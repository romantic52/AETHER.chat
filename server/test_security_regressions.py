#!/usr/bin/env python3
"""Регрессии по итогам аудита безопасности сервера.

Каждый тест ловит конкретный, ранее существовавший дефект — если правку
откатят, тест упадёт. Запуск такой же, как у server/test_prekeys.py:

    AETHER_URL=http://127.0.0.1:8099 python3 server/test_security_regressions.py

Тест создаёт аккаунты и пишет сообщения, поэтому адрес обязателен: прод по
умолчанию не берём. Нужен PyNaCl (сервер и так его требует).

Покрытие:
  2FA-DOWNGRADE-001  POST /2fa/setup не снимает уже включённую 2FA.
  DEVICE-ACK-001     ACK нельзя выдать от имени чужого устройства.
  DEVICE-INBOX-002   inbox нельзя вычитать за чужое устройство.
  DEVICE-TARGET-003  сообщение несуществующему устройству получателя отвергается.
  IDEMPOTENCY-001    тот же client_id с другим содержимым → 409, а не «ok».
  GROUP-MEMBER-001   в группу нельзя добавить несуществующего пользователя.
  KEYSHAPE-001       публичный ключ аккаунта обязан быть 32 байта.
  WS-TICKET-001      билет на WebSocket одноразовый.
"""
import base64
import json
import os
import secrets
import urllib.error
import urllib.request
import uuid

from nacl.signing import SigningKey

BASE = os.environ.get("AETHER_URL", "").rstrip("/")
if not BASE:
    raise SystemExit("Задайте AETHER_URL (например http://127.0.0.1:8099).")


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _once(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data, timeout=15) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def call(method, path, token=None, body=None):
    """429 здесь — не результат теста, а его помеха: набор создаёт с десяток
    аккаунтов, а /users/register и /users/login ограничены 15 запросами в
    минуту с адреса. Пережидаем окно и повторяем."""
    for attempt in range(3):
        status, data = _once(method, path, token, body)
        if status != 429:
            return status, data
        import time
        wait = 61 if attempt == 0 else 31
        print(f"  429 на {path}: ждём {wait} с (ограничение частоты, не дефект)")
        time.sleep(wait)
    return status, data


def register(prefix, public_key_b64=None):
    user = f"{prefix}_{secrets.token_hex(4)}"
    password = "Regress-test-" + secrets.token_hex(4)
    # Сравнение с None, а не `or`: пустая строка — законный кейс KEYSHAPE-001,
    # и подменять её валидным ключом значит проверять не то.
    if public_key_b64 is None:
        key = b64(secrets.token_bytes(32))
    else:
        key = public_key_b64
    status, data = call("POST", "/users/register", body={
        "user_id": user,
        "public_key_b64": key,
        "encrypted_private_key_b64": "salt:iv:ct",
        "password": password,
    })
    if public_key_b64 is not None:
        return user, password, status, data
    assert status in (200, 201), f"register: {status} {data}"
    return user, password, status, data


def login(user, password):
    status, data = call("POST", "/users/login", body={"user_id": user, "password": password})
    assert status == 200, f"login: {status} {data}"
    return data["token"]


def publish_device(user, token, device):
    """Опубликовать подписанный + cross-signed бандл: без него у устройства
    нет записи в crypto_devices, а именно её проверяет DEVICE-TARGET-003."""
    master = SigningKey.generate()
    device_key = SigningKey.generate()
    ed_b64 = b64(bytes(device_key.verify_key))
    identity_b64 = b64(secrets.token_bytes(32))
    key_id, key_b64 = secrets.token_hex(4), b64(secrets.token_bytes(32))
    body = {
        "identity_key_b64": identity_b64,
        "ed25519_key_b64": ed_b64,
        "identity_sig_b64": b64(device_key.sign(
            f"AETHER-IDKEY-1|{user.lower()}|{device}|{identity_b64}".encode()).signature),
        "one_time_keys": {key_id: key_b64},
        "otk_signatures": {key_id: b64(device_key.sign(
            f"AETHER-OTK-1|{user.lower()}|{device}|{identity_b64}|{key_id}|{key_b64}"
            .encode()).signature)},
        "device_id": device,
        "master_key_b64": b64(bytes(master.verify_key)),
        "device_sig_b64": b64(master.sign(
            f"AETHER-DEVSIG-1|{user.lower()}|{device}|{identity_b64}|{ed_b64}".encode()).signature),
    }
    status, data = call("PUT", "/keys/upload", token, body)
    assert status == 200, f"keys/upload {device}: {status} {data}"
    return master


def ratchet_envelope(marker: str) -> dict:
    """Минимальный валидный Ratchet-конверт. Содержимое сервер не читает —
    ему важны только форма и то, что это не plaintext."""
    return {
        "ratchet": "1",
        "olm_identity": b64(secrets.token_bytes(32)),
        "type": 0,
        "body_b64": b64(marker.encode() + secrets.token_bytes(16)),
    }


def totp_at(secret_b32: str) -> str:
    import hashlib
    import hmac
    import struct
    import time
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    msg = struct.pack(">Q", int(time.time() // 30))
    digest = hmac.new(key, msg, hashlib.sha1).digest()
    off = digest[-1] & 0x0F
    return f"{(int.from_bytes(digest[off:off + 4], 'big') & 0x7FFFFFFF) % 1_000_000:06d}"


def test_2fa_downgrade():
    """2FA-DOWNGRADE-001: сессия без текущего кода не снимает включённую 2FA."""
    user, password, _, _ = register("reg2fa")
    token = login(user, password)

    status, data = call("POST", "/2fa/setup", token, {})
    assert status == 200, f"setup: {status} {data}"
    secret = data["secret"]
    status, _ = call("POST", "/2fa/enable", token, {"code": totp_at(secret)})
    assert status == 200, "enable"
    status, data = call("GET", "/2fa/status", token)
    assert data["enabled"] is True, "2FA должна быть включена"

    # Ключевая проверка: повторный setup без текущего кода обязан быть отвергнут.
    status, _ = call("POST", "/2fa/setup", token, {})
    assert status == 403, f"setup без TOTP при включённой 2FA: {status}, ожидался 403"
    status, data = call("GET", "/2fa/status", token)
    assert data["enabled"] is True, "2FA снялась без подтверждения — downgrade"

    # Вход всё ещё требует код от СТАРОГО секрета.
    status, _ = call("POST", "/users/login", body={"user_id": user, "password": password})
    assert status == 401, f"вход без TOTP: {status}"
    status, _ = call("POST", "/users/login",
                     body={"user_id": user, "password": password, "totp_code": totp_at(secret)})
    assert status == 200, "старый фактор перестал работать"

    # Замена фактора с текущим кодом разрешена, но до подтверждения активен старый.
    status, data = call("POST", "/2fa/setup", token, {"code": totp_at(secret)})
    assert status == 200, f"setup с текущим кодом: {status} {data}"
    new_secret = data["secret"]
    status, _ = call("POST", "/users/login",
                     body={"user_id": user, "password": password, "totp_code": totp_at(secret)})
    assert status == 200, "старый фактор должен работать до подтверждения нового"
    status, _ = call("POST", "/2fa/enable", token, {"code": totp_at(new_secret)})
    assert status == 200, "подтверждение нового секрета"
    status, _ = call("POST", "/users/login",
                     body={"user_id": user, "password": password, "totp_code": totp_at(new_secret)})
    assert status == 200, "новый фактор не заработал"
    print("2FA-DOWNGRADE-001: ok")


def test_device_scoping():
    """DEVICE-ACK-001 / DEVICE-INBOX-002: device_id берётся из сессии."""
    user, password, _, _ = register("regdev")
    token_a1 = login(user, password)
    token_a2 = login(user, password)
    publish_device(user, token_a1, "dev1")

    # Первое обращение пиннит сессию к устройству.
    status, _ = call("GET", f"/messages/inbox/{user}?device_id=dev1", token_a1)
    assert status == 200, f"inbox dev1: {status}"

    status, _ = call("GET", f"/messages/inbox/{user}?device_id=dev2", token_a1)
    assert status == 403, f"inbox за чужое устройство: {status}, ожидался 403"

    status, _ = call("POST", "/messages/ack", token_a1,
                     {"message_ids": [str(uuid.uuid4())], "device_id": "dev2"})
    assert status == 403, f"ACK за чужое устройство: {status}, ожидался 403"

    # Другая сессия того же аккаунта свободно пиннится к своему устройству.
    status, _ = call("GET", f"/messages/inbox/{user}?device_id=dev2", token_a2)
    assert status == 200, f"вторая сессия, своё устройство: {status}"
    print("DEVICE-ACK-001 / DEVICE-INBOX-002: ok")


def test_target_device_validation():
    """DEVICE-TARGET-003: конверт для несуществующего устройства не принимается."""
    sender, sp, _, _ = register("regsnd")
    recipient, rp, _, _ = register("regrcp")
    s_token, r_token = login(sender, sp), login(recipient, rp)
    publish_device(recipient, r_token, "real")

    status, data = call("POST", "/messages", s_token, {
        "sender_id": sender, "recipient_id": recipient,
        "envelope": ratchet_envelope("ok"), "target_device_id": "real",
    })
    assert status == 200, f"на существующее устройство: {status} {data}"

    for bad in ("ghost", "primary-nope"):
        status, _ = call("POST", "/messages", s_token, {
            "sender_id": sender, "recipient_id": recipient,
            "envelope": ratchet_envelope("bad"), "target_device_id": bad,
        })
        assert status == 400, f"конверт на устройство {bad}: {status}, ожидался 400"

    # Устройство ОТПРАВИТЕЛЯ тоже не годится: оно не принадлежит получателю.
    publish_device(sender, s_token, "mine")
    status, _ = call("POST", "/messages", s_token, {
        "sender_id": sender, "recipient_id": recipient,
        "envelope": ratchet_envelope("bad"), "target_device_id": "mine",
    })
    assert status == 400, f"конверт на своё устройство как чужое: {status}"
    print("DEVICE-TARGET-003: ok")


def test_idempotency():
    """IDEMPOTENCY-001: повтор client_id — только для побайтно того же запроса."""
    sender, sp, _, _ = register("regidm")
    bob, bpw, _, _ = register("regidmb")
    carol, cpw, _, _ = register("regidmc")
    s_token = login(sender, sp)
    login(bob, bpw)
    login(carol, cpw)

    cid = str(uuid.uuid4())
    env_one = ratchet_envelope("ONE")
    status, data = call("POST", "/messages", s_token, {
        "sender_id": sender, "recipient_id": bob, "envelope": env_one, "client_id": cid})
    assert status == 200, f"первая отправка: {status} {data}"

    status, data = call("POST", "/messages", s_token, {
        "sender_id": sender, "recipient_id": bob, "envelope": env_one, "client_id": cid})
    assert status == 200 and data.get("duplicate") is True, f"честный ретрай: {status} {data}"

    status, _ = call("POST", "/messages", s_token, {
        "sender_id": sender, "recipient_id": carol,
        "envelope": ratchet_envelope("TWO"), "client_id": cid})
    assert status == 409, f"тот же id другому получателю: {status}, ожидался 409"

    status, _ = call("POST", "/messages", s_token, {
        "sender_id": sender, "recipient_id": bob,
        "envelope": ratchet_envelope("THREE"), "client_id": cid})
    assert status == 409, f"тот же id с другим телом: {status}, ожидался 409"
    print("IDEMPOTENCY-001: ok")


def test_group_member_must_exist():
    """GROUP-MEMBER-001: адресат приглашения обязан существовать."""
    owner, pw, _, _ = register("reggrp")
    token = login(owner, pw)
    gid = "g" + secrets.token_hex(8)
    status, data = call("POST", "/groups", token, {
        "id": gid, "name": "regression", "encrypted_key_b64": b64(secrets.token_bytes(48))})
    assert status == 200, f"create group: {status} {data}"

    status, _ = call("POST", f"/groups/{gid}/members", token, {
        "user_id": "ghost_" + secrets.token_hex(6),
        "encrypted_key_b64": b64(secrets.token_bytes(48))})
    assert status == 404, f"призрак в участниках: {status}, ожидался 404"

    real, rpw, _, _ = register("reggrpm")
    status, _ = call("POST", f"/groups/{gid}/members", token, {
        "user_id": real, "encrypted_key_b64": b64(secrets.token_bytes(48))})
    assert status == 200, "настоящего пользователя добавить можно"
    print("GROUP-MEMBER-001: ok")


def test_public_key_shape():
    """KEYSHAPE-001: ключ аккаунта — ровно 32 байта Curve25519."""
    # Пустая строка отсекается ещё pydantic-ом (min_length=16) — это 422,
    # а не 400 из _validate_key_b64. Для теста важно, что аккаунт не создан.
    for raw_len in (0, 16, 31, 33):
        _, _, status, _ = register("regkey", public_key_b64=b64(secrets.token_bytes(raw_len)))
        assert status in (400, 422), f"ключ длиной {raw_len} принят: {status}"
    _, _, status, _ = register("regkey", public_key_b64="!!!not-base64!!!")
    assert status in (400, 422), f"не-base64 ключ принят: {status}"
    print("KEYSHAPE-001: ok")


def test_ws_ticket_single_use():
    """WS-TICKET-001: билет гасится при предъявлении."""
    import socket
    from urllib.parse import urlparse

    user, pw, _, _ = register("regws")
    token = login(user, pw)
    status, data = call("POST", "/ws/ticket", token)
    assert status == 200 and data.get("ticket"), f"ws/ticket: {status} {data}"
    ticket = data["ticket"]

    u = urlparse(BASE)
    port = u.port or (443 if u.scheme == "https" else 80)
    if u.scheme == "https":
        print("WS-TICKET-001: пропущен (нужен plaintext-адрес для сырого хендшейка)")
        return

    def handshake(t):
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        req = (f"GET /ws?ticket={t} HTTP/1.1\r\nHost: {u.hostname}:{port}\r\n"
               "Upgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        with socket.create_connection((u.hostname, port), timeout=10) as s:
            s.sendall(req.encode())
            return s.recv(256).decode(errors="replace").splitlines()[0]

    first = handshake(ticket)
    assert "101" in first, f"первый билет отвергнут: {first}"
    second = handshake(ticket)
    assert "101" not in second, f"билет сработал дважды: {second}"
    print("WS-TICKET-001: ok")


def main():
    print(f"Сервер: {BASE}")
    test_2fa_downgrade()
    test_device_scoping()
    test_target_device_validation()
    test_idempotency()
    test_group_member_must_exist()
    test_public_key_shape()
    test_ws_ticket_single_use()
    print("\nВсе регрессии пройдены.")


if __name__ == "__main__":
    main()
