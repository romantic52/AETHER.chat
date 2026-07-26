#!/usr/bin/env python3
"""Полный деплой backend'а AETHER на прод-хост.

В отличие от srv_admin.py (только точечные захардкоженные патчи) заливает
серверный код целиком: бэкап → копирование файлов → syntax-check → зависимости
→ restart → health. Миграции схемы применяет сам сервер в init_db() на старте.

Использование:
    python scripts/deploy_server.py --dry-run   # что будет сделано
    python scripts/deploy_server.py             # деплой
    python scripts/deploy_server.py --rollback  # откат на прошлый бэкап

Креды берутся из окружения или .env в корне репозитория:
    AETHER_SSH_HOST / AETHER_SSH_USER / AETHER_SSH_PASSWORD
Ничего не выкладывается без явного запуска этого скрипта.
"""
from __future__ import annotations

import argparse
import os
import posixpath
import re
import sys
from datetime import datetime, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REMOTE_ROOT = "/root/secure_messenger"
SERVICE = "secure_messenger"
DEFAULT_PORT = 8000

# Что именно уезжает на сервер. Медиа (uploads/), .env и venv не трогаем.
FILES = [
    ("server/main.py", "server/main.py"),
    ("server/apns.py", "server/apns.py"),
    ("requirements.txt", "requirements.txt"),
]


def load_env() -> dict:
    values = {}
    try:
        with open(os.path.join(REPO, ".env"), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    values[k.strip()] = v.strip().strip('"').strip("'")
    except OSError:
        pass
    return values


def health_port(cli) -> int:
    """Порт берём из юнита (ExecStart --port N), а не угадываем."""
    override = os.environ.get("AETHER_SERVER_PORT")
    if override and override.isdigit():
        return int(override)
    _, unit, _ = run(cli, f"systemctl cat {SERVICE} 2>/dev/null")
    match = re.search(r"--port[= ](\d+)", unit)
    return int(match.group(1)) if match else DEFAULT_PORT


def run(cli, cmd: str) -> tuple[int, str, str]:
    _, out, err = cli.exec_command(cmd, timeout=180)
    stdout = out.read().decode("utf-8", "replace")
    stderr = err.read().decode("utf-8", "replace")
    return out.channel.recv_exit_status(), stdout, stderr


def main() -> int:
    parser = argparse.ArgumentParser(description="Деплой AETHER backend")
    parser.add_argument("--dry-run", action="store_true", help="показать план, ничего не менять")
    parser.add_argument("--rollback", action="store_true", help="вернуть последний бэкап")
    parser.add_argument("--no-restart", action="store_true", help="залить файлы без рестарта")
    args = parser.parse_args()

    env = load_env()
    host = os.environ.get("AETHER_SSH_HOST") or env.get("AETHER_SSH_HOST")
    user = os.environ.get("AETHER_SSH_USER") or env.get("AETHER_SSH_USER", "root")
    password = os.environ.get("AETHER_SSH_PASSWORD") or env.get("AETHER_SSH_PASSWORD")
    if not host or not password:
        return fail("AETHER_SSH_HOST/AETHER_SSH_PASSWORD не найдены (окружение или .env)")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    backup_dir = posixpath.join(REMOTE_ROOT, "backups", stamp)

    if args.dry_run:
        print(f"host:     {user}@{host}")
        print(f"remote:   {REMOTE_ROOT}")
        print(f"backup:   {backup_dir}")
        print("files:")
        for local, remote in FILES:
            size = os.path.getsize(os.path.join(REPO, local))
            print(f"  {local} -> {posixpath.join(REMOTE_ROOT, remote)} ({size} байт)")
        print(f"после заливки: py_compile → pip install -r requirements.txt → systemctl restart {SERVICE} → health")
        return 0

    import paramiko

    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(host, username=user, password=password, timeout=30)
    try:
        if args.rollback:
            code, out, err = run(cli, f"ls -1 {REMOTE_ROOT}/backups | sort | tail -n 1")
            last = out.strip()
            if not last:
                return fail("бэкапов нет")
            print(f"откат на {last}")
            for _, remote in FILES:
                src = posixpath.join(REMOTE_ROOT, "backups", last, posixpath.basename(remote))
                dst = posixpath.join(REMOTE_ROOT, remote)
                code, out, err = run(cli, f"test -f {src} && cp {src} {dst} && echo RESTORED {remote}")
                print(out.strip() or err.strip())
            code, out, _ = run(cli, f"systemctl restart {SERVICE} && sleep 3 && systemctl is-active {SERVICE}")
            print("service:", out.strip())
            return 0

        # 1. Бэкап текущих файлов
        run(cli, f"mkdir -p {backup_dir}")
        for _, remote in FILES:
            path = posixpath.join(REMOTE_ROOT, remote)
            run(cli, f"test -f {path} && cp {path} {backup_dir}/")
        print(f"бэкап: {backup_dir}")

        # 2. Заливка
        sftp = cli.open_sftp()
        try:
            for local, remote in FILES:
                target = posixpath.join(REMOTE_ROOT, remote)
                run(cli, f"mkdir -p {posixpath.dirname(target)}")
                sftp.put(os.path.join(REPO, local), target)
                print(f"залит: {remote}")
        finally:
            sftp.close()

        # 3. Синтаксис — до рестарта, иначе сервис ляжет
        code, out, err = run(cli, f"cd {REMOTE_ROOT} && python3 -m py_compile server/main.py server/apns.py && echo SYNTAX_OK")
        if "SYNTAX_OK" not in out:
            print(err or out)
            return fail("синтаксическая ошибка — рестарт отменён, файлы в бэкапе " + backup_dir)
        print("syntax: OK")

        # 4. Зависимости (новые пакеты появляются вместе с фичами)
        code, out, err = run(cli, f"cd {REMOTE_ROOT} && pip3 install -q -r requirements.txt 2>&1 | tail -n 3; echo DEPS_DONE")
        print("deps:", (out or err).strip().splitlines()[-1] if (out or err).strip() else "ok")

        if args.no_restart:
            print("рестарт пропущен (--no-restart)")
            return 0

        # 5. Рестарт + health (init_db мигрирует схему на старте)
        code, out, _ = run(cli, f"systemctl restart {SERVICE} && sleep 4 && systemctl is-active {SERVICE}")
        state = out.strip()
        print("service:", state)
        if state != "active":
            _, log, _ = run(cli, f"journalctl -u {SERVICE} -n 30 --no-pager")
            print(log)
            return fail("сервис не поднялся — откат: python scripts/deploy_server.py --rollback")

        port = health_port(cli)
        code, out, _ = run(cli, f"curl -s -o /dev/null -w '%{{http_code}}' http://127.0.0.1:{port}/health")
        print(f"health (:{port}):", out.strip())
        if out.strip() != "200":
            _, log, _ = run(cli, f"journalctl -u {SERVICE} -n 30 --no-pager")
            print(log)
            return fail("health не 200 — откат: python scripts/deploy_server.py --rollback")
        print("деплой завершён")
        return 0
    finally:
        cli.close()


def fail(message: str) -> int:
    print("ОШИБКА:", message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
