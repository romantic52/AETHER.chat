#!/usr/bin/env python3
"""Смок-тест обнаружения сервера: /server/info, /.well-known/aether, подпись.

Запуск против живого сервера:
    AETHER_URL=http://127.0.0.1:8099 python3 server/test_server_info.py

Проверяет ровно то, на что опирается клиент, решая «тот ли это сервер»:
подпись сходится, nonce возвращён, зеркало /api/v1 отвечает так же,
а подделанный документ подпись НЕ проходит.
"""
import base64
import json
import os
import secrets
import sys
import urllib.request

BASE = os.environ.get("AETHER_URL", "http://127.0.0.1:8099")

PROTOCOL_VERSION = 1


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=15) as r:
        return r.status, json.loads(r.read() or b"{}")


def b64u_decode(s: str) -> bytes:
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s.replace("-", "+").replace("_", "/") + pad)


def canonical(d: dict) -> bytes:
    return "\n".join([
        "AETHER-SERVER-INFO-1",
        d["server_id"],
        d["name"],
        d["api_url"],
        d["websocket_url"],
        d["registration_mode"],
        str(PROTOCOL_VERSION),
        d["signed_at"],
        d["nonce"],
    ]).encode("utf-8")


def check(label, ok):
    print(("  ok   " if ok else "  FAIL ") + label)
    if not ok:
        sys.exit(1)


def main():
    from nacl.signing import VerifyKey
    from nacl.exceptions import BadSignatureError

    nonce = secrets.token_hex(8)
    status, info = get(f"/.well-known/aether?nonce={nonce}")
    check(".well-known/aether отвечает 200", status == 200)
    check("protocol = aether", info.get("protocol") == "aether")
    check("server_id — непустой", bool(info.get("server_id")))
    check("nonce возвращён без изменений", info.get("nonce") == nonce)
    check("режим регистрации известен",
          info.get("registration_mode") in ("open", "approval", "invite_only", "closed"))

    vk = VerifyKey(b64u_decode(info["public_key_b64"]))
    try:
        vk.verify(canonical(info), b64u_decode(info["signature_b64"]))
        signed = True
    except BadSignatureError:
        signed = False
    check("подпись документа сходится", signed)

    # Подделка: меняем имя сервера, подпись обязана развалиться. Без этой
    # проверки предыдущая ничего не доказывает.
    forged = dict(info, name=info["name"] + " (подделка)")
    try:
        vk.verify(canonical(forged), b64u_decode(info["signature_b64"]))
        forged_passes = True
    except BadSignatureError:
        forged_passes = False
    check("подделанный документ подпись НЕ проходит", not forged_passes)

    status_v1, info_v1 = get("/api/v1/server/info")
    check("/api/v1/server/info отвечает 200", status_v1 == 200)
    check("тот же server_id под префиксом", info_v1["server_id"] == info["server_id"])

    status_legacy, info_legacy = get("/server/info")
    check("/server/info (без префикса) отвечает 200", status_legacy == 200)

    # Зеркалирование легаси-маршрутов: старый путь и /api/v1 дают одно и то же.
    _, health = get("/health")
    _, health_v1 = get("/api/v1/health")
    check("/health и /api/v1/health совпадают", health == health_v1)

    print(f"\nВсё сошлось. server_id={info['server_id']} режим={info['registration_mode']}")


if __name__ == "__main__":
    main()
