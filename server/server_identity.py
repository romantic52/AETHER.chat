"""Идентичность инстанса Aether-сервера.

Каждый сервер (официальный или самостоятельно поднятый) обязан уметь назвать
себя: постоянный server_id, имя, политика регистрации и ключ, которым он
подписывает свой /server/info. Клиент запоминает эту пару при первом
подключении (TOFU) и поднимает тревогу, если она изменилась — см.
docs/MULTI_SERVER_DESIGN.md, раздел 16.

Ключ подписи — Ed25519 (PyNaCl, уже в зависимостях). Существующий
`server_priv_b64` из server_meta НЕ переиспользуется: он X25519 и служит для
заворачивания ключей публичных каналов. Смешивать ключ подписи и ключ
шифрования нельзя даже когда алгоритмы родственные.

Всё лениво импортирует main, чтобы не образовалось циклической зависимости:
к моменту первого запроса модуль main уже загружен целиком.
"""

from __future__ import annotations

import base64
import uuid
from typing import Optional

PROTOCOL_VERSION = 1
SOFTWARE_NAME = "aether-server"
SOFTWARE_VERSION = "0.3.0"

# Ключи в существующей таблице server_meta (key TEXT PRIMARY KEY, value TEXT).
K_SERVER_ID = "aether.server_id"
K_NAME = "aether.server_name"
K_ED_PRIV = "aether.ed25519_priv_b64"
K_ED_PUB = "aether.ed25519_pub_b64"
K_REG_MODE = "aether.registration_mode"
K_IMPORT_ENABLED = "aether.data_import_enabled"
K_IMPORT_QUOTA = "aether.import_quota_bytes"
K_REQUEST_TTL = "aether.request_ttl_days"
K_OFFICIAL = "aether.official"
K_IP_SALT = "aether.ip_salt"

REGISTRATION_MODES = ("OPEN", "APPROVAL", "INVITE_ONLY", "CLOSED")

DEFAULTS = {
    K_NAME: "Aether Server",
    K_IMPORT_ENABLED: "1",
    K_IMPORT_QUOTA: str(2 * 1024 * 1024 * 1024),   # 2 ГБ на аккаунт
    K_REQUEST_TTL: "14",
    K_OFFICIAL: "0",
}


def _db():
    try:
        from server.main import db_conn
    except ImportError:
        from main import db_conn
    return db_conn


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64u_decode(s: str) -> bytes:
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s.replace("-", "+").replace("_", "/") + pad)


def meta_get(key: str) -> Optional[str]:
    with _db()() as cur:
        cur.execute("SELECT value FROM server_meta WHERE key = %s", (key,))
        row = cur.fetchone()
    return row["value"] if row else None


def meta_set(key: str, value: str) -> None:
    with _db()() as cur:
        cur.execute(
            """INSERT INTO server_meta (key, value) VALUES (%s, %s)
               ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value""",
            (key, value),
        )


def _meta_set_once(cur, key: str, value: str) -> None:
    """Записать, только если ключа ещё нет. Для server_id это критично:
    перегенерация = все клиенты увидят подмену сервера."""
    cur.execute(
        "INSERT INTO server_meta (key, value) VALUES (%s, %s) ON CONFLICT (key) DO NOTHING",
        (key, value),
    )


def ensure_identity() -> None:
    """Идемпотентная инициализация. Зовётся из init_db() при каждом старте."""
    from nacl.signing import SigningKey

    with _db()() as cur:
        _meta_set_once(cur, K_SERVER_ID, str(uuid.uuid4()))

        cur.execute("SELECT value FROM server_meta WHERE key = %s", (K_ED_PRIV,))
        if not cur.fetchone():
            sk = SigningKey.generate()
            _meta_set_once(cur, K_ED_PRIV, b64u(bytes(sk)))
            _meta_set_once(cur, K_ED_PUB, b64u(bytes(sk.verify_key)))

        # Режим регистрации по умолчанию зависит от того, живой это инстанс или
        # свежий. У работающего сервера менять поведение молча нельзя — он
        # остаётся OPEN. Новый самохост стартует в APPROVAL: иначе окно между
        # первым запуском и назначением владельца открыто всему интернету.
        cur.execute("SELECT value FROM server_meta WHERE key = %s", (K_REG_MODE,))
        if not cur.fetchone():
            cur.execute("SELECT EXISTS (SELECT 1 FROM users)")
            has_users = bool(cur.fetchone()[0])
            _meta_set_once(cur, K_REG_MODE, "OPEN" if has_users else "APPROVAL")

        # Соль для хеширования IP в аудите и анти-абьюзе. IP в открытом виде
        # не хранится нигде: мессенджер, который не собирает даже почту, не
        # должен копить адреса своих пользователей.
        import secrets
        _meta_set_once(cur, K_IP_SALT, secrets.token_hex(16))

        for key, value in DEFAULTS.items():
            _meta_set_once(cur, key, value)


def server_id() -> str:
    value = meta_get(K_SERVER_ID)
    if not value:                      # первый запрос до init_db (не должно случаться)
        ensure_identity()
        value = meta_get(K_SERVER_ID)
    return value


def signing_key():
    from nacl.signing import SigningKey
    priv = meta_get(K_ED_PRIV)
    if not priv:
        ensure_identity()
        priv = meta_get(K_ED_PRIV)
    return SigningKey(b64u_decode(priv))


def public_key_b64() -> str:
    pub = meta_get(K_ED_PUB)
    if not pub:
        ensure_identity()
        pub = meta_get(K_ED_PUB)
    return pub


def registration_mode() -> str:
    mode = (meta_get(K_REG_MODE) or "APPROVAL").upper()
    return mode if mode in REGISTRATION_MODES else "CLOSED"


def canonical_info(server_id_: str, name: str, api_url: str, ws_url: str,
                   mode: str, signed_at: str, nonce: str) -> bytes:
    """Каноническая строка подписи AETHER-SERVER-INFO-1.

    Порядок полей и разделитель фиксированы: любой клиент на любой платформе
    обязан собрать ровно эти байты, иначе подпись не сойдётся.
    """
    parts = [
        "AETHER-SERVER-INFO-1",
        server_id_,
        name,
        api_url,
        ws_url,
        mode.lower(),
        str(PROTOCOL_VERSION),
        signed_at,
        nonce,
    ]
    return "\n".join(parts).encode("utf-8")
