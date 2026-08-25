"""Политика допуска новых пользователей: OPEN / APPROVAL / INVITE_ONLY / CLOSED.

Проверяется на СЕРВЕРЕ. Клиент читает режим из /server/info только чтобы
показать правильную кнопку — на решение это не влияет: клиент, который
попросит зарегистрироваться на закрытом сервере, получит отказ.

docs/MULTI_SERVER_DESIGN.md, разделы 4, 5, 6.1.
"""

from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timezone
from typing import Optional

from fastapi import HTTPException


def _main():
    try:
        from server import main
    except ImportError:
        import main
    return main


def hash_code(code: str) -> str:
    """Инвайт хранится только хешем: дамп базы не должен раздавать доступ."""
    return hashlib.sha256(code.strip().encode()).hexdigest()


def new_invite_code() -> str:
    # Читаемый код без похожих символов: его диктуют голосом и переписывают.
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    raw = "".join(secrets.choice(alphabet) for _ in range(12))
    return f"{raw[:4]}-{raw[4:8]}-{raw[8:]}"


def _expired(value: Optional[str]) -> bool:
    if not value:
        return False
    try:
        return datetime.now(timezone.utc) > datetime.fromisoformat(value)
    except (ValueError, TypeError):
        return False


def consume_invite(cur, code: Optional[str]) -> str:
    """Проверить и потратить приглашение. Возвращает выдаваемую роль.

    Счётчик увеличивается тем же UPDATE, что и проверяет остаток, поэтому две
    одновременные регистрации по коду с max_uses=1 не пройдут обе.
    """
    if not code or not code.strip():
        raise HTTPException(403, "invite_required")
    code_hash = hash_code(code)
    cur.execute(
        """SELECT code_hash, expires_at, max_uses, uses, revoked, grants_role
           FROM invites WHERE code_hash = %s""", (code_hash,))
    row = cur.fetchone()
    if not row or row["revoked"] or _expired(row["expires_at"]):
        raise HTTPException(403, "invite_invalid")
    cur.execute(
        """UPDATE invites SET uses = uses + 1
           WHERE code_hash = %s AND revoked = 0 AND uses < max_uses
           RETURNING grants_role""", (code_hash,))
    used = cur.fetchone()
    if not used:
        raise HTTPException(403, "invite_invalid")
    return used["grants_role"] or "USER"


def enforce(cur, invite_code: Optional[str]) -> str:
    """Пустить или отказать по текущему режиму сервера.

    Возвращает роль, которую получит новый пользователь.
    """
    try:
        from server import server_identity as ident
    except ImportError:
        import server_identity as ident

    mode = ident.registration_mode()
    if mode == "OPEN":
        return "USER"
    if mode == "INVITE_ONLY":
        return consume_invite(cur, invite_code)
    if mode == "APPROVAL":
        # Прямая регистрация закрыта, но путь есть: подать заявку.
        # Код разбирается клиентом и ведёт его на нужный экран.
        raise HTTPException(403, "approval_required")
    raise HTTPException(403, "registration_closed")


# --- Бутстрап владельца -------------------------------------------------------
#
# Свежий инстанс стартует в APPROVAL, то есть зарегистрироваться не может никто,
# включая будущего владельца — заявку одобрять некому. Разрывает круг одноразовый
# код, который сервер печатает в свой лог при первом старте: тот, кто поднял VPS,
# читает его в journalctl/docker logs и вводит в приложении как код приглашения.
# Интернету код неизвестен, поэтому «кто первый нашёл сервер, тот и владелец»
# не случается.

K_BOOTSTRAP = "aether.bootstrap_code_hash"


def ensure_bootstrap_code(log) -> None:
    """Идемпотентно: код существует, пока им не воспользовались."""
    try:
        from server import roles, server_identity as ident
    except ImportError:
        import roles
        import server_identity as ident

    with _main().db_conn() as cur:
        if roles.owners_count(cur):
            return
        cur.execute("SELECT value FROM server_meta WHERE key = %s", (K_BOOTSTRAP,))
        if cur.fetchone():
            log.warning("AETHER: владелец не назначен. Код владельца уже выдан "
                        "ранее — ищите его в логе первого старта.")
            return
        code = new_invite_code()
        cur.execute("INSERT INTO server_meta (key, value) VALUES (%s, %s)",
                    (K_BOOTSTRAP, hash_code(code)))
        log.warning(
            "\n"
            "=========================================================\n"
            " AETHER: у сервера ещё нет владельца.\n"
            " Код владельца (одноразовый, вводится как приглашение):\n"
            "     %s\n"
            " Зарегистрируйтесь с ним — аккаунт получит роль OWNER.\n"
            "=========================================================", code)


def try_bootstrap(cur, code: Optional[str]) -> bool:
    """Совпал ли код с бутстрап-кодом. Совпал — код гасится немедленно."""
    try:
        from server import roles
    except ImportError:
        import roles

    if not code or not code.strip() or roles.owners_count(cur):
        return False
    cur.execute("SELECT value FROM server_meta WHERE key = %s", (K_BOOTSTRAP,))
    row = cur.fetchone()
    if not row:
        return False
    import hmac
    if not hmac.compare_digest(row["value"], hash_code(code)):
        return False
    cur.execute("DELETE FROM server_meta WHERE key = %s", (K_BOOTSTRAP,))
    return True
