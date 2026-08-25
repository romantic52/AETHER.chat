"""Административные ручки сервера: пользователи, роли, приглашения, аудит,
настройки, сессии, хранилище.

Права проверяются зависимостью require_role, которая читает роль ИЗ БАЗЫ по
текущей сессии. Ничего присланного клиентом на решение не влияет.
Каждое изменяющее действие пишет audit_log в той же транзакции.

docs/MULTI_SERVER_DESIGN.md, разделы 8.5 и 10.
"""

from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, Field

try:
    from server import roles, registration_policy as policy, server_identity as ident
except ImportError:
    import roles
    import registration_policy as policy
    import server_identity as ident

router = APIRouter(prefix="/admin", tags=["admin"])


def _main():
    try:
        from server import main
    except ImportError:
        import main
    return main


# --- Overview -----------------------------------------------------------------

@router.get("/overview")
def overview(actor: str = Depends(roles.require_role("MODERATOR"))) -> dict:
    with _main().db_conn() as cur:
        cur.execute("SELECT COUNT(*) AS n FROM users")
        users = int(cur.fetchone()["n"])
        cur.execute("SELECT COUNT(*) AS n FROM registration_requests WHERE status = 'pending'")
        pending = int(cur.fetchone()["n"])
        cur.execute("SELECT COUNT(*) AS n FROM sessions")
        sessions = int(cur.fetchone()["n"])
        cur.execute("SELECT COUNT(*) AS n FROM messages")
        messages = int(cur.fetchone()["n"])
    return {
        "server_id": ident.server_id(),
        "name": ident.meta_get(ident.K_NAME),
        "registration_mode": ident.registration_mode().lower(),
        "users": users,
        "pending_requests": pending,
        "sessions": sessions,
        "messages_queued": messages,
        "your_role": roles.role_of(actor),
    }


# --- Пользователи -------------------------------------------------------------

@router.get("/users")
def list_users(query: Optional[str] = Query(default=None, max_length=64),
               limit: int = Query(default=50, ge=1, le=200),
               actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    sql = """SELECT u.user_id, u.display_name, u.username, u.created_at, u.account_no,
                    COALESCE(u.disabled, 0) AS disabled,
                    COALESCE(r.role, 'USER') AS role
             FROM users u LEFT JOIN server_roles r ON LOWER(r.user_id) = LOWER(u.user_id)"""
    params: list = []
    if query:
        sql += " WHERE u.user_id ILIKE %s OR u.display_name ILIKE %s"
        params += [f"%{query}%", f"%{query}%"]
    sql += " ORDER BY u.created_at DESC LIMIT %s"
    params.append(limit)
    with _main().db_conn() as cur:
        cur.execute(sql, tuple(params))
        rows = [dict(r) for r in cur.fetchall()]
    return {"users": rows}


def _guard_target(cur, actor: str, target: str) -> str:
    """Общие правила для действий над чужим аккаунтом."""
    if actor.lower() == target.lower():
        raise HTTPException(400, "cannot_target_self")
    cur.execute("SELECT 1 FROM users WHERE LOWER(user_id) = LOWER(%s)", (target,))
    if not cur.fetchone():
        raise HTTPException(404, "user_not_found")
    return roles.role_of(target)


@router.post("/users/{user_id}/disable")
def disable_user(user_id: str, request: Request,
                 actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    with _main().db_conn() as cur:
        target_role = _guard_target(cur, actor, user_id)
        if target_role == "OWNER":
            raise HTTPException(403, "cannot_disable_owner")
        cur.execute("UPDATE users SET disabled = 1 WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        # Блокировка обязана действовать сразу, а не со следующего входа:
        # снимаем живые сессии и refresh-токены.
        cur.execute("DELETE FROM sessions WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        cur.execute("UPDATE refresh_tokens SET revoked = 1 WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        roles.audit_with(cur, "user.disable", actor, user_id.lower(),
                         ip=roles.ip_hash(request))
    return {"ok": True}


@router.post("/users/{user_id}/enable")
def enable_user(user_id: str, request: Request,
                actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    with _main().db_conn() as cur:
        _guard_target(cur, actor, user_id)
        cur.execute("UPDATE users SET disabled = 0 WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        roles.audit_with(cur, "user.enable", actor, user_id.lower(), ip=roles.ip_hash(request))
    return {"ok": True}


@router.delete("/users/{user_id}")
def delete_user(user_id: str, request: Request,
                actor: str = Depends(roles.require_role("OWNER"))) -> dict:
    with _main().db_conn() as cur:
        target_role = _guard_target(cur, actor, user_id)
        if target_role == "OWNER" and roles.owners_count(cur) <= 1:
            raise HTTPException(409, "last_owner")
        cur.execute("DELETE FROM sessions WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        cur.execute("DELETE FROM server_roles WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        cur.execute("DELETE FROM users WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        roles.audit_with(cur, "user.delete", actor, user_id.lower(), ip=roles.ip_hash(request))
    return {"ok": True}


# --- Роли ---------------------------------------------------------------------

class SetRoleRequest(BaseModel):
    role: str = Field(pattern=r"^(OWNER|ADMIN|MODERATOR|USER)$")


@router.get("/roles")
def list_roles(actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    with _main().db_conn() as cur:
        cur.execute("SELECT user_id, role, granted_by, granted_at FROM server_roles ORDER BY role DESC, user_id")
        return {"roles": [dict(r) for r in cur.fetchall()]}


@router.put("/roles/{user_id}")
def set_user_role(user_id: str, body: SetRoleRequest, request: Request,
                  actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    actor_role = roles.role_of(actor)
    with _main().db_conn() as cur:
        target_role = _guard_target(cur, actor, user_id)
        # ADMIN распоряжается только нижними ролями. Раздавать ADMIN/OWNER —
        # право владельца: иначе любой админ мгновенно становится владельцем.
        if body.role in ("OWNER", "ADMIN") or target_role in ("OWNER", "ADMIN"):
            if actor_role != "OWNER":
                raise HTTPException(403, "owner_required")
        if target_role == "OWNER" and body.role != "OWNER" and roles.owners_count(cur) <= 1:
            raise HTTPException(409, "last_owner")
        roles.set_role(cur, user_id, body.role, granted_by=actor)
        roles.audit_with(cur, "role.grant", actor, user_id.lower(),
                         meta={"from": target_role, "to": body.role}, ip=roles.ip_hash(request))
    return {"ok": True, "user_id": user_id.lower(), "role": body.role}


# --- Приглашения --------------------------------------------------------------

class CreateInviteRequest(BaseModel):
    label: Optional[str] = Field(default=None, max_length=128)
    expires_at: Optional[str] = Field(default=None, max_length=64)
    max_uses: int = Field(default=1, ge=1, le=1000)


@router.get("/invites")
def list_invites(actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    with _main().db_conn() as cur:
        cur.execute(
            """SELECT code_hash, label, created_by, created_at, expires_at,
                      max_uses, uses, revoked, grants_role
               FROM invites ORDER BY created_at DESC LIMIT 200""")
        # Сам код не возвращается никогда — его нет в базе, только хеш.
        return {"invites": [dict(r) for r in cur.fetchall()]}


@router.post("/invites")
def create_invite(body: CreateInviteRequest, request: Request,
                  actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    code = policy.new_invite_code()
    with _main().db_conn() as cur:
        cur.execute(
            """INSERT INTO invites (code_hash, label, created_by, created_at,
                                    expires_at, max_uses, grants_role)
               VALUES (%s, %s, %s, %s, %s, %s, 'USER')""",
            (policy.hash_code(code), body.label, actor.lower(), _main()._utc_now(),
             body.expires_at, body.max_uses),
        )
        roles.audit_with(cur, "invite.create", actor, meta={"label": body.label,
                         "max_uses": body.max_uses}, ip=roles.ip_hash(request))
    # Единственный момент, когда код виден. Дальше его знает только тот,
    # кому его передали.
    return {"ok": True, "code": code}


@router.delete("/invites/{code_hash}")
def revoke_invite(code_hash: str, request: Request,
                  actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    with _main().db_conn() as cur:
        cur.execute("UPDATE invites SET revoked = 1 WHERE code_hash = %s", (code_hash,))
        if cur.rowcount == 0:
            raise HTTPException(404, "invite_not_found")
        roles.audit_with(cur, "invite.revoke", actor, code_hash[:16], ip=roles.ip_hash(request))
    return {"ok": True}


# --- Аудит и сессии -----------------------------------------------------------

@router.get("/audit")
def read_audit(action: Optional[str] = Query(default=None, max_length=64),
               limit: int = Query(default=100, ge=1, le=500),
               actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    sql = "SELECT id, ts, actor_id, action, target, meta_json FROM audit_log"
    params: list = []
    if action:
        sql += " WHERE action = %s"
        params.append(action)
    sql += " ORDER BY id DESC LIMIT %s"
    params.append(limit)
    with _main().db_conn() as cur:
        cur.execute(sql, tuple(params))
        return {"entries": [dict(r) for r in cur.fetchall()]}


@router.get("/sessions")
def list_sessions(user_id: Optional[str] = Query(default=None, max_length=64),
                  actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    sql = "SELECT user_id, created_at, expires_at FROM sessions"
    params: list = []
    if user_id:
        sql += " WHERE LOWER(user_id) = LOWER(%s)"
        params.append(user_id)
    sql += " ORDER BY created_at DESC LIMIT 200"
    with _main().db_conn() as cur:
        cur.execute(sql, tuple(params))
        # Токены не отдаются даже администратору: знать их незачем.
        return {"sessions": [dict(r) for r in cur.fetchall()]}


@router.delete("/sessions/{user_id}")
async def kill_sessions(user_id: str, request: Request,
                        actor: str = Depends(roles.require_role("ADMIN"))) -> dict:
    if roles.role_of(user_id) == "OWNER" and roles.role_of(actor) != "OWNER":
        raise HTTPException(403, "owner_required")
    with _main().db_conn() as cur:
        cur.execute("SELECT token FROM sessions WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        tokens = [r["token"] for r in cur.fetchall()]
        cur.execute("DELETE FROM sessions WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        cur.execute("UPDATE refresh_tokens SET revoked = 1 WHERE LOWER(user_id) = LOWER(%s)", (user_id,))
        roles.audit_with(cur, "session.revoke_all", actor, user_id.lower(),
                         meta={"count": len(tokens)}, ip=roles.ip_hash(request))
    # Живые сокеты рвём сразу: иначе отозванная сессия продолжает получать всё.
    for token in tokens:
        await _main().manager.close_for_token(token)
    return {"ok": True, "revoked": len(tokens)}


# --- Настройки сервера --------------------------------------------------------

class SettingsRequest(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=128)
    # Инлайновый флаг (?i) не в начале выражения ломает re в Python 3.11+,
    # поэтому перечисляем оба регистра явно.
    registration_mode: Optional[str] = Field(
        default=None,
        pattern=r"^(?:open|approval|invite_only|closed|OPEN|APPROVAL|INVITE_ONLY|CLOSED)$")
    data_import_enabled: Optional[bool] = None
    import_quota_bytes: Optional[int] = Field(default=None, ge=0, le=1024 ** 4)


@router.get("/settings")
def get_settings(actor: str = Depends(roles.require_role("OWNER"))) -> dict:
    return {
        "server_id": ident.server_id(),
        "name": ident.meta_get(ident.K_NAME),
        "registration_mode": ident.registration_mode().lower(),
        "data_import_enabled": (ident.meta_get(ident.K_IMPORT_ENABLED) or "1") == "1",
        "import_quota_bytes": int(ident.meta_get(ident.K_IMPORT_QUOTA) or 0),
        "request_ttl_days": int(ident.meta_get(ident.K_REQUEST_TTL) or 14),
    }


@router.put("/settings")
async def put_settings(body: SettingsRequest, request: Request,
                       actor: str = Depends(roles.require_role("OWNER"))) -> dict:
    changed = {}
    if body.name is not None:
        ident.meta_set(ident.K_NAME, body.name)
        changed["name"] = body.name
    if body.registration_mode is not None:
        mode = body.registration_mode.upper()
        ident.meta_set(ident.K_REG_MODE, mode)
        changed["registration_mode"] = mode
    if body.data_import_enabled is not None:
        ident.meta_set(ident.K_IMPORT_ENABLED, "1" if body.data_import_enabled else "0")
        changed["data_import_enabled"] = body.data_import_enabled
    if body.import_quota_bytes is not None:
        ident.meta_set(ident.K_IMPORT_QUOTA, str(body.import_quota_bytes))
        changed["import_quota_bytes"] = body.import_quota_bytes

    with _main().db_conn() as cur:
        roles.audit_with(cur, "server.settings", actor, meta=changed, ip=roles.ip_hash(request))
    return {"ok": True, "changed": changed}
