"""
Relay-сервер: хранит только публичные ключи и зашифрованные сообщения.
Никогда не принимает plaintext.
"""

from __future__ import annotations

import base64
import hmac
import json
import os
import psycopg2
import psycopg2.extras
import struct
import time
import uuid
import hashlib
import secrets
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, Depends, Header, Request, WebSocket, WebSocketDisconnect, UploadFile, File
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import asyncio

from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded

from fastapi.staticfiles import StaticFiles

# Работает и как пакет server.main (uvicorn server.main:app), и из папки server/.
try:
    from server import apns
except ImportError:
    import apns

WEB_DIR = Path(__file__).resolve().parent.parent / "web"

class ConnectionManager:
    def __init__(self):
        self.active_connections: dict[str, set[WebSocket]] = {}
        # Отзыв токена должен рвать и живой WebSocket, открытый этим токеном,
        # иначе после /logout остаётся рабочий realtime-канал.
        self.token_connections: dict[str, set[WebSocket]] = {}

    async def connect(self, websocket: WebSocket, user_id: str, token: str = ""):
        await websocket.accept()
        if user_id not in self.active_connections:
            self.active_connections[user_id] = set()
        self.active_connections[user_id].add(websocket)
        if token:
            self.token_connections.setdefault(token, set()).add(websocket)

    def disconnect(self, websocket: WebSocket, user_id: str, token: str = ""):
        if user_id in self.active_connections:
            self.active_connections[user_id].discard(websocket)
            if not self.active_connections[user_id]:
                del self.active_connections[user_id]
        if token and token in self.token_connections:
            self.token_connections[token].discard(websocket)
            if not self.token_connections[token]:
                del self.token_connections[token]

    async def close_for_token(self, token: str):
        """Закрыть все сокеты, поднятые отозванным токеном (policy violation 1008)."""
        for ws in list(self.token_connections.pop(token, ())):
            try:
                await ws.close(code=1008)
            except Exception:
                pass

    async def send_personal_message(self, message: dict, user_id: str):
        if user_id in self.active_connections:
            for connection in self.active_connections[user_id]:
                try:
                    await connection.send_json(message)
                except Exception:
                    pass

manager = ConnectionManager()

# --- Rate Limiter (#4) ---
# (#P4.12) За реверс-прокси все запросы приходят с локального IP,
# поэтому лимиты считались бы на один общий ключ. Берём реальный IP из
# X-Forwarded-For, но ТОЛЬКО если непосредственный клиент — доверенный прокси
# (иначе заголовок можно подделать).
TRUSTED_PROXIES = {"127.0.0.1", "::1"}


def _real_client_ip(request: Request) -> str:
    direct_ip = get_remote_address(request)
    if direct_ip in TRUSTED_PROXIES:
        xff = request.headers.get("x-forwarded-for")
        if xff:
            # Первый адрес в цепочке — исходный клиент
            return xff.split(",")[0].strip()
    return direct_ip


limiter = Limiter(key_func=_real_client_ip)

app = FastAPI(title="Secure Messenger Relay", version="0.2.0")
app.state.limiter = limiter


# Кастомный обработчик 429: отдаём detail (клиент читает именно это поле),
# Retry-After и логируем ключ лимитера для диагностики.
def _rate_limit_handler(request: Request, exc: RateLimitExceeded):
    from fastapi.responses import JSONResponse
    try:
        key = _real_client_ip(request)
    except Exception:
        key = "?"
    logging.getLogger("secure_messenger").warning(
        "429 rate-limit: path=%s key=%s limit=%s", request.url.path, key, exc.detail
    )
    return JSONResponse(
        status_code=429,
        content={"detail": "Слишком много попыток. Подождите минуту и попробуйте снова."},
        headers={"Retry-After": "60"},
    )


app.add_exception_handler(RateLimitExceeded, _rate_limit_handler)

# --- CORS (#P4.11): только домены сервера вместо wildcard ---
# (#A4) Домены настраиваются через env (CSV), хардкод IP — лишь дефолт для dev.
ALLOWED_ORIGINS = [
    o.strip()
    for o in os.environ.get(
        "ALLOWED_ORIGINS", "https://your-server.example.com,https://YOUR_SERVER_IP"
    ).split(",")
    if o.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Browser hardening: user content is ciphertext until it reaches the client,
# and the client must not be able to turn it into executable markup.
@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("Content-Security-Policy", (
        "default-src 'self'; base-uri 'self'; object-src 'none'; "
        "frame-ancestors 'none'; script-src 'self' 'wasm-unsafe-eval'; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdnjs.cloudflare.com; "
        "font-src 'self' https://fonts.gstatic.com https://cdnjs.cloudflare.com; "
        "img-src 'self' data: blob: https: http:; media-src 'self' data: blob: https: http:; "
        "connect-src 'self' https: wss: http://localhost:* http://127.0.0.1:* "
        "http://10.0.2.2:* ws://localhost:* ws://127.0.0.1:* ws://10.0.2.2:*"
    ))
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("Permissions-Policy", "camera=(self), microphone=(self), geolocation=()")
    # Код веб-клиента без версионирования имён файлов: браузер обязан
    # ревалидировать (ETag), иначе после деплоя неделями живёт старый app.js.
    if request.url.path.endswith((".js", ".html", ".css")) or request.url.path == "/":
        response.headers["Cache-Control"] = "no-cache"
    return response

SESSION_LIFETIME_DAYS = 30


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _session_expires() -> str:
    return (datetime.now(timezone.utc) + timedelta(days=SESSION_LIFETIME_DAYS)).isoformat()
import logging

logging.basicConfig(level=logging.DEBUG)
logger = logging.getLogger("secure_messenger")

# --- DB credentials from environment (#5) ---
@contextmanager
def db_conn():
    conn = psycopg2.connect(
        dbname=os.environ.get("DB_NAME", "secure_messenger"),
        user=os.environ.get("DB_USER", "sm_user"),
        password=os.environ.get("DB_PASS", "sm_pass"),
        host=os.environ.get("DB_HOST", "127.0.0.1"),
    )
    cur = conn.cursor(cursor_factory=psycopg2.extras.DictCursor)
    try:
        yield cur
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        cur.close()
        conn.close()


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    iterations = 100000
    hash_bytes = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), iterations
    )
    return f"pbkdf2_sha256${iterations}${salt}${hash_bytes.hex()}"


def verify_password(password: str, hashed: str) -> bool:
    try:
        parts = hashed.split("$")
        if len(parts) != 4 or parts[0] != "pbkdf2_sha256":
            return False
        iterations = int(parts[1])
        salt = parts[2]
        expected_hex = parts[3]
        hash_bytes = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt.encode("utf-8"), iterations
        )
        return secrets.compare_digest(hash_bytes.hex(), expected_hex)
    except Exception:
        return False


# --- Session management (#3): check expiry ---
def get_current_user(authorization: str = Header(None)) -> str:
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(401, "Missing or invalid authorization header")
    token = token.strip()
    with db_conn() as cur:
        cur.execute("SELECT user_id, expires_at FROM sessions WHERE token = %s", (token,))
        row = cur.fetchone()
    if not row:
        raise HTTPException(401, "Session expired or invalid")
    # Check session expiry
    if row["expires_at"]:
        try:
            expires = datetime.fromisoformat(row["expires_at"])
            if datetime.now(timezone.utc) > expires:
                # Clean up expired session
                with db_conn() as cur:
                    cur.execute("DELETE FROM sessions WHERE token = %s", (token,))
                raise HTTPException(401, "Session expired")
        except (ValueError, TypeError):
            pass
    return row["user_id"].lower()


def init_db() -> None:
    with db_conn() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                public_key_b64 TEXT NOT NULL,
                password_hash TEXT,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT
            );
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                sender_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                envelope_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS groups (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                owner_id TEXT NOT NULL,
                is_channel INTEGER NOT NULL DEFAULT 0,
                linked_group_id TEXT,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS group_members (
                group_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                encrypted_key_b64 TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'member',
                PRIMARY KEY (group_id, user_id)
            );
            -- (#A4) Удалены таблицы user_chat_settings (клиент хранит локально)
            -- и items/user_items (магазин — мёртвый код). Существующие БД
            -- сохраняют старые таблицы — они просто не используются.
            -- (#A1) Подтверждения доставки: сообщение считается доставленным
            -- пользователю ТОЛЬКО после его явного ACK (клиент сохранил в БД).
            -- Работает и для личных, и для групповых сообщений (у группы много получателей).
            CREATE TABLE IF NOT EXISTS message_receipts (
                message_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL DEFAULT 'primary',
                acked_at TEXT NOT NULL,
                PRIMARY KEY (message_id, user_id, device_id)
            );
            -- Просмотры постов каналов: уникальный зритель на сообщение.
            CREATE TABLE IF NOT EXISTS post_views (
                message_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                viewed_at TEXT NOT NULL,
                PRIMARY KEY (message_id, user_id)
            );
            -- APNs-токены устройств: kind = apns (баннеры) | voip (звонки/CallKit).
            -- Токен уникален per-устройство; смена аккаунта на устройстве — перезапись.
            CREATE TABLE IF NOT EXISTS device_push_tokens (
                token TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'apns',
                updated_at TEXT NOT NULL
            );
            -- Prekey-директория для Double Ratchet (Olm/X3DH). Сервер только
            -- раздаёт публичные one-time keys, приватных не видит. Каждый OTK
            -- одноразовый: при claim удаляется, чтобы обеспечить forward secrecy.
            CREATE TABLE IF NOT EXISTS one_time_keys (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL DEFAULT 'primary',
                key_id TEXT NOT NULL,
                key_b64 TEXT NOT NULL,
                PRIMARY KEY (user_id, device_id, key_id)
            );
            """
        )
        
        # Публичные каналы: public_join=1 — любой может подписаться; ключ канала
        # хранится у сервера (осознанный трейдофф ТОЛЬКО для публичного контента,
        # E2E личек/групп/приватных каналов не затронут) и выдаётся подписчику
        # обычным конвертом. server_meta — ключи самого сервера.
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS server_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """
        )
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='users' AND column_name='status_emoji')""")
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE users ADD COLUMN status_emoji TEXT")

        # Olm identity-ключ (curve25519) для Double Ratchet — публичный, отдаётся в prekey-bundle.
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='users' AND column_name='olm_identity_key')""")
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE users ADD COLUMN olm_identity_key TEXT")

        for col, ddl in [("public_join", "INTEGER NOT NULL DEFAULT 0"),
                         ("join_key_b64", "TEXT"),
                         ("username", "TEXT"),
                         ("avatar_file_id", "TEXT")]:
            cur.execute(
                """SELECT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name='groups' AND column_name=%s)""", (col,))
            if not cur.fetchone()[0]:
                cur.execute(f"ALTER TABLE groups ADD COLUMN {col} {ddl}")
        cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_groups_username ON groups(LOWER(username))")

        # Migrations — must use SAVEPOINTs in PostgreSQL
        # because a failed statement aborts the whole transaction.

        # 1) Rename legacy column if it exists
        cur.execute("""
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name='messages' AND column_name='ciphertext'
            )
        """)
        if cur.fetchone()[0]:
            cur.execute("ALTER TABLE messages RENAME COLUMN ciphertext TO envelope_json")
        
        # 2) Ensure delivered column exists
        cur.execute("""
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name='messages' AND column_name='delivered'
            )
        """)
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE messages ADD COLUMN delivered INTEGER NOT NULL DEFAULT 0")

    
    with db_conn() as cur:
        # Check password_hash
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns 
                WHERE table_schema = current_schema() AND table_name='users' AND column_name='password_hash'
            )
            """
        )
        has_password_hash = cur.fetchone()[0]
        if not has_password_hash:
            cur.execute("ALTER TABLE users ADD COLUMN password_hash TEXT")
            
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns 
                WHERE table_schema = current_schema() AND table_name='users' AND column_name='encrypted_private_key_b64'
            )
            """
        )
        has_encrypted_key = cur.fetchone()[0]
        if not has_encrypted_key:
            cur.execute("ALTER TABLE users ADD COLUMN encrypted_private_key_b64 TEXT")

        # Password-encrypted Olm account pickle for browser recovery. The relay
        # never receives the account plaintext or password-derived key.
        cur.execute(
            """SELECT EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_schema = current_schema() AND table_name='users' AND column_name='encrypted_olm_account_b64')"""
        )
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE users ADD COLUMN encrypted_olm_account_b64 TEXT")

        # Multi-device (v1): устройство = свой Olm-аккаунт. Всё, что было
        # загружено до этой миграции, становится устройством 'primary' —
        # старые клиенты продолжают работать, не зная о девайсах.
        cur.execute(
            """CREATE TABLE IF NOT EXISTS crypto_devices (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                identity_key_b64 TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (user_id, device_id)
            )"""
        )
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='one_time_keys' AND column_name='device_id')""")
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE one_time_keys ADD COLUMN device_id TEXT NOT NULL DEFAULT 'primary'")
            cur.execute("ALTER TABLE one_time_keys DROP CONSTRAINT one_time_keys_pkey")
            cur.execute("ALTER TABLE one_time_keys ADD PRIMARY KEY (user_id, device_id, key_id)")
        # ACK'и — per-device: иначе подтверждение с одного устройства прятало бы
        # групповые сообщения от остальных устройств того же аккаунта.
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='message_receipts' AND column_name='device_id')""")
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE message_receipts ADD COLUMN device_id TEXT NOT NULL DEFAULT 'primary'")
            cur.execute("ALTER TABLE message_receipts DROP CONSTRAINT message_receipts_pkey")
            cur.execute("ALTER TABLE message_receipts ADD PRIMARY KEY (message_id, user_id, device_id)")
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='messages' AND column_name='recipient_device_id')""")
        if not cur.fetchone()[0]:
            # NULL = адресовано аккаунту целиком (группы, legacy-клиенты).
            cur.execute("ALTER TABLE messages ADD COLUMN recipient_device_id TEXT")
        cur.execute(
            """INSERT INTO crypto_devices (user_id, device_id, identity_key_b64, created_at)
               SELECT LOWER(user_id), 'primary', olm_identity_key, %s FROM users
               WHERE olm_identity_key IS NOT NULL
               ON CONFLICT (user_id, device_id) DO NOTHING""",
            (_utc_now(),),
        )
        # Подписанные prekey-бандлы (SEC HIGH-2): ed25519-ключ устройства,
        # подпись его identity и per-OTK подписи. NULL — легаси до апдейта клиента.
        for table, col in (("crypto_devices", "ed25519_key_b64"),
                           ("crypto_devices", "identity_sig_b64"),
                           ("crypto_devices", "master_key_b64"),
                           ("crypto_devices", "device_sig_b64"),
                           ("one_time_keys", "sig_b64")):
            cur.execute(
                """SELECT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name=%s AND column_name=%s)""", (table, col))
            if not cur.fetchone()[0]:
                cur.execute(f"ALTER TABLE {table} ADD COLUMN {col} TEXT")
        # Надгробие анти-даунгрейда: переживает удаление устройства. Без него
        # kick_device (или пере-регистрация device_id) сбрасывал бы требование
        # подписей, и следующий upload снова принимался бы неподписанным.
        cur.execute(
            """CREATE TABLE IF NOT EXISTS signed_devices (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                first_signed_at TEXT NOT NULL,
                PRIMARY KEY (user_id, device_id)
            )"""
        )
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='signed_devices' AND column_name='cross_signed')""")
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE signed_devices ADD COLUMN cross_signed INTEGER NOT NULL DEFAULT 0")

        # P10 / SEC MED-3: fallback-ключ — «последний рубеж», когда одноразовые
        # кончились. Один на устройство, переиспользуемый (forward secrecy слабее,
        # чем у OTK), зато исчерпание одноразовых больше не глушит переписку.
        cur.execute(
            """CREATE TABLE IF NOT EXISTS fallback_keys (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                key_id TEXT NOT NULL,
                key_b64 TEXT NOT NULL,
                sig_b64 TEXT,
                created_at TEXT NOT NULL,
                PRIMARY KEY (user_id, device_id)
            )"""
        )
        # Счётчик claim'ов «кто у кого» — против выжигания чужих одноразовых
        # ключей (SEC MED-3). Честному отправителю на устройство нужен один OTK;
        # сверх квоты выдаём fallback вместо расхода нового одноразового.
        cur.execute(
            """CREATE TABLE IF NOT EXISTS otk_claims (
                claimer_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                claims INTEGER NOT NULL DEFAULT 0,
                window_start TEXT NOT NULL,
                PRIMARY KEY (claimer_id, user_id, device_id)
            )"""
        )

        # P9: резервная копия истории. Сервер хранит ТОЛЬКО шифротекст
        # (AES-256-GCM ключом, выведенным из приватного ключа аккаунта) —
        # прочитать его он не может, как и конверты сообщений.
        cur.execute(
            """CREATE TABLE IF NOT EXISTS history_backups (
                seq BIGSERIAL PRIMARY KEY,
                user_id TEXT NOT NULL,
                nonce_b64 TEXT NOT NULL,
                ciphertext_b64 TEXT NOT NULL,
                created_at TEXT NOT NULL
            )"""
        )
        cur.execute(
            "CREATE INDEX IF NOT EXISTS idx_history_backups_user ON history_backups(user_id, seq)")

        # Контроль сессий: сессия привязывается к крипто-устройству (клиент
        # сообщает device_id после логина) — чтобы можно было выкинуть
        # конкретное устройство. 2FA: TOTP-секрет на аккаунт.
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='sessions' AND column_name='device_id')""")
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE sessions ADD COLUMN device_id TEXT")
        # Когда ЭТА сессия объявила себя устройством. Анти-вор гейт считает возраст
        # по ней, а не по crypto_devices.created_at: иначе угнанный токен, привязавшись
        # к старому чужому слоту, получал бы право выкидывать устройства немедленно.
        cur.execute("""SELECT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name='sessions' AND column_name='device_bound_at')""")
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE sessions ADD COLUMN device_bound_at TEXT")
        for col, ddl in [("totp_secret", "TEXT"),
                         ("totp_enabled", "INTEGER NOT NULL DEFAULT 0")]:
            cur.execute(
                """SELECT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name='users' AND column_name=%s)""", (col,))
            if not cur.fetchone()[0]:
                cur.execute(f"ALTER TABLE users ADD COLUMN {col} {ddl}")
            
        # Check username
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns 
                WHERE table_schema = current_schema() AND table_name='users' AND column_name='username'
            )
            """
        )
        has_username = cur.fetchone()[0]
        if not has_username:
            cur.execute("ALTER TABLE users ADD COLUMN username TEXT")
            cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username ON users(username)")
            cur.execute("ALTER TABLE users ADD COLUMN display_name TEXT")
            cur.execute("ALTER TABLE users ADD COLUMN avatar_data TEXT")

        # Check last_active
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns 
                WHERE table_schema = current_schema() AND table_name='users' AND column_name='last_active'
            )
            """
        )
        has_last_active = cur.fetchone()[0]
        if not has_last_active:
            cur.execute("ALTER TABLE users ADD COLUMN last_active TEXT")

        # Check avatar_file_id
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns 
                WHERE table_schema = current_schema() AND table_name='users' AND column_name='avatar_file_id'
            )
            """
        )
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE users ADD COLUMN avatar_file_id TEXT")
            
        # Check bio
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns 
                WHERE table_schema = current_schema() AND table_name='users' AND column_name='bio'
            )
            """
        )
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE users ADD COLUMN bio TEXT")

        # (#3) Check expires_at on sessions
        cur.execute(
            """
            SELECT EXISTS (
                SELECT 1 FROM information_schema.columns 
                WHERE table_schema = current_schema() AND table_name='sessions' AND column_name='expires_at'
            )
            """
        )
        has_expires_at = cur.fetchone()[0]
        if not has_expires_at:
            cur.execute("ALTER TABLE sessions ADD COLUMN expires_at TEXT")

        # (#3) Clean up expired sessions on startup
        cur.execute("DELETE FROM sessions WHERE expires_at IS NOT NULL AND expires_at < %s", (_utc_now(),))

        # (#A6) linked_group_id мог отсутствовать в БД, созданной в период,
        # когда колонка была удалена как мёртвый код
        cur.execute(
            """SELECT EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name='groups' AND column_name='linked_group_id'
            )"""
        )
        if not cur.fetchone()[0]:
            cur.execute("ALTER TABLE groups ADD COLUMN linked_group_id TEXT")

        # (#A1) Миграция: старые личные сообщения с delivered=1 считаем подтверждёнными,
        # иначе после перехода на ACK они приедут получателям повторно.
        cur.execute(
            """INSERT INTO message_receipts (message_id, user_id, device_id, acked_at)
               SELECT m.id, LOWER(m.recipient_id), 'primary', %s FROM messages m
               WHERE m.delivered = 1
               ON CONFLICT (message_id, user_id, device_id) DO NOTHING""",
            (_utc_now(),),
        )


class RegisterRequest(BaseModel):
    user_id: str = Field(min_length=2, max_length=64, pattern=r"^[A-Za-z0-9_]+$")
    public_key_b64: str = Field(min_length=16, max_length=128)
    encrypted_private_key_b64: Optional[str] = Field(default=None, max_length=100_000)
    encrypted_olm_account_b64: Optional[str] = Field(default=None, max_length=100_000)
    password: str = Field(min_length=8, max_length=256)


class LoginRequest(BaseModel):
    # Login stays compatible with pre-policy accounts; registration below is
    # stricter for all new ids/passwords.
    user_id: str = Field(min_length=2, max_length=64)
    password: str = Field(min_length=1, max_length=256)
    # 2FA: обязателен, когда на аккаунте включён TOTP.
    totp_code: Optional[str] = Field(default=None, max_length=10)


# --- TOTP (RFC 6238, sha1/30s/6 цифр — совместимо с любым аутентификатором) ---

def _totp_at(secret_b32: str, at: float, step: int = 30) -> str:
    key = base64.b32decode(secret_b32)
    msg = struct.pack(">Q", int(at // step))
    digest = hmac.new(key, msg, hashlib.sha1).digest()
    off = digest[-1] & 0x0F
    code = (int.from_bytes(digest[off:off + 4], "big") & 0x7FFFFFFF) % 1_000_000
    return f"{code:06d}"


def _totp_valid(secret_b32: str, code: Optional[str]) -> bool:
    supplied = str(code or "").strip()
    if len(supplied) != 6 or not supplied.isdigit():
        return False
    now = time.time()
    # ±1 шаг — терпимость к рассинхрону часов.
    return any(hmac.compare_digest(_totp_at(secret_b32, now + drift * 30), supplied)
               for drift in (-1, 0, 1))


class UploadKeysRequest(BaseModel):
    identity_key_b64: str = Field(min_length=16, max_length=128)
    # {key_id: key_b64}
    one_time_keys: dict[str, str] = Field(default_factory=dict, max_length=100)
    # Multi-device: старые клиенты поле не шлют и остаются устройством 'primary'.
    device_id: str = Field(default="primary", min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    # Подписанный бандл (SEC HIGH-2). Легаси-клиенты полей не шлют.
    ed25519_key_b64: Optional[str] = Field(default=None, min_length=16, max_length=128)
    identity_sig_b64: Optional[str] = Field(default=None, min_length=16, max_length=256)
    # {key_id: sig_b64}, ключи совпадают с one_time_keys.
    otk_signatures: dict[str, str] = Field(default_factory=dict, max_length=100)
    # Cross-signing (P8): мастер-ключ аккаунта и его подпись этого устройства.
    master_key_b64: Optional[str] = Field(default=None, min_length=16, max_length=128)
    device_sig_b64: Optional[str] = Field(default=None, min_length=16, max_length=256)
    # P10 / SEC MED-3: fallback-ключ {key_id, key_b64, sig_b64}. Подписан тем же
    # каноном AETHER-OTK-1, что и одноразовые — клиент проверяет его как обычный OTK.
    fallback_key: Optional[dict[str, str]] = Field(default=None)


class UpdateOlmBackupRequest(BaseModel):
    encrypted_olm_account_b64: str = Field(min_length=16, max_length=100_000)


class SendMessageRequest(BaseModel):
    sender_id: str = Field(min_length=2, max_length=64)
    recipient_id: str = Field(min_length=2, max_length=64)
    envelope: dict
    # (#A2) Идемпотентность: клиент генерирует UUID сам (паттерн random_id
    # Telegram). Повторная отправка после обрыва сети не создаёт дубликат.
    client_id: Optional[str] = Field(default=None, max_length=64)
    # Multi-device: копия для конкретного устройства получателя. None — всему
    # аккаунту (группы, legacy-отправители → устройство 'primary').
    target_device_id: Optional[str] = Field(default=None, min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")

class UpdateKeyRequest(BaseModel):
    public_key_b64: str = Field(min_length=16, max_length=128)

class UpdateProfileRequest(BaseModel):
    username: Optional[str] = Field(default=None, min_length=2, max_length=64, pattern=r"^[A-Za-z0-9_]+$")
    display_name: Optional[str] = Field(default=None, max_length=128)
    avatar_file_id: Optional[str] = Field(default=None, max_length=128)
    bio: Optional[str] = Field(default=None, max_length=2_000)
    status_emoji: Optional[str] = Field(default=None, max_length=16)

class CreateGroupRequest(BaseModel):
    id: str = Field(min_length=2, max_length=64, pattern=r"^[A-Za-z0-9_]+$")
    name: str = Field(min_length=1, max_length=128)
    description: str = Field(default="", max_length=2_000)
    is_channel: bool = False
    encrypted_key_b64: str = Field(min_length=16, max_length=10_000)
    # (#A6) Группа обсуждений канала (телеграм-паттерн «комментарии»):
    # создаётся клиентом ДО канала и подвязывается при создании
    linked_group_id: Optional[str] = Field(default=None, max_length=64)

class AddGroupMemberRequest(BaseModel):
    user_id: str = Field(min_length=2, max_length=64)
    encrypted_key_b64: str = Field(min_length=16, max_length=10_000)
    role: str = Field(default="member", pattern=r"^(member|admin)$")

# (#12) Edit group request
class UpdateGroupRequest(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=128)
    description: Optional[str] = Field(default=None, max_length=2_000)
    avatar_file_id: Optional[str] = Field(default=None, max_length=128)

@app.on_event("startup")
def startup() -> None:
    init_db()


# --- (#4) Rate-limited registration ---
# 5/min было слишком жёстко: неудачные попытки (занятое имя и т.п.) тоже считаются.
@app.post("/users/register")
@limiter.limit("15/minute;60/hour")
def register_user(body: RegisterRequest, request: Request) -> dict:
    hashed = hash_password(body.password)
    with db_conn() as cur:
        # Check if user already exists
        cur.execute("SELECT 1 FROM users WHERE LOWER(user_id) = LOWER(%s)", (body.user_id,))
        exist = cur.fetchone()
        if exist:
            raise HTTPException(400, "Username already taken")
        cur.execute(
            """INSERT INTO users
               (user_id, public_key_b64, encrypted_private_key_b64,
                encrypted_olm_account_b64, password_hash, created_at)
               VALUES (%s, %s, %s, %s, %s, %s)""",
            (body.user_id.lower(), body.public_key_b64, body.encrypted_private_key_b64,
             body.encrypted_olm_account_b64, hashed, _utc_now()),
        )
    return {"ok": True, "user_id": body.user_id.lower()}


# --- (#4) Rate-limited login, (#3) session with expiry ---
@app.post("/users/login")
@limiter.limit("15/minute;100/hour")
def login_user(body: LoginRequest, request: Request) -> dict:
    with db_conn() as cur:
        cur.execute(
            """SELECT password_hash, public_key_b64, encrypted_private_key_b64,
                      encrypted_olm_account_b64, user_id, totp_secret, totp_enabled
               FROM users WHERE LOWER(user_id) = LOWER(%s)""",
            (body.user_id,),
        )
        row = cur.fetchone()
    if not row or not row["password_hash"]:
        raise HTTPException(401, "Invalid username or password")
    if not verify_password(body.password, row["password_hash"]):
        raise HTTPException(401, "Invalid username or password")
    # 2FA: пароль верный, но без валидного кода сессия не выдаётся.
    if row["totp_enabled"] and row["totp_secret"]:
        if not body.totp_code:
            raise HTTPException(401, "totp_required")
        if not _totp_valid(row["totp_secret"], body.totp_code):
            raise HTTPException(401, "totp_invalid")
    
    # Generate session token with expiry
    token = secrets.token_hex(32)
    with db_conn() as cur:
        cur.execute(
            "INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (%s, %s, %s, %s)",
            (token, row["user_id"].lower(), _utc_now(), _session_expires())
        )
    
    return {
        "ok": True,
        "token": token,
        "user_id": row["user_id"].lower(),
        "public_key_b64": row["public_key_b64"],
        "encrypted_private_key_b64": row["encrypted_private_key_b64"],
        "encrypted_olm_account_b64": row["encrypted_olm_account_b64"],
    }


# (#A4) Полный выход: токен отзывается на сервере, а не только забывается
# клиентом, и живые WebSocket этого токена закрываются сразу.
@app.post("/logout")
async def logout(authorization: str = Header(None), current_user: str = Depends(get_current_user)) -> dict:
    token = authorization.split(" ", 1)[1].strip()
    with db_conn() as cur:
        cur.execute("DELETE FROM sessions WHERE token = %s", (token,))
    await manager.close_for_token(token)
    return {"ok": True}


# --- Контроль сессий (multi-device) ---

class BindDeviceRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    # Доказательство владения устройством: его Olm-identity лежит в шифрованной
    # базе клиента, у вора одного лишь токена его нет (см. bind_session_device).
    identity_key_b64: Optional[str] = Field(default=None, min_length=16, max_length=128)


class TotpCodeRequest(BaseModel):
    code: str = Field(min_length=6, max_length=10)


class WipeRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)


@app.put("/sessions/me/device")
def bind_session_device(body: BindDeviceRequest, authorization: str = Header(None),
                        current_user: str = Depends(get_current_user)) -> dict:
    """Клиент после логина сообщает своё крипто-устройство: сессия становится
    выкидываемой адресно (экран «Сессии»).

    identity_key_b64 — слабая проверка «не опечатка»: этот ключ ПУБЛИЧЕН (виден
    в GET /users/{id}/devices), поэтому доказательством владения быть не может.
    Настоящая защита от вора токена — в kick_device: он смотрит, как давно ИМЕННО
    ЭТА сессия привязана к устройству (device_bound_at), а не на возраст самого
    устройства, который вор наследовал бы вместе с чужим слотом."""
    token = authorization.split(" ", 1)[1].strip()
    with db_conn() as cur:
        cur.execute(
            "SELECT identity_key_b64 FROM crypto_devices WHERE user_id = LOWER(%s) AND device_id = %s",
            (current_user, body.device_id))
        row = cur.fetchone()
        if row and body.identity_key_b64 and row["identity_key_b64"] != body.identity_key_b64:
            raise HTTPException(403, "Device identity mismatch")
        # Перепривязка к ДРУГОМУ устройству перезапускает отсчёт: иначе смена
        # device_id мгновенно наследовала бы право выкидывать чужие устройства.
        cur.execute(
            """UPDATE sessions
               SET device_id = %s,
                   device_bound_at = CASE WHEN device_id IS DISTINCT FROM %s
                                          THEN %s ELSE COALESCE(device_bound_at, %s) END
               WHERE token = %s""",
            (body.device_id, body.device_id, _utc_now(), _utc_now(), token))
    return {"ok": True}


KICK_MIN_DEVICE_AGE_HOURS = 12


def _hours_since(iso_ts: Optional[str]) -> Optional[float]:
    if not iso_ts:
        return None
    try:
        return (datetime.now(timezone.utc) - datetime.fromisoformat(iso_ts)).total_seconds() / 3600
    except (ValueError, TypeError):
        return None


def _kick_age_hours(device_age: Optional[float], bind_age: Optional[float]) -> Optional[float]:
    """Возраст для анти-вор гейта: МЕНЬШИЙ из «сколько живёт устройство» и «сколько
    эта сессия к нему привязана». Публичный identity_key доказательством владения
    быть не может, а выждать 12 часов после захвата слота вор незаметно не сможет."""
    if device_age is not None and bind_age is not None:
        return min(device_age, bind_age)
    return bind_age if device_age is None else device_age


def _device_age_hours(cur, user_id: str, device_id: Optional[str]) -> Optional[float]:
    if not device_id:
        return None
    cur.execute(
        "SELECT created_at FROM crypto_devices WHERE user_id = LOWER(%s) AND device_id = %s",
        (user_id, device_id))
    row = cur.fetchone()
    if not row:
        return None
    try:
        created = datetime.fromisoformat(row["created_at"])
    except (ValueError, TypeError):
        return None
    return (datetime.now(timezone.utc) - created).total_seconds() / 3600


@app.get("/sessions/me")
def list_sessions(authorization: str = Header(None),
                  current_user: str = Depends(get_current_user)) -> dict:
    """Устройства аккаунта + их активные сессии. current — сессия запроса."""
    token = authorization.split(" ", 1)[1].strip()
    with db_conn() as cur:
        cur.execute(
            "SELECT device_id, created_at FROM crypto_devices WHERE user_id = LOWER(%s) ORDER BY created_at",
            (current_user,))
        devices = {r["device_id"]: {"device_id": r["device_id"], "device_created_at": r["created_at"],
                                    "sessions": 0, "current": False} for r in cur.fetchall()}
        cur.execute(
            "SELECT token, device_id, created_at, device_bound_at FROM sessions "
            "WHERE LOWER(user_id) = LOWER(%s)",
            (current_user,))
        unbound = 0
        my_device = None
        my_bound_at = None
        for s in cur.fetchall():
            dev = s["device_id"]
            if dev in devices:
                devices[dev]["sessions"] += 1
                if s["token"] == token:
                    devices[dev]["current"] = True
                    my_device = dev
                    my_bound_at = s.get("device_bound_at")
            else:
                unbound += 1
        # Та же формула, что в kick_device: иначе UI показывал бы активную кнопку,
        # а DELETE отвечал бы 403.
        my_age = _kick_age_hours(_device_age_hours(cur, current_user, my_device),
                                 _hours_since(my_bound_at))
    can_kick = my_age is not None and my_age >= KICK_MIN_DEVICE_AGE_HOURS
    return {"devices": list(devices.values()), "unbound_sessions": unbound,
            "can_kick": can_kick, "kick_min_hours": KICK_MIN_DEVICE_AGE_HOURS}


@app.delete("/sessions/device/{device_id}")
async def kick_device(device_id: str, authorization: str = Header(None),
                      current_user: str = Depends(get_current_user)) -> dict:
    """Выкинуть устройство: отозвать его сессии, закрыть WS, удалить его
    Olm-устройство и prekeys. Анти-вор: с недавно добавленного устройства
    (моложе 12 часов) выкидывать другие нельзя."""
    token = authorization.split(" ", 1)[1].strip()
    with db_conn() as cur:
        cur.execute("SELECT device_id, device_bound_at FROM sessions WHERE token = %s", (token,))
        me = cur.fetchone()
        my_device = me["device_id"] if me else None
        # Анти-вор: гейт применяется и к «своему» устройству. Иначе угнанный токен
        # привязывался к чужому device_id (PUT /sessions/me/device) и «выкидывал сам
        # себя», снося настоящему устройству сессии, prekeys и Olm-идентичность.
        # Штатный выход из аккаунта — POST /logout, он крипто-материал не трогает.
        #
        age = _kick_age_hours(_device_age_hours(cur, current_user, my_device),
                              _hours_since(me["device_bound_at"] if me else None))
        if age is None or age < KICK_MIN_DEVICE_AGE_HOURS:
            raise HTTPException(
                403,
                f"С нового устройства выкидывать устройства можно через {KICK_MIN_DEVICE_AGE_HOURS} ч.")
        cur.execute(
            "DELETE FROM sessions WHERE LOWER(user_id) = LOWER(%s) AND device_id = %s RETURNING token",
            (current_user, device_id))
        revoked = [r["token"] for r in cur.fetchall()]
        cur.execute(
            "DELETE FROM crypto_devices WHERE user_id = LOWER(%s) AND device_id = %s",
            (current_user, device_id))
        cur.execute(
            "DELETE FROM one_time_keys WHERE LOWER(user_id) = LOWER(%s) AND device_id = %s",
            (current_user, device_id))
        # Fallback выкинутого устройства тоже убираем: иначе claim продолжал бы
        # отдавать «последний рубеж» для ключей, которых уже нет в директории.
        cur.execute(
            "DELETE FROM fallback_keys WHERE user_id = LOWER(%s) AND device_id = %s",
            (current_user, device_id))
        if device_id == "primary":
            # Легаси-поля primary тоже гасим, иначе старый клиент продолжит claim.
            cur.execute("UPDATE users SET olm_identity_key = NULL WHERE LOWER(user_id) = LOWER(%s)",
                        (current_user,))
    for t in revoked:
        await manager.close_for_token(t)
    return {"ok": True, "revoked_sessions": len(revoked)}


# --- 2FA (TOTP) ---

@app.get("/2fa/status")
def totp_status(current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute("SELECT totp_enabled FROM users WHERE LOWER(user_id) = LOWER(%s)", (current_user,))
        row = cur.fetchone()
    return {"enabled": bool(row and row["totp_enabled"])}


@app.post("/2fa/setup")
def totp_setup(current_user: str = Depends(get_current_user)) -> dict:
    """Выдать новый секрет (2FA ещё не включена — до подтверждения кодом)."""
    secret = base64.b32encode(secrets.token_bytes(20)).decode().rstrip("=")
    with db_conn() as cur:
        cur.execute(
            "UPDATE users SET totp_secret = %s, totp_enabled = 0 WHERE LOWER(user_id) = LOWER(%s)",
            (secret, current_user))
    uri = f"otpauth://totp/AETHER:{current_user}?secret={secret}&issuer=AETHER"
    return {"secret": secret, "otpauth_uri": uri}


@app.post("/2fa/enable")
def totp_enable(body: TotpCodeRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute("SELECT totp_secret FROM users WHERE LOWER(user_id) = LOWER(%s)", (current_user,))
        row = cur.fetchone()
        if not row or not row["totp_secret"]:
            raise HTTPException(400, "Сначала запросите секрет: POST /2fa/setup")
        if not _totp_valid(row["totp_secret"], body.code):
            raise HTTPException(400, "Неверный код")
        cur.execute("UPDATE users SET totp_enabled = 1 WHERE LOWER(user_id) = LOWER(%s)", (current_user,))
    return {"ok": True}


@app.post("/2fa/disable")
def totp_disable(body: TotpCodeRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute("SELECT totp_secret, totp_enabled FROM users WHERE LOWER(user_id) = LOWER(%s)",
                    (current_user,))
        row = cur.fetchone()
        if not row or not row["totp_enabled"]:
            return {"ok": True}
        if not _totp_valid(row["totp_secret"], body.code):
            raise HTTPException(400, "Неверный код")
        cur.execute(
            "UPDATE users SET totp_enabled = 0, totp_secret = NULL WHERE LOWER(user_id) = LOWER(%s)",
            (current_user,))
    return {"ok": True}


# --- Паника: удалить всё ---

@app.post("/users/me/wipe")
async def wipe_account(body: WipeRequest, authorization: str = Header(None),
                       current_user: str = Depends(get_current_user)) -> dict:
    """«Удалить всё»: подчистить сообщения на сервере, выйти из всех групп и
    каналов, отозвать все сессии кроме текущей. Аккаунт и ключи остаются."""
    with db_conn() as cur:
        cur.execute("SELECT password_hash FROM users WHERE LOWER(user_id) = LOWER(%s)", (current_user,))
        row = cur.fetchone()
    if not row or not verify_password(body.password, row["password_hash"]):
        raise HTTPException(401, "Неверный пароль")
    token = authorization.split(" ", 1)[1].strip()
    with db_conn() as cur:
        cur.execute(
            "DELETE FROM messages WHERE LOWER(sender_id) = LOWER(%s) OR LOWER(recipient_id) = LOWER(%s)",
            (current_user, current_user))
        purged = cur.rowcount
        cur.execute("DELETE FROM group_members WHERE LOWER(user_id) = LOWER(%s)", (current_user,))
        left_groups = cur.rowcount
        # «Удалить всё» обязано забирать и резервную копию истории.
        cur.execute("DELETE FROM history_backups WHERE user_id = LOWER(%s)", (current_user,))
        # ...и счётчики claim'ов: это след «кто кому писал» в обе стороны.
        cur.execute("DELETE FROM otk_claims WHERE claimer_id = LOWER(%s) OR user_id = LOWER(%s)",
                    (current_user, current_user))
        cur.execute(
            "DELETE FROM sessions WHERE LOWER(user_id) = LOWER(%s) AND token != %s RETURNING token",
            (current_user, token))
        revoked = [r["token"] for r in cur.fetchall()]
    for t in revoked:
        await manager.close_for_token(t)
    return {"ok": True, "purged_messages": purged, "left_groups": left_groups,
            "revoked_sessions": len(revoked)}


# (#A4) Только с авторизацией: иначе перечисление пользователей без токена
@app.get("/users/{user_id}/public-key")
def get_public_key(user_id: str, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            "SELECT public_key_b64 FROM users WHERE LOWER(user_id) = LOWER(%s)", (user_id,)
        )
        row = cur.fetchone()
    if row is None:
        raise HTTPException(404, "User not found")
    return {"user_id": user_id.lower(), "public_key_b64": row["public_key_b64"]}


@app.put("/users/me/public-key")
def update_public_key(body: UpdateKeyRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute("UPDATE users SET public_key_b64 = %s WHERE LOWER(user_id) = LOWER(%s)", (body.public_key_b64, current_user))
    return {"ok": True, "user_id": current_user}


# --- Prekey-директория для Double Ratchet (Olm/X3DH) ---

MAX_ENVELOPE_BYTES = 2_000_000
# Static crypto_box envelopes are kept only as an explicit emergency migration
# switch. New direct traffic is Ratchet-only by default.
ALLOW_LEGACY_DIRECT = os.environ.get("AETHER_ALLOW_LEGACY_DIRECT", "0").lower() in {
    "1", "true", "yes",
}


def _envelope_size(envelope: dict) -> int:
    return len(json.dumps(envelope, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def _is_group_envelope(envelope: dict) -> bool:
    marker = envelope.get("is_group")
    return marker == "1" or marker is True


def _decode_b64url(value: object, field: str) -> bytes:
    import base64
    if not isinstance(value, str) or not value:
        raise HTTPException(400, f"{field} must be base64url")
    try:
        raw = base64.b64decode(
            value.replace("-", "+").replace("_", "/") + "=" * (-len(value) % 4),
            validate=True,
        )
    except Exception as exc:
        raise HTTPException(400, f"{field} must be base64url") from exc
    return raw


def _validate_key_b64(value: object, field: str) -> None:
    """Olm identity/OTK keys are exactly one Curve25519 public key (32 bytes)."""
    raw = _decode_b64url(value, field)
    if len(raw) != 32:
        raise HTTPException(400, f"{field} must encode 32 bytes")


def _validate_sig_b64(value: object, field: str) -> None:
    """Ed25519-подпись — ровно 64 байта."""
    raw = _decode_b64url(value, field)
    if len(raw) != 64:
        raise HTTPException(400, f"{field} must encode 64 bytes")


def _validated_fallback(fallback: object) -> dict:
    """Разобрать поле fallback_key: {key_id, key_b64, sig_b64}."""
    if not isinstance(fallback, dict):
        raise HTTPException(400, "fallback_key must be an object")
    key_id, key_b64 = fallback.get("key_id"), fallback.get("key_b64")
    if not isinstance(key_id, str) or not 0 < len(key_id) <= 128:
        raise HTTPException(400, "Invalid fallback key id")
    if not isinstance(key_b64, str) or len(key_b64) > 128:
        raise HTTPException(400, "Invalid fallback key")
    _validate_key_b64(key_b64, "fallback key")
    return {"key_id": key_id, "key_b64": key_b64, "sig_b64": fallback.get("sig_b64")}


def _verify_upload_signatures(user_id: str, body: "UploadKeysRequest") -> None:
    """Проверка подписей бандла (SEC HIGH-2). Канон должен побайтно совпадать
    с ядром (core/ratchet-core): AETHER-IDKEY-1 / AETHER-OTK-1.
    Сервер не доверенная сторона — это защита от битых клиентов и мусора
    в директории, настоящая проверка выполняется получателем при claim."""
    from nacl.signing import VerifyKey
    from nacl.exceptions import BadSignatureError
    vk = VerifyKey(_decode_b64url(body.ed25519_key_b64, "ed25519_key_b64"))
    ident_canon = f"AETHER-IDKEY-1|{user_id.lower()}|{body.device_id}|{body.identity_key_b64}"
    try:
        vk.verify(ident_canon.encode(), _decode_b64url(body.identity_sig_b64, "identity_sig_b64"))
    except BadSignatureError:
        raise HTTPException(400, "identity signature invalid")
    def verify_otk(key_id: str, key_b64: str, sig: object, what: str) -> None:
        if sig is None:
            raise HTTPException(400, f"{what} {key_id} lacks signature")
        _validate_sig_b64(sig, "otk signature")
        otk_canon = (f"AETHER-OTK-1|{user_id.lower()}|{body.device_id}|"
                     f"{body.identity_key_b64}|{key_id}|{key_b64}")
        try:
            vk.verify(otk_canon.encode(), _decode_b64url(sig, "otk signature"))
        except BadSignatureError:
            raise HTTPException(400, f"{what} {key_id} signature invalid")

    for key_id, key_b64 in body.one_time_keys.items():
        verify_otk(key_id, key_b64, body.otk_signatures.get(key_id), "one-time key")
    # Fallback-ключ (P10) подписан тем же каноном: получатель проверяет его той же
    # веткой, что и обычный OTK, и подмена «последнего рубежа» сервером не проходит.
    if body.fallback_key is not None:
        fb = _validated_fallback(body.fallback_key)
        verify_otk(fb["key_id"], fb["key_b64"], fb.get("sig_b64"), "fallback key")
    # Cross-signing (P8): подпись записи устройства мастер-ключом аккаунта. Именно
    # она мешает подсадить пиру фантомное устройство с самоподписанным бандлом —
    # решающая проверка снова у получателя, который пинит мастер-ключ.
    # Подписанный бандл ОБЯЗАН быть cross-signed: иначе клиент не смог бы отличить
    # «пир ещё не обновился» от «сервер вырезал мастер-поля» (стриппинг).
    if not (body.master_key_b64 and body.device_sig_b64):
        raise HTTPException(400, "Signed upload requires master_key_b64 and device_sig_b64")
    _validate_key_b64(body.master_key_b64, "master_key_b64")
    _validate_sig_b64(body.device_sig_b64, "device_sig_b64")
    master = VerifyKey(_decode_b64url(body.master_key_b64, "master_key_b64"))
    dev_canon = (f"AETHER-DEVSIG-1|{user_id.lower()}|{body.device_id}|"
                 f"{body.identity_key_b64}|{body.ed25519_key_b64}")
    try:
        master.verify(dev_canon.encode(), _decode_b64url(body.device_sig_b64, "device_sig_b64"))
    except BadSignatureError:
        raise HTTPException(400, "device signature invalid")


def _validate_ratchet_envelope(envelope: dict) -> None:
    if _envelope_size(envelope) > MAX_ENVELOPE_BYTES:
        raise HTTPException(413, "Encrypted envelope is too large")
    ratchet = envelope.get("ratchet")
    if ratchet not in ("1", 1):
        return
    if envelope.get("is_group"):
        raise HTTPException(400, "Ratchet envelope cannot be a group message")
    required = {"olm_identity", "type", "body_b64"}
    if not required.issubset(envelope):
        raise HTTPException(400, "Invalid ratchet envelope")
    _validate_key_b64(envelope["olm_identity"], "olm_identity")
    if envelope["type"] not in (0, 1):
        raise HTTPException(400, "Ratchet message type must be 0 or 1")
    body = envelope["body_b64"]
    if not isinstance(body, str) or not body or len(body) > MAX_ENVELOPE_BYTES:
        raise HTTPException(400, "Invalid ratchet body")
    if len(_decode_b64url(body, "body_b64")) > MAX_ENVELOPE_BYTES:
        raise HTTPException(413, "Encrypted body is too large")


@app.put("/keys/upload")
@limiter.limit("30/minute")
def upload_keys(body: UploadKeysRequest, request: Request,
                current_user: str = Depends(get_current_user)) -> dict:
    _validate_key_b64(body.identity_key_b64, "identity_key_b64")
    device_id = body.device_id
    signed = body.ed25519_key_b64 is not None or body.identity_sig_b64 is not None
    cross_signed = body.master_key_b64 is not None or body.device_sig_b64 is not None
    if cross_signed and not signed:
        # Мастер подписывает канон, включающий ed25519 устройства: без подписанного
        # бандла такая подпись бессмысленна и раньше принималась без проверки.
        raise HTTPException(400, "Cross-signing requires a signed bundle")
    if body.fallback_key is not None and not signed:
        # Fallback переиспользуется, поэтому неподписанный был бы идеальной точкой
        # подмены: один раз подсунул — и читаешь начало всех новых переписок.
        raise HTTPException(400, "Fallback key requires a signed bundle")
    if signed:
        # Подписанный бандл — оба поля обязательны и подписи должны сходиться.
        if not (body.ed25519_key_b64 and body.identity_sig_b64):
            raise HTTPException(400, "Signed upload requires ed25519_key_b64 and identity_sig_b64")
        _validate_key_b64(body.ed25519_key_b64, "ed25519_key_b64")
        _validate_sig_b64(body.identity_sig_b64, "identity_sig_b64")
        _verify_upload_signatures(current_user, body)   # включая подпись устройства
    with db_conn() as cur:
        cur.execute(
            "SELECT identity_key_b64, ed25519_key_b64, master_key_b64 FROM crypto_devices "
            "WHERE user_id = LOWER(%s) AND device_id = %s FOR UPDATE",
            (current_user, device_id))
        previous = cur.fetchone()
        cur.execute(
            "SELECT cross_signed FROM signed_devices WHERE user_id = LOWER(%s) AND device_id = %s",
            (current_user, device_id))
        tombstone = cur.fetchone()
        if not signed:
            # Анти-даунгрейд: раз устройство публиковало подписанные бандлы,
            # неподписанные больше не принимаем (стриппинг подписи невозможен).
            # Надгробие переживает удаление устройства.
            if (previous and previous.get("ed25519_key_b64")) or tombstone:
                raise HTTPException(400, "Signed uploads required for this device")
        if not cross_signed and ((previous and previous.get("master_key_b64"))
                                 or (tombstone and tombstone.get("cross_signed"))):
            raise HTTPException(400, "Cross-signed uploads required for this device")
        if cross_signed:
            # Все устройства аккаунта подписаны ОДНИМ мастером (он выводится из
            # приватного ключа аккаунта). Расхождение — либо баг клиента, либо
            # попытка развести директорию на два корня доверия.
            cur.execute(
                """SELECT master_key_b64 FROM crypto_devices
                   WHERE user_id = LOWER(%s) AND device_id <> %s AND master_key_b64 IS NOT NULL
                   LIMIT 1""",
                (current_user, device_id))
            other = cur.fetchone()
            if other and other["master_key_b64"] != body.master_key_b64:
                raise HTTPException(409, "Master key mismatch with existing devices")
        identity_rotated = previous is not None and (
            previous["identity_key_b64"] != body.identity_key_b64
            or (previous.get("ed25519_key_b64") or None) != body.ed25519_key_b64)
        if identity_rotated:
            # OTKs are bound to the identity that generated them. A rotated
            # account must not leave stale OTKs for the next claimant.
            cur.execute("DELETE FROM one_time_keys WHERE LOWER(user_id) = LOWER(%s) AND device_id = %s",
                        (current_user, device_id))
            # То же и для fallback: он привязан к прежнему identity, и сессия
            # на нём после ротации у отправителя не соберётся.
            cur.execute("DELETE FROM fallback_keys WHERE user_id = LOWER(%s) AND device_id = %s",
                        (current_user, device_id))
        cur.execute(
            """INSERT INTO crypto_devices (user_id, device_id, identity_key_b64,
                                           ed25519_key_b64, identity_sig_b64,
                                           master_key_b64, device_sig_b64, created_at)
               VALUES (LOWER(%s), %s, %s, %s, %s, %s, %s, %s)
               ON CONFLICT (user_id, device_id) DO UPDATE
               SET identity_key_b64 = EXCLUDED.identity_key_b64,
                   ed25519_key_b64 = EXCLUDED.ed25519_key_b64,
                   identity_sig_b64 = EXCLUDED.identity_sig_b64,
                   master_key_b64 = EXCLUDED.master_key_b64,
                   device_sig_b64 = EXCLUDED.device_sig_b64,
                   -- Смена ЛЮБОГО ключа = фактически новое устройство в этом слоте:
                   -- 12-часовой анти-вор гейт kick_device отсчитывается заново,
                   -- иначе захват чужого слота давал бы право выкидывать сразу.
                   -- ЗАПОЛНЕНИЕ ранее пустого поля сменой НЕ считается: иначе первая
                   -- же публикация после апдейта (NULL → ed25519/master) обнулила бы
                   -- возраст у всех устройств и заблокировала kick на 12 часов.
                   created_at = CASE WHEN crypto_devices.identity_key_b64
                                          IS DISTINCT FROM EXCLUDED.identity_key_b64
                                       OR (crypto_devices.ed25519_key_b64 IS NOT NULL
                                           AND crypto_devices.ed25519_key_b64
                                               IS DISTINCT FROM EXCLUDED.ed25519_key_b64)
                                       OR (crypto_devices.master_key_b64 IS NOT NULL
                                           AND crypto_devices.master_key_b64
                                               IS DISTINCT FROM EXCLUDED.master_key_b64)
                                     THEN EXCLUDED.created_at
                                     ELSE crypto_devices.created_at END""",
            (current_user, device_id, body.identity_key_b64,
             body.ed25519_key_b64, body.identity_sig_b64,
             body.master_key_b64, body.device_sig_b64, _utc_now()))
        if signed:
            cur.execute(
                """INSERT INTO signed_devices (user_id, device_id, first_signed_at, cross_signed)
                   VALUES (LOWER(%s), %s, %s, %s)
                   ON CONFLICT (user_id, device_id) DO UPDATE
                   SET cross_signed = GREATEST(signed_devices.cross_signed,
                                               EXCLUDED.cross_signed)""",
                (current_user, device_id, _utc_now(), 1 if cross_signed else 0))
        if device_id == "primary":
            # Legacy-клиенты и старый claim читают identity из users — держим в синхроне.
            cur.execute("UPDATE users SET olm_identity_key = %s WHERE LOWER(user_id) = LOWER(%s)",
                        (body.identity_key_b64, current_user))
        for key_id, key_b64 in body.one_time_keys.items():
            if len(str(key_id)) > 128 or len(key_b64) > 128:
                raise HTTPException(400, "Invalid one-time key")
            _validate_key_b64(key_b64, "one_time_key")
            cur.execute(
                "INSERT INTO one_time_keys (user_id, device_id, key_id, key_b64, sig_b64) "
                "VALUES (%s, %s, %s, %s, %s) "
                "ON CONFLICT (user_id, device_id, key_id) DO NOTHING",
                (current_user.lower(), device_id, str(key_id), str(key_b64),
                 body.otk_signatures.get(key_id) if signed else None))
        if body.fallback_key is not None:
            fb = _validated_fallback(body.fallback_key)
            # Один fallback на устройство: ротация замещает прежний. Клиент при
            # этом ещё какое-то время умеет расшифровать сообщения на старом.
            cur.execute(
                """INSERT INTO fallback_keys (user_id, device_id, key_id, key_b64, sig_b64, created_at)
                   VALUES (LOWER(%s), %s, %s, %s, %s, %s)
                   ON CONFLICT (user_id, device_id) DO UPDATE
                   SET key_id = EXCLUDED.key_id, key_b64 = EXCLUDED.key_b64,
                       sig_b64 = EXCLUDED.sig_b64, created_at = EXCLUDED.created_at""",
                (current_user, device_id, fb["key_id"], fb["key_b64"], fb["sig_b64"], _utc_now()))
    return {"ok": True}


@app.put("/users/me/olm-backup")
def update_olm_backup(body: UpdateOlmBackupRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            "UPDATE users SET encrypted_olm_account_b64 = %s WHERE LOWER(user_id) = LOWER(%s)",
            (body.encrypted_olm_account_b64, current_user),
        )
    return {"ok": True}


@app.get("/keys/count")
def keys_count(device_id: str = "primary", current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            "SELECT identity_key_b64 FROM crypto_devices WHERE user_id = LOWER(%s) AND device_id = %s",
            (current_user, device_id))
        dev = cur.fetchone()
        cur.execute(
            "SELECT COUNT(*) AS n FROM one_time_keys WHERE user_id = LOWER(%s) AND device_id = %s",
            (current_user, device_id))
        row = cur.fetchone()
    return {"count": row["n"] if row else 0,
            "identity_key_b64": dev["identity_key_b64"] if dev else None}


# --- P9: резервная копия истории (сервер видит только шифротекст) ---

MAX_BACKUP_CHUNK_BYTES = 1_000_000
MAX_BACKUP_CHUNKS = 20_000


class BackupChunkRequest(BaseModel):
    nonce_b64: str = Field(min_length=8, max_length=64)
    ciphertext_b64: str = Field(min_length=1, max_length=1_400_000)


@app.put("/backup/history")
@limiter.limit("120/minute")
def backup_upload(body: BackupChunkRequest, request: Request,
                  current_user: str = Depends(get_current_user)) -> dict:
    """Принять чанк резервной копии. Содержимое серверу непрозрачно: это
    AES-256-GCM под ключом, выведенным из приватного ключа аккаунта."""
    if len(_decode_b64url(body.ciphertext_b64, "ciphertext_b64")) > MAX_BACKUP_CHUNK_BYTES:
        raise HTTPException(413, "Backup chunk is too large")
    if len(_decode_b64url(body.nonce_b64, "nonce_b64")) != 12:
        raise HTTPException(400, "nonce_b64 must encode 12 bytes")
    with db_conn() as cur:
        cur.execute("SELECT COUNT(*) AS n FROM history_backups WHERE user_id = LOWER(%s)",
                    (current_user,))
        if (cur.fetchone()["n"] or 0) >= MAX_BACKUP_CHUNKS:
            raise HTTPException(413, "Backup quota exceeded")
        cur.execute(
            """INSERT INTO history_backups (user_id, nonce_b64, ciphertext_b64, created_at)
               VALUES (LOWER(%s), %s, %s, %s) RETURNING seq""",
            (current_user, body.nonce_b64, body.ciphertext_b64, _utc_now()))
        seq = cur.fetchone()["seq"]
    return {"ok": True, "seq": seq}


@app.get("/backup/history")
def backup_fetch(after_seq: int = 0, limit: int = 50,
                 current_user: str = Depends(get_current_user)) -> dict:
    """Отдать чанки резервной копии по возрастанию seq (для восстановления)."""
    limit = max(1, min(limit, 200))
    with db_conn() as cur:
        cur.execute(
            """SELECT seq, nonce_b64, ciphertext_b64 FROM history_backups
               WHERE user_id = LOWER(%s) AND seq > %s ORDER BY seq ASC LIMIT %s""",
            (current_user, after_seq, limit))
        rows = cur.fetchall()
    return {"chunks": [{"seq": r["seq"], "nonce_b64": r["nonce_b64"],
                        "ciphertext_b64": r["ciphertext_b64"]} for r in rows],
            "has_more": len(rows) == limit}


@app.delete("/backup/history")
def backup_delete(current_user: str = Depends(get_current_user)) -> dict:
    """Удалить резервную копию целиком — выключение тумблера в клиенте."""
    with db_conn() as cur:
        cur.execute("DELETE FROM history_backups WHERE user_id = LOWER(%s)", (current_user,))
        removed = cur.rowcount
    return {"ok": True, "removed_chunks": removed}


@app.get("/users/me/dialogs")
def my_dialogs(current_user: str = Depends(get_current_user)) -> dict:
    """1:1-диалоги по метаданным маршрутизации (сервер их и так видит).
    Новое устройство получает список чатов без переноса истории;
    содержимое переписки остаётся E2E-недоступным серверу."""
    with db_conn() as cur:
        cur.execute(
            """SELECT CASE WHEN LOWER(m.sender_id) = LOWER(%s)
                           THEN LOWER(m.recipient_id) ELSE LOWER(m.sender_id) END AS peer,
                      MAX(m.created_at) AS last_at
               FROM messages m
               WHERE (LOWER(m.sender_id) = LOWER(%s) OR LOWER(m.recipient_id) = LOWER(%s))
                 AND EXISTS (SELECT 1 FROM users u
                             WHERE LOWER(u.user_id) = CASE WHEN LOWER(m.sender_id) = LOWER(%s)
                                   THEN LOWER(m.recipient_id) ELSE LOWER(m.sender_id) END)
               GROUP BY peer
               ORDER BY last_at DESC
               LIMIT 200""",
            (current_user, current_user, current_user, current_user),
        )
        rows = cur.fetchall()
        # Группы/каналы: членство + последняя активность — раскладка чатов
        # на новом устройстве совпадает с основным.
        cur.execute(
            """SELECT LOWER(gm.group_id) AS peer,
                      COALESCE(MAX(m.created_at), '') AS last_at
               FROM group_members gm
               LEFT JOIN messages m ON LOWER(m.recipient_id) = LOWER(gm.group_id)
               WHERE LOWER(gm.user_id) = LOWER(%s)
               GROUP BY LOWER(gm.group_id)""",
            (current_user,),
        )
        group_rows = cur.fetchall()
    dialogs = [{"peer_id": r["peer"], "last_at": r["last_at"]}
               for r in rows if r["peer"] != current_user.lower()]
    dialogs += [{"peer_id": r["peer"], "last_at": r["last_at"]} for r in group_rows]
    dialogs.sort(key=lambda d: str(d["last_at"] or ""), reverse=True)
    return {"dialogs": dialogs}


@app.get("/users/{user_id}/devices")
def list_devices(user_id: str, current_user: str = Depends(get_current_user)) -> dict:
    """Все крипто-устройства пользователя: отправитель шифрует копию каждому."""
    with db_conn() as cur:
        cur.execute(
            "SELECT device_id, identity_key_b64, ed25519_key_b64, identity_sig_b64, "
            "       master_key_b64, device_sig_b64 "
            "FROM crypto_devices WHERE user_id = LOWER(%s) ORDER BY created_at",
            (user_id,))
        rows = cur.fetchall()
    return {"devices": [{"device_id": r["device_id"], "identity_key_b64": r["identity_key_b64"],
                         "ed25519_key_b64": r.get("ed25519_key_b64"),
                         "identity_sig_b64": r.get("identity_sig_b64"),
                         "master_key_b64": r.get("master_key_b64"),
                         "device_sig_b64": r.get("device_sig_b64")} for r in rows]}


# Сколько одноразовых ключей ОДИН отправитель вправе израсходовать у ОДНОГО
# устройства за окно. Честному нужен ровно один (плюс запас на переустановки и
# сброс сессии); всё сверх — выжигание чужих ключей (SEC MED-3). Сверх квоты
# выдаём fallback, не трогая запас одноразовых.
CLAIM_OTK_QUOTA = 5
CLAIM_QUOTA_WINDOW_HOURS = 24


# Забрать prekey-bundle пира: identity + ОДИН one-time key, который тут же
# удаляется (одноразовость → forward secrecy). Атомарно, чтобы двум клиентам не
# достался один и тот же OTK. Когда одноразовые кончились — отдаём fallback
# (переиспользуемый, forward secrecy слабее, но переписка не встаёт).
@app.post("/keys/claim/{user_id}")
@limiter.limit("60/minute")
def claim_keys(user_id: str, request: Request, device_id: str = "primary",
               current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # FOR UPDATE сериализует claim с upload_keys (он берёт ту же строку):
        # иначе бандл склеивался бы из двух снапшотов — старый identity + OTK,
        # подписанный уже новым ключом, что у получателя выглядит как атака.
        cur.execute(
            "SELECT identity_key_b64, ed25519_key_b64, identity_sig_b64, "
            "       master_key_b64, device_sig_b64 "
            "FROM crypto_devices WHERE user_id = LOWER(%s) AND device_id = %s FOR UPDATE",
            (user_id, device_id))
        row = cur.fetchone()
        if row is None:
            raise HTTPException(404, "No Olm identity for user")
        cur.execute(
            "SELECT key_id, key_b64, sig_b64 FROM fallback_keys "
            "WHERE user_id = LOWER(%s) AND device_id = %s",
            (user_id, device_id))
        fallback = cur.fetchone()
        # Квота считается по паре (кто claim'ит, чьё устройство): общий лимит на
        # эндпоинт от выжигания не спасает — атакующему хватает и 60 запросов
        # в минуту, чтобы за час осушить запас конкретной жертвы.
        cutoff = (datetime.now(timezone.utc)
                  - timedelta(hours=CLAIM_QUOTA_WINDOW_HOURS)).isoformat()
        # Просроченные окна этого отправителя чистим сразу: счётчики — это след
        # «кто кому писал», и хранить его дольше, чем работает квота, незачем.
        cur.execute("DELETE FROM otk_claims WHERE claimer_id = LOWER(%s) AND window_start < %s",
                    (current_user, cutoff))
        # Просроченную строку удалил шаг выше, поэтому уцелевшая заведомо внутри
        # окна — здесь достаточно инкремента, без разбора «а не истекло ли».
        cur.execute(
            """INSERT INTO otk_claims (claimer_id, user_id, device_id, claims, window_start)
               VALUES (LOWER(%s), LOWER(%s), %s, 1, %s)
               ON CONFLICT (claimer_id, user_id, device_id) DO UPDATE
               SET claims = otk_claims.claims + 1
               RETURNING claims""",
            (current_user, user_id, device_id, _utc_now()))
        claims = cur.fetchone()["claims"]
        # Сверх квоты одноразовый не расходуем — но только если есть чем заменить.
        # У устройства без fallback (клиент до P10) отказ означал бы, что ему
        # просто нельзя написать; там остаёмся на прежнем поведении.
        otk = None
        if claims <= CLAIM_OTK_QUOTA or fallback is None:
            cur.execute(
                "DELETE FROM one_time_keys WHERE ctid IN "
                "(SELECT ctid FROM one_time_keys WHERE user_id = %s AND device_id = %s LIMIT 1) "
                "RETURNING key_id, key_b64, sig_b64",
                (user_id.lower(), device_id))
            otk = cur.fetchone()
    used_fallback = otk is None
    if used_fallback:
        otk = fallback
    if otk is None:
        raise HTTPException(409, "No one-time keys available")
    return {"user_id": user_id.lower(), "device_id": device_id,
            "identity_key_b64": row["identity_key_b64"],
            "ed25519_key_b64": row.get("ed25519_key_b64"),
            "identity_sig_b64": row.get("identity_sig_b64"),
            "master_key_b64": row.get("master_key_b64"),
            "device_sig_b64": row.get("device_sig_b64"),
            # fallback=true — сигнал клиенту, что ключ переиспользуемый; проверка
            # подписи одинаковая, канон у fallback тот же AETHER-OTK-1.
            "fallback": used_fallback,
            "one_time_key": {"key_id": otk["key_id"], "key_b64": otk["key_b64"],
                             "sig_b64": otk.get("sig_b64")}}


@app.put("/users/me/profile")
def update_profile(body: UpdateProfileRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # Check if username is already taken by someone else
        if body.username:
            cur.execute("SELECT user_id FROM users WHERE LOWER(username) = LOWER(%s) AND LOWER(user_id) != LOWER(%s)", (body.username, current_user))
            exist = cur.fetchone()
            if exist:
                raise HTTPException(400, "Username already taken")
        
        cur.execute(
            "UPDATE users SET username = COALESCE(%s, username), display_name = COALESCE(%s, display_name), avatar_file_id = COALESCE(%s, avatar_file_id), bio = COALESCE(%s, bio) WHERE LOWER(user_id) = LOWER(%s)",
            (body.username, body.display_name, body.avatar_file_id, body.bio, current_user)
        )
        # Эмодзи-статус: пустая строка снимает статус (поэтому без COALESCE).
        if body.status_emoji is not None:
            cur.execute("UPDATE users SET status_emoji = %s WHERE LOWER(user_id) = LOWER(%s)",
                        (body.status_emoji or None, current_user))
    return {"ok": True}


# (#A4) Только с авторизацией: профиль (имя, био, last_active) — не публичные данные
@app.get("/users/{user_id}/profile")
def get_user_profile(user_id: str, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            "SELECT user_id, public_key_b64, username, display_name, avatar_file_id, bio, status_emoji, last_active FROM users WHERE LOWER(user_id) = LOWER(%s)", (user_id,)
        )
        row = cur.fetchone()
    if not row:
        raise HTTPException(404, "User not found")
    res_dict = dict(row)
    res_dict["user_id"] = res_dict["user_id"].lower()
    return res_dict


@app.post("/users/me/heartbeat")
def heartbeat(current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            "UPDATE users SET last_active = %s WHERE LOWER(user_id) = LOWER(%s)",
            (_utc_now(), current_user)
        )
    return {"ok": True}


# --- (#10) Search now requires auth ---
@app.get("/users/search")
def search_users(q: str, current_user: str = Depends(get_current_user)) -> dict:
    if not q or len(q) < 2:
        return {"users": []}

    # (#A6) Телеграм-семантика: "@xxx" ищет ТОЛЬКО по username,
    # обычный запрос — по имени/username/id. Точные совпадения первыми,
    # затем совпадения по началу, затем по подстроке.
    handle_only = q.startswith('@')
    if handle_only:
        q = q[1:]
        if len(q) < 2:
            return {"users": []}

    exact = q.lower()
    prefix_term = f"{q}%"
    search_term = f"%{q}%"
    with db_conn() as cur:
        if handle_only:
            cur.execute(
                """SELECT user_id, username, display_name, avatar_file_id, status_emoji FROM users
                   WHERE LOWER(COALESCE(username, '')) LIKE LOWER(%s)
                   ORDER BY (LOWER(COALESCE(username, '')) = %s) DESC,
                            (LOWER(COALESCE(username, '')) LIKE LOWER(%s)) DESC,
                            username ASC
                   LIMIT 20""",
                (search_term, exact, prefix_term)
            )
        else:
            cur.execute(
                """SELECT user_id, username, display_name, avatar_file_id, status_emoji FROM users
                   WHERE LOWER(user_id) LIKE LOWER(%s)
                      OR LOWER(COALESCE(username, '')) LIKE LOWER(%s)
                      OR LOWER(COALESCE(display_name, '')) LIKE LOWER(%s)
                   ORDER BY (LOWER(COALESCE(username, '')) = %s OR LOWER(user_id) = %s) DESC,
                            (LOWER(COALESCE(username, '')) LIKE LOWER(%s)
                             OR LOWER(COALESCE(display_name, '')) LIKE LOWER(%s)) DESC,
                            user_id ASC
                   LIMIT 20""",
                (search_term, search_term, search_term, exact, exact, prefix_term, prefix_term)
            )
        rows = cur.fetchall()
        
        # Группы/каналы: по имени и id (поиск по @handle — только пользователи)
        if handle_only:
            group_rows = []
        else:
            cur.execute(
                """SELECT id, name, description, is_channel, public_join, username, avatar_file_id FROM groups
                   WHERE LOWER(name) LIKE LOWER(%s) OR LOWER(id) LIKE LOWER(%s)
                      OR LOWER(COALESCE(username,'')) LIKE LOWER(%s) LIMIT 20""",
                (search_term, search_term, search_term)
            )
            group_rows = cur.fetchall()
        
    users = []
    for r in rows:
        rd = dict(r)
        rd["user_id"] = rd["user_id"].lower()
        users.append(rd)
        
    groups = []
    for r in group_rows:
        groups.append({
            "id": r["id"].lower(),
            "name": r["name"],
            "description": r["description"],
            "is_channel": bool(r["is_channel"]),
            "public_join": bool(r["public_join"]),
            "username": r["username"],
            "avatar_file_id": r["avatar_file_id"],
        })
        
    return {"users": users, "groups": groups}


# --- Публичные каналы: серверная выдача ключа подписчику ---

def _b64u_decode(s: str) -> bytes:
    import base64
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _b64u_encode(b: bytes) -> str:
    import base64
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _server_keypair():
    """Ключевая пара сервера для заворачивания ключей публичных каналов
    (конверт формата seal_direct из ядра: NaCl Box + b64url)."""
    from nacl.public import PrivateKey
    with db_conn() as cur:
        cur.execute("SELECT value FROM server_meta WHERE key = 'server_priv_b64'")
        row = cur.fetchone()
        if row:
            priv = PrivateKey(_b64u_decode(row["value"]))
        else:
            priv = PrivateKey.generate()
            cur.execute("INSERT INTO server_meta (key, value) VALUES ('server_priv_b64', %s)",
                        (_b64u_encode(bytes(priv)),))
    return priv


def _wrap_key_for(user_pub_b64: str, key_b64: str) -> str:
    """BoxEnvelope, совместимый с core::unwrap_group_key."""
    from nacl.public import PublicKey, Box
    import nacl.utils
    priv = _server_keypair()
    box = Box(priv, PublicKey(_b64u_decode(user_pub_b64)))
    nonce = nacl.utils.random(Box.NONCE_SIZE)
    ct = box.encrypt(key_b64.encode(), nonce).ciphertext
    return json.dumps({
        "sender_pubkey_b64": _b64u_encode(bytes(priv.public_key)),
        "nonce_b64": _b64u_encode(nonce),
        "ciphertext_b64": _b64u_encode(ct),
    })


import re as _re

PUBLIC_GROUPS_PER_OWNER = 25
GROUP_USERNAME_RE = _re.compile(r"^[a-z][a-z0-9_]{3,31}$")


class ChannelPublicRequest(BaseModel):
    public: bool
    join_key_b64: Optional[str] = None
    username: Optional[str] = None


@app.put("/groups/{group_id}/public")
def set_group_public(group_id: str, body: ChannelPublicRequest,
                     current_user: str = Depends(get_current_user)) -> dict:
    """Владелец включает/выключает публичность группы/канала (Telegram-модель):
    публичность = @username (общее пространство имён с пользователями) + ключ
    у сервера для самостоятельной подписки. Лимит на владельца — 25 публичных."""
    with db_conn() as cur:
        cur.execute("SELECT owner_id, is_channel FROM groups WHERE LOWER(id) = LOWER(%s)", (group_id,))
        g = cur.fetchone()
        if not g:
            raise HTTPException(404, "Group not found")
        if g["owner_id"].lower() != current_user.lower():
            raise HTTPException(403, "Only the owner can change visibility")
        if body.public:
            username = (body.username or "").lower().lstrip("@")
            if not GROUP_USERNAME_RE.match(username):
                raise HTTPException(400, "Username: 4–32 символа, латиница/цифры/_, начинается с буквы")
            if not body.join_key_b64 or len(_b64u_decode(body.join_key_b64)) != 32:
                raise HTTPException(400, "join_key_b64 (32 bytes, b64url) is required")
            # Имя занято? Пространство имён общее: пользователи + группы/каналы.
            cur.execute("SELECT 1 FROM users WHERE LOWER(COALESCE(username,'')) = %s", (username,))
            if cur.fetchone():
                raise HTTPException(409, "Это имя занято пользователем")
            cur.execute("SELECT 1 FROM groups WHERE LOWER(COALESCE(username,'')) = %s AND LOWER(id) != LOWER(%s)",
                        (username, group_id))
            if cur.fetchone():
                raise HTTPException(409, "Это имя уже занято")
            # Лимит публичных на владельца.
            cur.execute("""SELECT COUNT(*) AS n FROM groups
                           WHERE LOWER(owner_id) = LOWER(%s) AND public_join = 1 AND LOWER(id) != LOWER(%s)""",
                        (current_user, group_id))
            if cur.fetchone()["n"] >= PUBLIC_GROUPS_PER_OWNER:
                raise HTTPException(403,
                    f"Лимит {PUBLIC_GROUPS_PER_OWNER} публичных групп и каналов. Удали или сделай приватным что-то из существующих")
            cur.execute("""UPDATE groups SET public_join = 1, join_key_b64 = %s, username = %s
                           WHERE LOWER(id) = LOWER(%s)""",
                        (body.join_key_b64, username, group_id))
        else:
            cur.execute("""UPDATE groups SET public_join = 0, join_key_b64 = NULL, username = NULL
                           WHERE LOWER(id) = LOWER(%s)""", (group_id,))
    return {"ok": True}


@app.post("/groups/{group_id}/join")
def join_public_group(group_id: str, current_user: str = Depends(get_current_user)) -> dict:
    """Самостоятельное вступление в публичную группу/канал: сервер заворачивает
    ключ в конверт для нового участника."""
    with db_conn() as cur:
        cur.execute("""SELECT is_channel, public_join, join_key_b64 FROM groups
                       WHERE LOWER(id) = LOWER(%s)""", (group_id,))
        g = cur.fetchone()
        if not g:
            raise HTTPException(404, "Group not found")
        if not (g["public_join"] and g["join_key_b64"]):
            raise HTTPException(403, "Group is invite-only")
        cur.execute("SELECT public_key_b64 FROM users WHERE LOWER(user_id) = LOWER(%s)", (current_user,))
        u = cur.fetchone()
        if not u or not u["public_key_b64"]:
            raise HTTPException(400, "No public key on file")
        wrapped = _wrap_key_for(u["public_key_b64"], g["join_key_b64"])
        cur.execute(
            """INSERT INTO group_members (group_id, user_id, encrypted_key_b64, role)
               VALUES (%s, %s, %s, 'member')
               ON CONFLICT (group_id, user_id) DO NOTHING""",
            (group_id.lower(), current_user.lower(), wrapped),
        )
    return {"ok": True}


class PostViewsRequest(BaseModel):
    message_ids: list[str] = Field(min_length=1, max_length=200)


@app.post("/messages/views")
def post_views(body: PostViewsRequest, current_user: str = Depends(get_current_user)) -> dict:
    """Отметить просмотры постов и вернуть счётчики по этим id.
    Идемпотентно: повторный просмотр того же пользователя не считается."""
    now = _utc_now()
    with db_conn() as cur:
        cur.executemany(
            """INSERT INTO post_views (message_id, user_id, viewed_at)
               VALUES (%s, %s, %s) ON CONFLICT (message_id, user_id) DO NOTHING""",
            [(mid, current_user.lower(), now) for mid in body.message_ids],
        )
        cur.execute(
            """SELECT message_id, COUNT(*) AS n FROM post_views
               WHERE message_id = ANY(%s) GROUP BY message_id""",
            (body.message_ids,),
        )
        counts = {r["message_id"]: r["n"] for r in cur.fetchall()}
    return {"views": counts}


class RegisterDeviceRequest(BaseModel):
    token: str = Field(min_length=8, max_length=512)
    kind: str = Field(default="apns", pattern="^(apns|voip)$")


@app.post("/devices/register")
def register_device(body: RegisterDeviceRequest, current_user: str = Depends(get_current_user)) -> dict:
    """APNs-токен устройства. Токен — ключ: смена аккаунта перезаписывает владельца."""
    with db_conn() as cur:
        cur.execute(
            """INSERT INTO device_push_tokens (token, user_id, kind, updated_at)
               VALUES (%s, %s, %s, %s)
               ON CONFLICT (token) DO UPDATE
               SET user_id = EXCLUDED.user_id, kind = EXCLUDED.kind, updated_at = EXCLUDED.updated_at""",
            (body.token, current_user.lower(), body.kind, _utc_now()),
        )
    return {"ok": True, "apns_enabled": apns.configured()}


@app.post("/devices/unregister")
def unregister_device(body: RegisterDeviceRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute("DELETE FROM device_push_tokens WHERE token = %s AND user_id = %s",
                    (body.token, current_user.lower()))
    return {"ok": True}


def _drop_dead_token(token: str) -> None:
    """APNs ответил 410 — устройство снесло приложение, токен мёртв."""
    try:
        with db_conn() as cur:
            cur.execute("DELETE FROM device_push_tokens WHERE token = %s", (token,))
    except Exception:
        pass


def _apns_notify_offline(user_ids: list[str], peer_id: str) -> None:
    """Баннер-пуш тем получателям, у кого нет живого WS (приложение не в сети).
    Без текста и имён — только факт нового сообщения + peer для deep-link."""
    if not apns.configured():
        return
    offline = [u for u in user_ids if u not in manager.active_connections]
    if not offline:
        return
    with db_conn() as cur:
        cur.execute(
            "SELECT token FROM device_push_tokens WHERE kind = 'apns' AND user_id = ANY(%s)",
            (offline,),
        )
        rows = cur.fetchall()
    for row in rows:
        apns.notify_message_bg(row["token"], peer_id, on_dead=_drop_dead_token)


@app.post("/messages")
async def send_message(body: SendMessageRequest, current_user: str = Depends(get_current_user)) -> dict:
    logger.info(f"send_message: sender={body.sender_id}, recipient={body.recipient_id}, current_user={current_user}")
    logger.debug(f"send_message envelope keys: {list(body.envelope.keys())}")
    
    if body.sender_id.lower() != current_user.lower():
        logger.warning(f"send_message: sender mismatch {body.sender_id} vs {current_user}")
        raise HTTPException(403, "Cannot send messages as another user")

    # Double Ratchet (Olm) 1:1 — свой формат конверта; сервер только релеит.
    _validate_ratchet_envelope(body.envelope)
    if body.envelope.get("ratchet") in ("1", 1):
        ratchet_required = {"olm_identity", "type", "body_b64"}
        if not ratchet_required.issubset(body.envelope.keys()):
            logger.warning(f"send_message: bad ratchet envelope. Got: {list(body.envelope.keys())}")
            raise HTTPException(400, f"Invalid ratchet envelope. Got: {list(body.envelope.keys())}")
    else:
        if _envelope_size(body.envelope) > MAX_ENVELOPE_BYTES:
            raise HTTPException(413, "Encrypted envelope is too large")
        required = {"nonce_b64", "ciphertext_b64"}
        if not required.issubset(body.envelope.keys()):
            logger.warning(f"send_message: missing required keys. Got: {list(body.envelope.keys())}, need: {required}")
            raise HTTPException(400, f"Invalid envelope: missing keys. Got: {list(body.envelope.keys())}")
        if "is_group" not in body.envelope and "sender_pubkey_b64" not in body.envelope:
            logger.warning(f"send_message: missing sender_pubkey_b64. Keys: {list(body.envelope.keys())}")
            raise HTTPException(400, "Invalid envelope: missing sender_pubkey_b64")
        if _is_group_envelope(body.envelope):
            nonce = _decode_b64url(body.envelope.get("nonce_b64"), "nonce_b64")
            ciphertext = _decode_b64url(body.envelope.get("ciphertext_b64"), "ciphertext_b64")
            if len(nonce) != 12:
                raise HTTPException(400, "Invalid group nonce")
            if len(ciphertext) > MAX_ENVELOPE_BYTES:
                raise HTTPException(413, "Encrypted body is too large")
    if "plaintext" in body.envelope or "text" in body.envelope:
        raise HTTPException(400, "Plaintext not allowed on server")

    # (#A2) Идемпотентность: если клиент прислал свой UUID — используем его.
    # Повторный POST с тем же client_id не создаёт дубликат (random_id-паттерн).
    if body.client_id:
        try:
            msg_id = str(uuid.UUID(body.client_id))
        except (ValueError, AttributeError):
            raise HTTPException(400, "client_id must be a valid UUID")
    else:
        msg_id = str(uuid.uuid4())
    is_group = False
    try:
        with db_conn() as cur:
            # Check if recipient is a user or a group
            cur.execute("SELECT 1 FROM users WHERE LOWER(user_id) = LOWER(%s)", (body.recipient_id,))
            is_user = cur.fetchone()
            
            is_group = False
            if not is_user:
                cur.execute("SELECT 1 FROM groups WHERE LOWER(id) = LOWER(%s)", (body.recipient_id,))
                is_group = bool(cur.fetchone())
                
            if not is_user and not is_group:
                raise HTTPException(404, "Recipient not registered")

            is_ratchet = body.envelope.get("ratchet") in ("1", 1)
            if is_user and not is_ratchet and not ALLOW_LEGACY_DIRECT:
                raise HTTPException(400, "Direct messages require Olm Double Ratchet")
            if is_group and is_ratchet:
                raise HTTPException(400, "Group messages must use the group envelope")
            if is_group and not _is_group_envelope(body.envelope):
                raise HTTPException(400, "Invalid group envelope")
                
            # If it's a group, ensure sender is a member
            if is_group:
                cur.execute("SELECT role FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (body.recipient_id, current_user))
                member = cur.fetchone()
                if not member:
                    raise HTTPException(403, "Not a member of this group")
                
                # If it's a channel, ensure sender is an admin
                cur.execute("SELECT is_channel FROM groups WHERE LOWER(id) = LOWER(%s)", (body.recipient_id,))
                is_channel = cur.fetchone()["is_channel"]
                if is_channel and member["role"] != "admin":
                    raise HTTPException(403, "Only admins can post to a channel")
                    
            # (#A2) ON CONFLICT: повтор с тем же client_id — не ошибка, а дубликат.
            cur.execute(
                """INSERT INTO messages (id, sender_id, recipient_id, envelope_json, created_at, recipient_device_id)
                   VALUES (%s, %s, %s, %s, %s, %s)
                   ON CONFLICT (id) DO NOTHING""",
                (
                    msg_id,
                    body.sender_id.lower(),
                    body.recipient_id.lower(),
                    json.dumps(body.envelope),
                    _utc_now(),
                    body.target_device_id if is_user else None,
                ),
            )
            if cur.rowcount == 0:
                # id уже существует: либо честный ретрай отправителя (ok),
                # либо попытка занять чужой UUID (409).
                cur.execute("SELECT sender_id FROM messages WHERE id = %s", (msg_id,))
                existing = cur.fetchone()
                if not existing or existing["sender_id"].lower() != current_user.lower():
                    raise HTTPException(409, "Message id already in use")
                logger.info(f"send_message: duplicate retry for {msg_id}, treated as success")
                return {"ok": True, "message_id": msg_id, "duplicate": True}
            logger.info(f"send_message: inserted message {msg_id}")
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"send_message DB error: {type(e).__name__}: {e}", exc_info=True)
        raise HTTPException(500, f"Database error: {type(e).__name__}: {str(e)}")

    # Trigger push notification via WebSocket
    try:
        push_event = {
            "type": "new_message",
            "message_id": msg_id,
            "sender_id": body.sender_id.lower(),
            "recipient_id": body.recipient_id.lower()
        }
        
        if is_group:
            with db_conn() as cur:
                cur.execute("SELECT user_id FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) != LOWER(%s)", (body.recipient_id, current_user))
                members = cur.fetchall()
            for member in members:
                asyncio.create_task(manager.send_personal_message(push_event, member["user_id"].lower()))
            # APNs офлайн-получателям группы: deep-link ведёт в саму группу.
            _apns_notify_offline([m["user_id"].lower() for m in members], body.recipient_id.lower())
        else:
            asyncio.create_task(manager.send_personal_message(push_event, body.recipient_id.lower()))
            # Личка: deep-link — чат отправителя.
            _apns_notify_offline([body.recipient_id.lower()], body.sender_id.lower())
    except Exception as e:
        logger.warning(f"send_message: push notification failed (non-fatal): {e}")

    return {"ok": True, "message_id": msg_id}


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket, token: str):
    # Validate token
    with db_conn() as cur:
        cur.execute("SELECT user_id, expires_at FROM sessions WHERE token = %s", (token,))
        row = cur.fetchone()
    
    if not row:
        await websocket.close(code=1008)
        return

    # (#A1) Сессия с истёкшим сроком не должна держать WebSocket
    if row["expires_at"]:
        try:
            if datetime.now(timezone.utc) > datetime.fromisoformat(row["expires_at"]):
                await websocket.close(code=1008)
                return
        except (ValueError, TypeError):
            pass

    user_id = row["user_id"].lower()
    await manager.connect(websocket, user_id, token)
    try:
        while True:
            # Keep alive and signaling
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_text("pong")
                continue
                
            try:
                import json
                msg = json.loads(data)
                # Ephemeral signaling relayed verbatim to a recipient.
                # Никакого plaintext тут нет: typing/presence не содержат текста сообщений,
                # а WebRTC — это только SDP/ICE для установки p2p-соединения.
                if msg.get("type") in [
                    "webrtc_offer", "webrtc_answer", "webrtc_ice",
                    "webrtc_hangup", "webrtc_busy",
                    "typing", "stop_typing", "presence",
                ]:
                    recipient_id = msg.get("recipient_id")
                    if recipient_id:
                        # Append sender_id to the message so the recipient knows who is calling
                        msg["sender_id"] = user_id
                        asyncio.create_task(manager.send_personal_message(msg, recipient_id.lower()))
                # Групповые звонки (mesh): start/join/leave рассылаются всем
                # участникам группы, направленный SDP/ICE идёт обычными webrtc_*.
                # Медиа сервер не видит — только сигналинг, как и в 1:1.
                elif msg.get("type") in ["group_call_start", "group_call_join", "group_call_leave"]:
                    gid = (msg.get("group_id") or "").lower()
                    if gid:
                        with db_conn() as cur:
                            cur.execute(
                                """SELECT user_id FROM group_members
                                   WHERE LOWER(group_id) = LOWER(%s)""", (gid,))
                            members = [r["user_id"].lower() for r in cur.fetchall()]
                        if user_id in members:
                            msg["sender_id"] = user_id
                            for member in members:
                                if member != user_id:
                                    asyncio.create_task(manager.send_personal_message(msg, member))
            except Exception:
                pass
    except WebSocketDisconnect:
        manager.disconnect(websocket, user_id, token)


class AckMessagesRequest(BaseModel):
    message_ids: list[str] = Field(min_length=1, max_length=500)
    device_id: str = Field(default="primary", min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")


@app.post("/messages/ack")
def ack_messages(body: AckMessagesRequest, current_user: str = Depends(get_current_user)) -> dict:
    """(#A1) Клиент подтверждает: сообщения сохранены локально.
    Только после этого они исчезают из inbox. Это исключает потерю
    сообщений при краше клиента между fetch и записью в локальную БД."""
    now = _utc_now()
    with db_conn() as cur:
        cur.executemany(
            """INSERT INTO message_receipts (message_id, user_id, device_id, acked_at)
               VALUES (%s, %s, %s, %s) ON CONFLICT (message_id, user_id, device_id) DO NOTHING""",
            [(mid, current_user.lower(), body.device_id, now) for mid in body.message_ids],
        )
    return {"ok": True, "acked": len(body.message_ids)}


@app.get("/messages/inbox/{user_id}")
def inbox(user_id: str, since: str = None, device_id: str = "primary",
          current_user: str = Depends(get_current_user)) -> dict:
    if user_id.lower() != current_user.lower():
        raise HTTPException(403, "Cannot read another user's inbox")

    # Multi-device: NULL recipient_device_id = всему аккаунту (группы, legacy).
    # Копии для чужих устройств в этот inbox не попадают.
    with db_conn() as cur:
        if since:
            cur.execute(
                """SELECT m.id, m.sender_id, m.recipient_id, m.envelope_json, m.created_at
                   FROM messages m
                   WHERE (LOWER(m.recipient_id) = LOWER(%s) OR LOWER(m.recipient_id) IN (
                       SELECT LOWER(group_id) FROM group_members WHERE LOWER(user_id) = LOWER(%s)
                   )) AND (m.recipient_device_id IS NULL OR m.recipient_device_id = %s)
                   AND m.created_at > %s
                   AND NOT EXISTS (
                       SELECT 1 FROM message_receipts r
                       WHERE r.message_id = m.id AND LOWER(r.user_id) = LOWER(%s)
                             AND r.device_id = %s
                   )
                   ORDER BY m.created_at ASC
                   LIMIT 200""",
                (user_id, user_id, device_id, since, user_id, device_id),
            )
            rows = cur.fetchall()
        else:
            # (#A1) Неподтверждённые сообщения: личные + групповые/канальные.
            # Ничего не помечаем — клиент подтверждает приём через POST /messages/ack.
            cur.execute(
                """SELECT m.id, m.sender_id, m.recipient_id, m.envelope_json, m.created_at
                   FROM messages m
                   WHERE (
                       LOWER(m.recipient_id) = LOWER(%s)
                       OR (
                           LOWER(m.recipient_id) IN (
                               SELECT LOWER(group_id) FROM group_members WHERE LOWER(user_id) = LOWER(%s)
                           )
                           AND LOWER(m.sender_id) != LOWER(%s)
                       )
                   )
                   AND (m.recipient_device_id IS NULL OR m.recipient_device_id = %s)
                   AND NOT EXISTS (
                       SELECT 1 FROM message_receipts r
                       WHERE r.message_id = m.id AND LOWER(r.user_id) = LOWER(%s)
                             AND r.device_id = %s
                   )
                   ORDER BY m.created_at ASC
                   LIMIT 200""",
                (user_id, user_id, user_id, device_id, user_id, device_id),
            )
            rows = cur.fetchall()
    messages = [
        {
            "id": r["id"],
            "sender_id": r["sender_id"].lower(),
            "recipient_id": r["recipient_id"].lower(),
            "envelope": json.loads(r["envelope_json"]),
            "created_at": r["created_at"],
        }
        for r in rows
    ]
    return {"messages": messages}


# --- (#9) /users with limit, no avatar_data ---
@app.get("/users")
def get_users(current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            "SELECT user_id, username, display_name, last_active, created_at, public_key_b64 FROM users ORDER BY user_id ASC LIMIT 200"
        )
        rows = cur.fetchall()
    return {
        "users": [
            {
                "user_id": r["user_id"].lower(),
                "username": r["username"],
                "display_name": r["display_name"],
                "last_active": r["last_active"],
                "created_at": r["created_at"],
                "public_key_b64": r["public_key_b64"]
            }
            for r in rows
        ]
    }

# (#A4) API user_chat_settings удалён: клиент хранит пин/мьют/архив локально (Room).

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "e2e": "server_never_sees_plaintext"}

@app.post("/groups")
def create_group(body: CreateGroupRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # Check if ID exists (as user or group)
        cur.execute("SELECT 1 FROM users WHERE LOWER(user_id) = LOWER(%s)", (body.id,))
        if cur.fetchone():
            raise HTTPException(400, "ID already taken by a user")
        cur.execute("SELECT 1 FROM groups WHERE LOWER(id) = LOWER(%s)", (body.id,))
        if cur.fetchone():
            raise HTTPException(400, "Group ID already taken")
            
        # (#A6) Подвязка группы обсуждений: только своя группа (не канал)
        linked = None
        if body.linked_group_id and body.is_channel:
            cur.execute(
                "SELECT owner_id, is_channel FROM groups WHERE LOWER(id) = LOWER(%s)",
                (body.linked_group_id,)
            )
            lg = cur.fetchone()
            if not lg:
                raise HTTPException(404, "Linked group not found")
            if lg["owner_id"].lower() != current_user.lower():
                raise HTTPException(403, "Linked group must be owned by you")
            if lg["is_channel"]:
                raise HTTPException(400, "Linked group cannot be a channel")
            linked = body.linked_group_id.lower()

        cur.execute(
            """INSERT INTO groups (id, name, description, owner_id, is_channel, linked_group_id, created_at)
               VALUES (%s, %s, %s, %s, %s, %s, %s)""",
            (body.id.lower(), body.name, body.description, current_user, 1 if body.is_channel else 0, linked, _utc_now())
        )
        cur.execute(
            """INSERT INTO group_members (group_id, user_id, encrypted_key_b64, role)
               VALUES (%s, %s, %s, %s)""",
            (body.id.lower(), current_user, body.encrypted_key_b64, 'admin')
        )
    return {"ok": True, "group_id": body.id.lower()}

@app.post("/groups/{group_id}/members")
def add_group_member(group_id: str, body: AddGroupMemberRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # Verify permissions
        cur.execute("SELECT role FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (group_id, current_user))
        admin = cur.fetchone()
        if not admin or admin["role"] != "admin":
            raise HTTPException(403, "Only admins can add members")
            
        # Check if member exists
        cur.execute("SELECT 1 FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (group_id, body.user_id))
        if cur.fetchone():
            raise HTTPException(400, "User is already a member")
            
        cur.execute(
            """INSERT INTO group_members (group_id, user_id, encrypted_key_b64, role)
               VALUES (%s, %s, %s, %s)""",
            (group_id.lower(), body.user_id.lower(), body.encrypted_key_b64, body.role)
        )
    return {"ok": True}


# --- (#11) Remove member from group ---
@app.delete("/groups/{group_id}/members/{user_id}")
def remove_group_member(group_id: str, user_id: str, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # Verify admin permissions
        cur.execute("SELECT role FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (group_id, current_user))
        admin = cur.fetchone()
        if not admin or admin["role"] != "admin":
            raise HTTPException(403, "Only admins can remove members")
        
        # Cannot remove the owner
        cur.execute("SELECT owner_id FROM groups WHERE LOWER(id) = LOWER(%s)", (group_id,))
        group = cur.fetchone()
        if group and group["owner_id"].lower() == user_id.lower():
            raise HTTPException(400, "Cannot remove the group owner")
        
        cur.execute(
            "DELETE FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)",
            (group_id, user_id)
        )
    return {"ok": True}


# --- (#12) Edit group ---
@app.put("/groups/{group_id}")
def update_group(group_id: str, body: UpdateGroupRequest, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # Verify admin permissions
        cur.execute("SELECT role FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (group_id, current_user))
        admin = cur.fetchone()
        if not admin or admin["role"] != "admin":
            raise HTTPException(403, "Only admins can edit group")
        
        updates = []
        values = []
        if body.name is not None:
            updates.append("name = %s")
            values.append(body.name)
        if body.description is not None:
            updates.append("description = %s")
            values.append(body.description)
        if body.avatar_file_id is not None:
            updates.append("avatar_file_id = %s")
            values.append(body.avatar_file_id)

        if not updates:
            return {"ok": True}
        
        values.append(group_id)
        cur.execute(
            f"UPDATE groups SET {', '.join(updates)} WHERE LOWER(id) = LOWER(%s)",
            values
        )
    return {"ok": True}


# --- (#20) Leave group ---
@app.post("/groups/{group_id}/leave")
def leave_group(group_id: str, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # Check membership
        cur.execute("SELECT 1 FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (group_id, current_user))
        if not cur.fetchone():
            raise HTTPException(400, "Not a member of this group")
        
        # Owner cannot leave
        cur.execute("SELECT owner_id FROM groups WHERE LOWER(id) = LOWER(%s)", (group_id,))
        group = cur.fetchone()
        if group and group["owner_id"].lower() == current_user.lower():
            raise HTTPException(400, "Owner cannot leave the group. Transfer ownership or delete the group.")
        
        cur.execute(
            "DELETE FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)",
            (group_id, current_user)
        )
    return {"ok": True}


@app.get("/groups/me")
def get_my_groups(current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            """SELECT g.id, g.name, g.description, g.owner_id, g.is_channel, g.public_join, g.username, g.avatar_file_id, g.linked_group_id, g.created_at, gm.encrypted_key_b64, gm.role,
                      (SELECT COUNT(*) FROM group_members gm2 WHERE LOWER(gm2.group_id) = LOWER(g.id)) AS member_count
               FROM groups g
               JOIN group_members gm ON LOWER(g.id) = LOWER(gm.group_id)
               WHERE LOWER(gm.user_id) = LOWER(%s)""",
            (current_user,)
        )
        rows = cur.fetchall()
        groups = []
        for r in rows:
            groups.append({
                "id": r["id"],
                "name": r["name"],
                "description": r["description"],
                "owner_id": r["owner_id"],
                "is_channel": bool(r["is_channel"]),
                "public_join": bool(r["public_join"]),
                "username": r["username"],
                "avatar_file_id": r["avatar_file_id"],
                "linked_group_id": r["linked_group_id"],
                "created_at": r["created_at"],
                "encrypted_key_b64": r["encrypted_key_b64"],
                "role": r["role"],
                "member_count": r["member_count"]
            })
    return {"groups": groups}

@app.get("/groups/{group_id}/members")
def get_group_members(group_id: str, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        # Check membership
        cur.execute("SELECT 1 FROM group_members WHERE LOWER(group_id) = LOWER(%s) AND LOWER(user_id) = LOWER(%s)", (group_id, current_user))
        if not cur.fetchone():
            raise HTTPException(403, "Not a member of this group")
            
        cur.execute(
            """SELECT u.user_id, u.username, u.display_name, u.avatar_file_id, gm.role
               FROM group_members gm
               JOIN users u ON LOWER(gm.user_id) = LOWER(u.user_id)
               WHERE LOWER(gm.group_id) = LOWER(%s)""",
            (group_id,)
        )
        rows = cur.fetchall()
        members = []
        for r in rows:
            members.append({
                "user_id": r["user_id"],
                "username": r["username"],
                "display_name": r["display_name"],
                "avatar_file_id": r["avatar_file_id"],
                "role": r["role"]
            })
    return {"members": members, "count": len(members)}


@app.delete("/groups/{group_id}")
def delete_group(group_id: str, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute("SELECT owner_id FROM groups WHERE LOWER(id) = LOWER(%s)", (group_id,))
        row = cur.fetchone()
        if not row:
            raise HTTPException(404, "Group not found")
        if row["owner_id"].lower() != current_user.lower():
            raise HTTPException(403, "Only the owner can delete the group")
            
        cur.execute("DELETE FROM messages WHERE LOWER(recipient_id) = LOWER(%s)", (group_id,))
        cur.execute("DELETE FROM group_members WHERE LOWER(group_id) = LOWER(%s)", (group_id,))
        cur.execute("DELETE FROM groups WHERE LOWER(id) = LOWER(%s)", (group_id,))
    return {"ok": True}

@app.delete("/messages/history/{peer_id}")
def delete_history(peer_id: str, current_user: str = Depends(get_current_user)) -> dict:
    with db_conn() as cur:
        cur.execute(
            """DELETE FROM messages 
               WHERE (LOWER(sender_id) = LOWER(%s) AND LOWER(recipient_id) = LOWER(%s))
                  OR (LOWER(sender_id) = LOWER(%s) AND LOWER(recipient_id) = LOWER(%s))""",
            (current_user, peer_id, peer_id, current_user)
        )
    return {"ok": True}


# Mount the web client static files at the root
UPLOAD_DIR = Path(__file__).resolve().parent / "uploads"
UPLOAD_DIR.mkdir(exist_ok=True)

# ПУБЛИЧНЫЙ неймспейс аватарок: файлы здесь отдаются БЕЗ авторизации и не
# шифруются (аватар виден в поиске/профиле любому пользователю). Не класть
# сюда ничего, кроме аватарок. Приватные медиа — только через /upload+/download.
AVATAR_DIR = UPLOAD_DIR / "avatars"
AVATAR_DIR.mkdir(parents=True, exist_ok=True)

# (#P1.6) Лимиты размера загрузки
MAX_UPLOAD_BYTES = 50 * 1024 * 1024   # 50 МБ — зашифрованные медиа
MAX_AVATAR_BYTES = 5 * 1024 * 1024    # 5 МБ — аватарки

import re as _re
_FILE_ID_RE = _re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


async def _save_upload(file: UploadFile, dest_dir: Path, max_bytes: int) -> str:
    """Стримит файл на диск, обрывая запись при превышении лимита."""
    file_id = str(uuid.uuid4())
    file_path = dest_dir / file_id
    total = 0
    try:
        with open(file_path, "wb") as f:
            while chunk := await file.read(1024 * 1024):
                total += len(chunk)
                if total > max_bytes:
                    raise HTTPException(413, f"File too large (max {max_bytes // (1024 * 1024)} MB)")
                f.write(chunk)
    except Exception:
        file_path.unlink(missing_ok=True)
        raise
    return file_id


@app.post("/upload")
@limiter.limit("20/minute")
async def upload_file(request: Request, file: UploadFile = File(...), current_user: str = Depends(get_current_user)) -> dict:
    file_id = await _save_upload(file, UPLOAD_DIR, MAX_UPLOAD_BYTES)
    return {"ok": True, "file_id": file_id}


# (#P1.4) Скачивание зашифрованных медиа — только с авторизацией
@app.get("/download/{file_id}")
@limiter.limit("100/minute")
def download_file(request: Request, file_id: str, current_user: str = Depends(get_current_user)) -> FileResponse:
    if not _FILE_ID_RE.match(file_id):
        raise HTTPException(404, "File not found")
    file_path = UPLOAD_DIR / file_id
    if not file_path.exists() or not file_path.is_file():
        raise HTTPException(404, "File not found")
    return FileResponse(file_path)


# (#P1.5) Аватарки: загрузка с авторизацией, отдача публичная (см. AVATAR_DIR)
@app.post("/avatars")
@limiter.limit("10/minute")
async def upload_avatar(request: Request, file: UploadFile = File(...), current_user: str = Depends(get_current_user)) -> dict:
    file_id = await _save_upload(file, AVATAR_DIR, MAX_AVATAR_BYTES)
    return {"ok": True, "file_id": file_id}


def _image_mime(path) -> str:
    # nosniff запрещает браузеру угадывать тип: octet-stream не отрисуется
    # как картинка. Определяем тип по сигнатуре файла сами.
    with open(path, "rb") as f:
        head = f.read(12)
    if head.startswith(b"\x89PNG"):
        return "image/png"
    if head.startswith(b"\xff\xd8"):
        return "image/jpeg"
    if head[:4] == b"GIF8":
        return "image/gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    return "application/octet-stream"


@app.get("/avatars/{file_id}")
@limiter.limit("200/minute")
def download_avatar(request: Request, file_id: str) -> FileResponse:
    if not _FILE_ID_RE.match(file_id):
        raise HTTPException(404, "File not found")
    file_path = AVATAR_DIR / file_id
    if not file_path.exists() or not file_path.is_file():
        raise HTTPException(404, "File not found")
    return FileResponse(file_path, media_type=_image_mime(file_path))

# (#A4) Удалены неиспользуемые API: магазин (items/buy/my-items) и
# /groups/{id}/link (заглушка «комментариев») — клиент их никогда не звал.

app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")
