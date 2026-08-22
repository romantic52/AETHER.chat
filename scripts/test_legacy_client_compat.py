# -*- coding: utf-8 -*-
"""Старый клиент (iOS до multi-device) и новый (Android) должны видеть друг друга.

Старая сборка iOS не знает про устройства совсем: не зовёт /sessions/me/device,
не шлёт device_id ни в /keys/upload, ни в /keys/claim, ни в inbox/ack, ни
target_device_id при отправке. Новый Android живёт в слоте 'android-xxx' и
рассылает копию каждому устройству получателя.

Регрессия, которую тест ловит: сервер начал требовать привязку сессии и жёстко
адресовать legacy-сообщения в 'primary'. Старый iOS переставал пополнять OTK
(403), а его сообщения уезжали в несуществующий 'primary' аккаунта Android.

Запуск (при поднятом локальном сервере): python scripts/test_legacy_client_compat.py
"""
import uuid

import requests

from smoke_common import BASE, check, random_key_b64


def register(user_id: str, password: str) -> str:
    r = requests.post(f"{BASE}/users/register", json={
        "user_id": user_id,
        "password": password,
        "public_key_b64": random_key_b64(),
        "encrypted_private_key_b64": random_key_b64(),
    })
    check(r.status_code == 200, f"регистрация {user_id}")
    r = requests.post(f"{BASE}/users/login", json={"user_id": user_id, "password": password})
    check(r.status_code == 200, f"логин {user_id}")
    return r.json()["token"]


def envelope() -> dict:
    return {"ratchet": "1", "olm_identity": random_key_b64(),
            "type": 0, "body_b64": random_key_b64()}


def main():
    suffix = uuid.uuid4().hex[:8]
    ios_id, android_id = f"compat_ios_{suffix}", f"compat_droid_{suffix}"
    password = "compat-smoke-pass-1"

    ios = {"Authorization": f"Bearer {register(ios_id, password)}"}
    android = {"Authorization": f"Bearer {register(android_id, password)}"}
    android_device = f"android-{suffix}"

    # --- Старый iOS: ни bind, ни device_id нигде ---
    ios_identity = random_key_b64()
    r = requests.put(f"{BASE}/keys/upload", headers=ios,
                     json={"identity_key_b64": ios_identity,
                           "one_time_keys": {"i1": random_key_b64()}})
    check(r.status_code == 200, "старый iOS публикует ключи без device_id")

    # Пополнение OTK: именно здесь legacy-клиент получал 403 и тихо оставался
    # без одноразовых ключей — собеседник больше не мог завести Olm-сессию.
    r = requests.put(f"{BASE}/keys/upload", headers=ios,
                     json={"identity_key_b64": ios_identity,
                           "one_time_keys": {"i2": random_key_b64()}})
    check(r.status_code == 200, "старый iOS пополняет OTK без device_id")

    # --- Новый Android: свой слот, сессия привязана ---
    r = requests.put(f"{BASE}/sessions/me/device", headers=android,
                     json={"device_id": android_device})
    check(r.status_code == 200, "Android привязывает сессию к своему устройству")
    android_identity = random_key_b64()
    r = requests.put(f"{BASE}/keys/upload", headers=android,
                     json={"identity_key_b64": android_identity,
                           "one_time_keys": {"a1": random_key_b64(), "a2": random_key_b64()},
                           "device_id": android_device})
    check(r.status_code == 200, "Android публикует ключи своего слота")

    # --- iPhone -> Android: аккаунта 'primary' у Android нет ---
    r = requests.post(f"{BASE}/keys/claim/{android_id}", headers=ios)
    check(r.status_code == 200, f"iPhone забирает prekey-bundle Android (получено {r.status_code})")
    check(r.json()["device_id"] == android_device, "bundle взят у реального устройства Android")
    check(r.json()["identity_key_b64"] == android_identity, "identity в bundle — Android'а")

    r = requests.post(f"{BASE}/messages", headers=ios, json={
        "sender_id": ios_id, "recipient_id": android_id, "envelope": envelope()})
    check(r.status_code == 200, "iPhone отправляет без target_device_id")
    to_android = r.json()["message_id"]

    r = requests.get(f"{BASE}/messages/inbox/{android_id}", headers=android,
                     params={"device_id": android_device})
    check(r.status_code == 200 and to_android in {m["id"] for m in r.json()["messages"]},
          "Android получил сообщение с iPhone")

    # --- Android -> iPhone: fanout по директории устройств ---
    r = requests.get(f"{BASE}/users/{ios_id}/devices", headers=android)
    check(r.status_code == 200, "Android читает директорию устройств iPhone")
    ios_devices = [d["device_id"] for d in r.json()["devices"]]
    check(ios_devices == ["primary"], f"старый iOS виден как одно устройство primary ({ios_devices})")

    r = requests.post(f"{BASE}/keys/claim/{ios_id}?device_id=primary", headers=android)
    check(r.status_code == 200, "Android забирает prekey-bundle iPhone")
    check(r.json()["identity_key_b64"] == ios_identity, "identity в bundle — iPhone'а")

    r = requests.post(f"{BASE}/messages", headers=android, json={
        "sender_id": android_id, "recipient_id": ios_id,
        "target_device_id": "primary", "envelope": envelope()})
    check(r.status_code == 200, "Android отправляет копию устройству iPhone")
    to_ios = r.json()["message_id"]

    # Старый iOS читает inbox без device_id и подтверждает без него же.
    r = requests.get(f"{BASE}/messages/inbox/{ios_id}", headers=ios)
    check(r.status_code == 200 and to_ios in {m["id"] for m in r.json()["messages"]},
          "iPhone получил сообщение с Android")
    r = requests.post(f"{BASE}/messages/ack", headers=ios, json={"message_ids": [to_ios]})
    check(r.status_code == 200, "iPhone подтверждает приём без device_id")

    print("LEGACY_COMPAT_SMOKE_OK")


if __name__ == "__main__":
    main()
