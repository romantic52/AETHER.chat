"""Роли уровня сервера, проверки прав и журнал административных действий.

Главное правило: роль берётся ТОЛЬКО из таблицы server_roles по текущему
пользователю сессии. Ни тело запроса, ни заголовок, ни что-либо присланное
клиентом на роль не влияет. Клиентский UI прячет кнопки — это удобство,
а не защита (docs/MULTI_SERVER_DESIGN.md, 10).
"""

from __future__ import annotations

import hashlib
import json
import os
from typing import Optional

from fastapi import Header, HTTPException, Request

ROLE_ORDER = {"USER": 0, "MODERATOR": 1, "ADMIN": 2, "OWNER": 3}
ROLES = tuple(ROLE_ORDER)


def _main():
    try:
        from server import main
    except ImportError:
        import main
    return main


def _ident():
    try:
        from server import server_identity as ident
    except ImportError:
        import server_identity as ident
    return ident


# --- IP: только в виде хеша ---------------------------------------------------

def ip_hash(request: Optional[Request]) -> Optional[str]:
    if request is None:
        return None
    try:
        ip = _main()._real_client_ip(request)
    except Exception:
        return None
    ident = _ident()
    salt = ident.meta_get(ident.K_IP_SALT) or ""
    return hashlib.sha256((salt + "|" + ip).encode()).hexdigest()[:32]


# --- Роли ---------------------------------------------------------------------

def role_of(user_id: str) -> str:
    with _main().db_conn() as cur:
        cur.execute("SELECT role FROM server_roles WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        row = cur.fetchone()
    return row["role"] if row else "USER"


def set_role(cur, user_id: str, role: str, granted_by: Optional[str]) -> None:
    if role not in ROLE_ORDER:
        raise HTTPException(400, "bad_role")
    cur.execute(
        """INSERT INTO server_roles (user_id, role, granted_by, granted_at)
           VALUES (%s, %s, %s, %s)
           ON CONFLICT (user_id) DO UPDATE
             SET role = EXCLUDED.role, granted_by = EXCLUDED.granted_by,
                 granted_at = EXCLUDED.granted_at""",
        (user_id.lower(), role, (granted_by or "").lower() or None, _main()._utc_now()),
    )


def owners_count(cur) -> int:
    cur.execute("SELECT COUNT(*) AS n FROM server_roles WHERE role = 'OWNER'")
    return int(cur.fetchone()["n"])


def require_role(minimum: str):
    """Зависимость FastAPI: пускает, только если роль не ниже minimum.

    get_current_user из main вызывается напрямую, а не через Depends: модули
    маршрутов импортируются раньше, чем main дообъявляет свои зависимости,
    и любая связка «через Depends» зависела бы от порядка импорта.
    """
    if minimum not in ROLE_ORDER:
        raise ValueError(f"unknown role {minimum}")

    def dep(request: Request, authorization: str = Header(None)) -> str:
        current_user = _main().get_current_user(authorization)
        actual = role_of(current_user)
        if ROLE_ORDER[actual] < ROLE_ORDER[minimum]:
            audit("access.denied", actor=current_user, target=minimum,
                  meta={"role": actual, "path": request.url.path}, request=request)
            # Не раскрываем, существует ли ресурс: любой недостаток прав даёт
            # один и тот же ответ.
            raise HTTPException(403, "insufficient_role")
        return current_user

    return dep


# --- Аудит --------------------------------------------------------------------

def audit_with(cur, action: str, actor: Optional[str] = None, target: Optional[str] = None,
               meta: Optional[dict] = None, ip: Optional[str] = None) -> None:
    """Запись в журнал В ТОЙ ЖЕ транзакции, что и само действие.

    Не записалось в аудит — значит действие не произошло: откатывается всё.
    """
    cur.execute(
        """INSERT INTO audit_log (ts, actor_id, action, target, meta_json, ip_hash)
           VALUES (%s, %s, %s, %s, %s, %s)""",
        (_main()._utc_now(), (actor or "").lower() or None, action, target,
         json.dumps(meta, ensure_ascii=False) if meta else None, ip),
    )


def audit(action: str, actor: Optional[str] = None, target: Optional[str] = None,
          meta: Optional[dict] = None, request: Optional[Request] = None) -> None:
    """Отдельная запись, когда своей транзакции у события нет (отказы доступа)."""
    try:
        with _main().db_conn() as cur:
            audit_with(cur, action, actor, target, meta, ip_hash(request))
    except Exception:
        # Журнал не должен ронять обработку запроса.
        pass


# --- Бутстрап владельца -------------------------------------------------------

def ensure_owner_bootstrap() -> None:
    """AETHER_OWNER_USER=<login> назначает владельца при старте, если ролей нет.

    Без этой переменной сервер стартует без владельца и пишет об этом в лог:
    первым OWNER станет первый зарегистрировавшийся (см. claim_first_user).
    Именно поэтому режим регистрации нового инстанса — APPROVAL, а не OPEN.
    """
    wanted = (os.environ.get("AETHER_OWNER_USER") or "").strip().lower()
    if not wanted:
        return
    with _main().db_conn() as cur:
        if owners_count(cur):
            return
        cur.execute("SELECT 1 FROM users WHERE LOWER(user_id) = LOWER(%s)", (wanted,))
        if not cur.fetchone():
            return
        set_role(cur, wanted, "OWNER", granted_by=None)
        audit_with(cur, "role.bootstrap", actor=None, target=wanted, meta={"role": "OWNER"})


def claim_first_user(cur, user_id: str) -> bool:
    """Первый пользователь пустого сервера становится владельцем.

    Вызывается ВНУТРИ транзакции регистрации, поэтому гонки двух одновременных
    регистраций не бывает: вторая увидит уже занятую роль.
    """
    if owners_count(cur):
        return False
    set_role(cur, user_id, "OWNER", granted_by=None)
    audit_with(cur, "role.first_user", actor=user_id, target=user_id, meta={"role": "OWNER"})
    return True
