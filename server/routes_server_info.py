"""Обнаружение сервера: /server/info и /.well-known/aether.

Единственные маршруты Aether, доступные без авторизации и без аккаунта.
Клиент по ним понимает: это вообще Aether? какой версии протокол? пускают ли
сюда новых людей? тот ли это сервер, что был вчера?

Ответ подписан ключом сервера (Ed25519). Подпись даёт целостность документа,
а доверие даёт TOFU-пин на клиенте — см. docs/MULTI_SERVER_DESIGN.md, 16.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Query, Request

try:
    from server import server_identity as ident
except ImportError:
    import server_identity as ident

router = APIRouter()

CAPABILITIES = [
    "e2ee",
    "ratchet",
    "groups",
    "channels",
    "calls",
    "multi_device",
    "invites",
    "registration_requests",
    "data_import",
]


def _public_base(request: Request) -> str:
    """Внешний адрес сервера.

    Приоритет у переменной окружения: Host из запроса подконтролен клиенту, и
    подписывать его как «мой адрес» — значит подписывать чужую строку. Когда
    переменной нет (типовой самохост за Caddy), берём Host, но клиент обязан
    сверить, что домен совпадает с тем, куда он сам постучался.
    """
    import os
    configured = os.environ.get("AETHER_PUBLIC_URL", "").strip().rstrip("/")
    if configured:
        return configured
    scheme = request.headers.get("x-forwarded-proto") or request.url.scheme
    host = request.headers.get("x-forwarded-host") or request.headers.get("host") or request.url.netloc
    return f"{scheme}://{host}"


def build_info(request: Request, nonce: str = "") -> dict:
    base = _public_base(request)
    ws_base = "wss://" + base.split("://", 1)[1] if base.startswith("https://") \
        else "ws://" + base.split("://", 1)[1]

    server_id = ident.server_id()
    name = ident.meta_get(ident.K_NAME) or "Aether Server"
    api_url = f"{base}/api/v1"
    ws_url = f"{ws_base}/ws"
    mode = ident.registration_mode()
    signed_at = datetime.now(timezone.utc).isoformat()

    message = ident.canonical_info(server_id, name, api_url, ws_url, mode, signed_at, nonce)
    signature = ident.signing_key().sign(message).signature

    import_enabled = (ident.meta_get(ident.K_IMPORT_ENABLED) or "1") == "1"
    caps = [c for c in CAPABILITIES if c != "data_import" or import_enabled]

    return {
        "protocol": "aether",
        "protocol_version": ident.PROTOCOL_VERSION,
        "server_id": server_id,
        "name": name,
        "api_url": api_url,
        "websocket_url": ws_url,
        "registration_mode": mode.lower(),
        "supports_data_import": import_enabled,
        "supports_e2ee": True,
        "capabilities": caps,
        "max_upload_bytes": _max_upload(),
        "official": (ident.meta_get(ident.K_OFFICIAL) or "0") == "1",
        "software": {"name": ident.SOFTWARE_NAME, "version": ident.SOFTWARE_VERSION},
        "public_key_b64": ident.public_key_b64(),
        "signed_at": signed_at,
        "nonce": nonce,
        "signature_b64": ident.b64u(signature),
    }


def _max_upload() -> int:
    try:
        from server.main import MAX_UPLOAD_BYTES
    except ImportError:
        from main import MAX_UPLOAD_BYTES
    return MAX_UPLOAD_BYTES


@router.get("/server/info")
def server_info(request: Request,
                nonce: Optional[str] = Query(default=None, max_length=64)) -> dict:
    return build_info(request, (nonce or "").strip())
