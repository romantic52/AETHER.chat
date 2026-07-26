#!/usr/bin/env python3
"""Смок-тест prekey-директории: подписанные бандлы (P7) и cross-signing (P8).

Запуск против живого сервера (по умолчанию прод):
    AETHER_URL=http://127.0.0.1:8000 python3 server/test_prekeys.py

Нужен PyNaCl (он и так требуется серверу). Проверяет:
  * upload подписанного + cross-signed бандла и отдачу подписей в claim/devices;
  * отказ при битой подписи identity, OTK и устройства;
  * анти-даунгрейд (после подписанной публикации неподписанная не принимается);
  * требование одного мастер-ключа на все устройства аккаунта.

Ключи здесь — случайные 32 байта: сервер проверяет длину и подписи, а не то,
что это точки кривой. Каноны продублированы из core/ratchet-core.
"""
import base64
import json
import os
import secrets
import urllib.error
import urllib.request

from nacl.signing import SigningKey

BASE = os.environ.get("AETHER_URL", "https://YOUR-SERVER-HOST.nip.io")


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def call(method, path, token=None, body=None):
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
        return e.code, json.loads(e.read() or b"{}")


def register(prefix):
    user = f"{prefix}_{secrets.token_hex(4)}"
    password = "Prekey-test-" + secrets.token_hex(4)
    status, _ = call("POST", "/users/register", body={
        "user_id": user,
        "public_key_b64": b64(secrets.token_bytes(32)),
        "encrypted_private_key_b64": "salt:iv:ct",
        "password": password,
    })
    assert status in (200, 201), f"register: {status}"
    status, data = call("POST", "/users/login", body={"user_id": user, "password": password})
    assert status == 200, f"login: {status}"
    return user, data["token"]


def signed_bundle(user, device, *, master: SigningKey = None, otk_count=2):
    """Собрать тело /keys/upload по канонам ядра."""
    device_key = SigningKey.generate()
    ed_b64 = b64(bytes(device_key.verify_key))
    identity_b64 = b64(secrets.token_bytes(32))
    body = {
        "identity_key_b64": identity_b64,
        "ed25519_key_b64": ed_b64,
        "identity_sig_b64": b64(device_key.sign(
            f"AETHER-IDKEY-1|{user.lower()}|{device}|{identity_b64}".encode()).signature),
        "one_time_keys": {},
        "otk_signatures": {},
        "device_id": device,
    }
    for _ in range(otk_count):
        key_id = secrets.token_hex(4)
        key_b64 = b64(secrets.token_bytes(32))
        body["one_time_keys"][key_id] = key_b64
        body["otk_signatures"][key_id] = b64(device_key.sign(
            f"AETHER-OTK-1|{user.lower()}|{device}|{identity_b64}|{key_id}|{key_b64}"
            .encode()).signature)
    if master is not None:
        body["master_key_b64"] = b64(bytes(master.verify_key))
        body["device_sig_b64"] = b64(master.sign(
            f"AETHER-DEVSIG-1|{user.lower()}|{device}|{identity_b64}|{ed_b64}".encode()).signature)
    return body


def main():
    print(f"Сервер: {BASE}")
    alice, alice_token = register("prekey_a")
    bob, bob_token = register("prekey_b")
    master = SigningKey.generate()
    device = "test-" + secrets.token_hex(3)

    # 1. Подписанная + cross-signed публикация.
    body = signed_bundle(bob, device, master=master)
    status, data = call("PUT", "/keys/upload", bob_token, body)
    assert status == 200, f"signed upload: {status} {data}"
    print("upload подписанного бандла: ok")

    # 2. Битые подписи отвергаются.
    for field, label in (("identity_sig_b64", "identity"), ("device_sig_b64", "устройства")):
        bad = signed_bundle(bob, device, master=master)
        bad[field] = b64(secrets.token_bytes(64))
        status, _ = call("PUT", "/keys/upload", bob_token, bad)
        assert status == 400, f"битая подпись {label} принята: {status}"
    bad = signed_bundle(bob, device, master=master)
    some_key = next(iter(bad["otk_signatures"]))
    bad["otk_signatures"][some_key] = b64(secrets.token_bytes(64))
    status, _ = call("PUT", "/keys/upload", bob_token, bad)
    assert status == 400, f"битая подпись OTK принята: {status}"
    print("битые подписи (identity/OTK/устройство): отвергнуты")

    # 3. Анти-даунгрейд: неподписанная публикация того же устройства.
    status, _ = call("PUT", "/keys/upload", bob_token, {
        "identity_key_b64": b64(secrets.token_bytes(32)),
        "one_time_keys": {secrets.token_hex(4): b64(secrets.token_bytes(32))},
        "device_id": device,
    })
    assert status == 400, f"анти-даунгрейд не сработал: {status}"
    print("анти-даунгрейд: неподписанная публикация отвергнута")

    # 4. Один мастер на аккаунт: второе устройство с чужим мастером.
    other_master = SigningKey.generate()
    status, _ = call("PUT", "/keys/upload", bob_token,
                     signed_bundle(bob, "test-second", master=other_master))
    assert status == 409, f"чужой мастер принят: {status}"
    status, _ = call("PUT", "/keys/upload", bob_token,
                     signed_bundle(bob, "test-second", master=master))
    assert status == 200, f"второе устройство с тем же мастером: {status}"
    print("один мастер на аккаунт: расхождение отвергнуто, совпадение принято")

    # 5. Claim отдаёт подписи и мастер-поля.
    status, bundle = call("POST", f"/keys/claim/{bob}?device_id={device}", alice_token)
    assert status == 200, f"claim: {status} {bundle}"
    for field in ("ed25519_key_b64", "identity_sig_b64", "master_key_b64", "device_sig_b64"):
        assert bundle.get(field), f"claim без поля {field}"
    assert bundle["one_time_key"].get("sig_b64"), "claim без подписи OTK"
    assert bundle["identity_key_b64"] == body["identity_key_b64"], "claim вернул чужой identity"
    print("claim: подписи и мастер-поля на месте")

    # 6. Проверка подписей бандла на стороне «получателя».
    from nacl.signing import VerifyKey
    vk = VerifyKey(base64.urlsafe_b64decode(bundle["ed25519_key_b64"] + "=="))
    vk.verify(f"AETHER-IDKEY-1|{bob.lower()}|{device}|{bundle['identity_key_b64']}".encode(),
              base64.urlsafe_b64decode(bundle["identity_sig_b64"] + "=="))
    mk = VerifyKey(base64.urlsafe_b64decode(bundle["master_key_b64"] + "=="))
    mk.verify(f"AETHER-DEVSIG-1|{bob.lower()}|{device}|{bundle['identity_key_b64']}|"
              f"{bundle['ed25519_key_b64']}".encode(),
              base64.urlsafe_b64decode(bundle["device_sig_b64"] + "=="))
    print("подписи бандла и устройства проверены получателем: ok")

    # 7. Директория устройств отдаёт мастер-поля.
    status, devices = call("GET", f"/users/{bob}/devices", alice_token)
    assert status == 200, f"devices: {status}"
    entry = next((d for d in devices["devices"] if d["device_id"] == device), None)
    assert entry and entry.get("master_key_b64") == bundle["master_key_b64"], "devices без мастера"
    print("директория устройств: мастер-поля на месте")

    print("\nВСЁ ЗЕЛЁНОЕ")


if __name__ == "__main__":
    main()
