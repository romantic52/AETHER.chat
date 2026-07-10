"""APNs-отправка (HTTP/2 + JWT ES256).

Конфиг через окружение — на self-hosted сервере пуши опциональны:
  APNS_KEY_FILE  — путь к .p8 ключу из Apple Developer (Keys → APNs)
  APNS_KEY_ID    — Key ID ключа
  APNS_TEAM_ID   — Team ID аккаунта разработчика
  APNS_TOPIC     — bundle id приложения (chat.aether.app)
  APNS_ENV       — sandbox | production (по умолчанию sandbox)

Не настроено — send() тихо no-op: сервер работает без пушей (WS-доставка остаётся).
Приватность: в payload НИКОГДА нет текста сообщений — только generic-баннер
и peer id для deep-link (шифрованный контент дотягивает сам клиент).
"""
import os
import time
import logging
import asyncio

logger = logging.getLogger("apns")

_KEY_FILE = os.environ.get("APNS_KEY_FILE", "")
_KEY_ID = os.environ.get("APNS_KEY_ID", "")
_TEAM_ID = os.environ.get("APNS_TEAM_ID", "")
_TOPIC = os.environ.get("APNS_TOPIC", "com.rmkhc.aether")
_HOST = ("https://api.sandbox.push.apple.com"
         if os.environ.get("APNS_ENV", "sandbox") != "production"
         else "https://api.push.apple.com")

_jwt_cache = {"token": None, "issued": 0.0}
_client = None  # общий httpx.AsyncClient c HTTP/2


def configured() -> bool:
    return bool(_KEY_FILE and _KEY_ID and _TEAM_ID and os.path.exists(_KEY_FILE))


def _auth_jwt() -> str:
    # Apple требует обновлять JWT не чаще раза в 20 минут и не реже часа.
    now = time.time()
    if _jwt_cache["token"] and now - _jwt_cache["issued"] < 45 * 60:
        return _jwt_cache["token"]
    import jwt  # PyJWT + cryptography
    with open(_KEY_FILE) as f:
        secret = f.read()
    token = jwt.encode(
        {"iss": _TEAM_ID, "iat": int(now)},
        secret, algorithm="ES256", headers={"kid": _KEY_ID},
    )
    _jwt_cache.update(token=token, issued=now)
    return token


async def _http():
    global _client
    if _client is None:
        import httpx
        _client = httpx.AsyncClient(http2=True, timeout=10)
    return _client


async def send(device_token: str, payload: dict, *,
               push_type: str = "alert", voip: bool = False) -> bool:
    """Отправить пуш. Возвращает False при 410 (токен мёртв — удалить из БД)."""
    if not configured():
        return True
    topic = _TOPIC + ".voip" if voip else _TOPIC
    headers = {
        "authorization": f"bearer {_auth_jwt()}",
        "apns-topic": topic,
        "apns-push-type": "voip" if voip else push_type,
        "apns-priority": "10",
        "apns-expiration": str(int(time.time()) + 3600),
    }
    try:
        client = await _http()
        r = await client.post(f"{_HOST}/3/device/{device_token}",
                              json=payload, headers=headers)
        if r.status_code == 200:
            return True
        if r.status_code == 410:
            logger.info("apns: token gone (410), dropping")
            return False
        logger.warning("apns: %s %s", r.status_code, r.text[:200])
        return True
    except Exception as e:  # сеть/TLS — не роняем доставку сообщений
        logger.warning("apns send failed: %s", e)
        return True


async def notify_message(device_token: str, peer_id: str) -> bool:
    """Generic-баннер о новом сообщении (без текста и имён — приватность)."""
    return await send(device_token, {
        "aps": {
            "alert": {"title": "Æther", "body": "Новое сообщение"},
            "sound": "default",
            "mutable-content": 1,
        },
        "peer": peer_id,
    })


def notify_message_bg(device_token: str, peer_id: str, on_dead=None):
    """Fire-and-forget из sync-контекста; on_dead(token) — колбэк на 410."""
    async def run():
        ok = await notify_message(device_token, peer_id)
        if not ok and on_dead:
            on_dead(device_token)
    asyncio.create_task(run())
