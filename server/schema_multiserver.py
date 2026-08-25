"""Схема мультисерверности: роли, заявки, инвайты, аудит, refresh, импорт.

Только ДОБАВЛЕНИЕ таблиц. Ни одного DROP, ни одного изменения типа колонки —
эта же база обслуживает работающих пользователей iOS, Android и веба.
Стиль тот же, что у init_db: всё идемпотентно, наличие колонок проверяется
через information_schema.

Раскладка и связи описаны в docs/MULTI_SERVER_DESIGN.md, разделы 2.1 и 3.
"""

from __future__ import annotations


def apply(cur) -> None:
    _roles(cur)
    _registration_requests(cur)
    _invites(cur)
    _audit(cur)
    _refresh_tokens(cur)
    _data_import(cur)
    _extra_columns(cur)


def _roles(cur) -> None:
    # Роль УРОВНЯ СЕРВЕРА. Не путать с group_members.role — та про одну группу.
    cur.execute(
        """CREATE TABLE IF NOT EXISTS server_roles (
            user_id    TEXT PRIMARY KEY,
            role       TEXT NOT NULL,
            granted_by TEXT,
            granted_at TEXT NOT NULL
        )"""
    )
    # CHECK добавляем отдельно: на уже существующей таблице CREATE TABLE
    # IF NOT EXISTS ограничение не создаст.
    cur.execute(
        """SELECT EXISTS (SELECT 1 FROM information_schema.table_constraints
           WHERE table_schema = current_schema()
             AND table_name = 'server_roles' AND constraint_name = 'server_roles_role_chk')"""
    )
    if not cur.fetchone()[0]:
        cur.execute(
            """ALTER TABLE server_roles ADD CONSTRAINT server_roles_role_chk
               CHECK (role IN ('OWNER','ADMIN','MODERATOR','USER'))"""
        )


def _registration_requests(cur) -> None:
    cur.execute(
        """CREATE TABLE IF NOT EXISTS registration_requests (
            request_id   TEXT PRIMARY KEY,
            code         TEXT NOT NULL UNIQUE,
            user_id      TEXT NOT NULL,
            display_name TEXT,
            message      TEXT,
            password_hash TEXT NOT NULL,
            public_key_b64            TEXT NOT NULL,
            encrypted_private_key_b64 TEXT,
            encrypted_olm_account_b64 TEXT,
            status       TEXT NOT NULL DEFAULT 'pending',
            created_at   TEXT NOT NULL,
            expires_at   TEXT NOT NULL,
            decided_at   TEXT,
            decided_by   TEXT,
            reject_reason TEXT,
            claim_token_hash TEXT NOT NULL,
            ip_hash      TEXT,
            ua_hash      TEXT
        )"""
    )
    cur.execute(
        "CREATE INDEX IF NOT EXISTS idx_reqs_status ON registration_requests(status, created_at)")
    # Один незакрытый запрос на логин: иначе одним именем можно завалить
    # администратору весь список заявок.
    cur.execute(
        """CREATE UNIQUE INDEX IF NOT EXISTS idx_reqs_pending_user
           ON registration_requests(user_id) WHERE status = 'pending'""")


def _invites(cur) -> None:
    # Сам код приглашения не хранится — только его sha256. Утечка дампа базы
    # не должна раздавать доступ на сервер.
    cur.execute(
        """CREATE TABLE IF NOT EXISTS invites (
            code_hash   TEXT PRIMARY KEY,
            label       TEXT,
            created_by  TEXT NOT NULL,
            created_at  TEXT NOT NULL,
            expires_at  TEXT,
            max_uses    INTEGER NOT NULL DEFAULT 1,
            uses        INTEGER NOT NULL DEFAULT 0,
            revoked     INTEGER NOT NULL DEFAULT 0,
            grants_role TEXT NOT NULL DEFAULT 'USER'
        )"""
    )


def _audit(cur) -> None:
    cur.execute(
        """CREATE TABLE IF NOT EXISTS audit_log (
            id        BIGSERIAL PRIMARY KEY,
            ts        TEXT NOT NULL,
            actor_id  TEXT,
            action    TEXT NOT NULL,
            target    TEXT,
            meta_json TEXT,
            ip_hash   TEXT
        )"""
    )
    cur.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log(ts DESC)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action, ts DESC)")


def _refresh_tokens(cur) -> None:
    # Хранится хеш: сервер, у которого увели дамп, не должен раздавать живые
    # сессии. family_id связывает цепочку ротации — повторное использование
    # уже потраченного refresh означает кражу и гасит всю семью.
    cur.execute(
        """CREATE TABLE IF NOT EXISTS refresh_tokens (
            token_hash  TEXT PRIMARY KEY,
            user_id     TEXT NOT NULL,
            device_id   TEXT NOT NULL DEFAULT 'primary',
            family_id   TEXT NOT NULL,
            issued_at   TEXT NOT NULL,
            expires_at  TEXT NOT NULL,
            used_at     TEXT,
            replaced_by TEXT,
            revoked     INTEGER NOT NULL DEFAULT 0
        )"""
    )
    cur.execute("CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_tokens(family_id)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens(user_id)")


def _data_import(cur) -> None:
    cur.execute(
        """CREATE TABLE IF NOT EXISTS import_sessions (
            session_id     TEXT PRIMARY KEY,
            user_id        TEXT NOT NULL,
            device_id      TEXT NOT NULL DEFAULT 'primary',
            created_at     TEXT NOT NULL,
            expires_at     TEXT NOT NULL,
            status         TEXT NOT NULL DEFAULT 'open',
            manifest_json  TEXT NOT NULL,
            categories     TEXT NOT NULL,
            total_bytes    BIGINT NOT NULL,
            chunk_count    INTEGER NOT NULL,
            received_bytes BIGINT NOT NULL DEFAULT 0,
            received_chunks INTEGER NOT NULL DEFAULT 0,
            manifest_sha256 TEXT NOT NULL,
            key_wrap_json  TEXT,
            completed_at   TEXT
        )"""
    )
    cur.execute("CREATE INDEX IF NOT EXISTS idx_import_user ON import_sessions(user_id, created_at DESC)")
    # Первичный ключ (session_id, seq) — он же идемпотентность: повторная
    # отправка чанка после обрыва не создаёт дубликата и не двигает счётчик.
    cur.execute(
        """CREATE TABLE IF NOT EXISTS import_chunks (
            session_id  TEXT NOT NULL,
            seq         INTEGER NOT NULL,
            category    TEXT NOT NULL,
            nonce_b64   TEXT NOT NULL,
            storage_ref TEXT NOT NULL,
            size        INTEGER NOT NULL,
            sha256      TEXT NOT NULL,
            received_at TEXT NOT NULL,
            PRIMARY KEY (session_id, seq)
        )"""
    )
    cur.execute(
        """CREATE TABLE IF NOT EXISTS data_erasure_requests (
            id         BIGSERIAL PRIMARY KEY,
            user_id    TEXT NOT NULL,
            scope      TEXT NOT NULL,
            created_at TEXT NOT NULL,
            status     TEXT NOT NULL DEFAULT 'received',
            handled_at TEXT,
            note       TEXT
        )"""
    )


def _extra_columns(cur) -> None:
    for table, column, ddl in (
        ("users", "approved_by", "ALTER TABLE users ADD COLUMN approved_by TEXT"),
        ("users", "disabled", "ALTER TABLE users ADD COLUMN disabled INTEGER NOT NULL DEFAULT 0"),
        ("sessions", "family_id", "ALTER TABLE sessions ADD COLUMN family_id TEXT"),
    ):
        cur.execute(
            """SELECT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = %s AND column_name = %s)""",
            (table, column),
        )
        if not cur.fetchone()[0]:
            cur.execute(ddl)
