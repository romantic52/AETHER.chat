# -*- coding: utf-8 -*-
"""Границы multi-device: чужой слот не подменить, но legacy-клиент жив.

Проверяются два инварианта сразу, потому что они тянут друг друга:
  * привязанная сессия не трогает ни ключи, ни очередь чужого устройства;
  * непривязанная (старый iOS, веб — про device_id они не знают) работает
    как раньше: публикует ключи своего единственного устройства, читает свою
    очередь, а её сообщения без target_device_id доезжают до аккаунта, даже
    если слота 'primary' у того нет.

Запуск (при поднятом локальном сервере): python scripts/test_device_identity.py
"""
import uuid

import requests

from smoke_common import BASE, USER, check, login_or_register, random_key_b64


def main():
    token = login_or_register()
    auth = {"Authorization": f"Bearer {token}"}

    suffix = uuid.uuid4().hex[:8]
    victim = f"idsmoke-victim-{suffix}"
    attacker_device = f"idsmoke-attacker-{suffix}"

    # Первый запуск устройства: сессия ещё не привязана — создать слот можно.
    victim_identity = random_key_b64()
    r = requests.put(f"{BASE}/keys/upload", headers=auth,
                     json={"identity_key_b64": victim_identity,
                           "one_time_keys": {"k1": random_key_b64()},
                           "device_id": victim})
    check(r.status_code == 200, "непривязанная сессия публикует ключи нового устройства")

    # ...и тем самым занимает его: перепривязать её к другому устройству уже нельзя.
    r = requests.put(f"{BASE}/sessions/me/device", headers=auth,
                     json={"device_id": attacker_device})
    check(r.status_code == 409, "публикация ключей неявно привязала сессию к устройству")
    r = requests.put(f"{BASE}/sessions/me/device", headers=auth, json={"device_id": victim})
    check(r.status_code == 200, "явный bind к тому же устройству идемпотентен")

    # Пополнение OTK своего слота обязано работать: это единственный способ для
    # клиента остаться доступным для новых Olm-сессий.
    r = requests.put(f"{BASE}/keys/upload", headers=auth,
                     json={"identity_key_b64": victim_identity,
                           "one_time_keys": {"k2": random_key_b64()},
                           "device_id": victim})
    check(r.status_code == 200, "сессия пополняет OTK своего устройства")

    attacker_token = login_or_register()
    attacker_auth = {"Authorization": f"Bearer {attacker_token}"}
    r = requests.put(f"{BASE}/sessions/me/device", headers=attacker_auth,
                     json={"device_id": attacker_device})
    check(r.status_code == 200, "вторая сессия привязана к своему устройству")
    r = requests.put(f"{BASE}/keys/upload", headers=attacker_auth,
                     json={"identity_key_b64": random_key_b64(),
                           "one_time_keys": {"k1": random_key_b64()},
                           "device_id": attacker_device})
    check(r.status_code == 200, "вторая сессия публикует ключи своего устройства")

    r = requests.put(f"{BASE}/keys/upload", headers=attacker_auth,
                     json={"identity_key_b64": random_key_b64(),
                           "one_time_keys": {"k1": random_key_b64()},
                           "device_id": victim})
    check(r.status_code == 403, f"подмена identity чужого слота -> 403 (получено {r.status_code})")

    r = requests.put(f"{BASE}/keys/upload", headers=attacker_auth,
                     json={"identity_key_b64": victim_identity,
                           "one_time_keys": {"evil": random_key_b64()},
                           "device_id": victim})
    check(r.status_code == 403, "публичный identity не разрешает добавить чужие OTK")

    # --- inbox/ack: device_id — адрес, а не авторизация ---
    targeted = requests.post(f"{BASE}/messages", headers=auth, json={
        "sender_id": USER,
        "recipient_id": USER,
        "target_device_id": victim,
        "envelope": {
            "ratchet": "1",
            "olm_identity": random_key_b64(),
            "type": 0,
            "body_b64": random_key_b64(),
        },
    })
    check(targeted.status_code == 200, "сообщение конкретному устройству принято")
    targeted_id = targeted.json()["message_id"]

    r = requests.get(f"{BASE}/messages/inbox/{USER}", headers=attacker_auth,
                     params={"device_id": victim})
    check(r.status_code == 403, "привязанная сессия не читает inbox чужого устройства")
    r = requests.post(f"{BASE}/messages/ack", headers=attacker_auth,
                      json={"message_ids": [targeted_id], "device_id": victim})
    check(r.status_code == 403, "привязанная сессия не подтверждает чужие сообщения")

    # Legacy-сессия про device_id не знает и режима «своё устройство» не имеет:
    # запрещать ей очередь = полностью остановить доставку на старом клиенте.
    legacy_token = login_or_register()
    legacy_auth = {"Authorization": f"Bearer {legacy_token}"}
    r = requests.get(f"{BASE}/messages/inbox/{USER}", headers=legacy_auth,
                     params={"device_id": victim})
    check(r.status_code == 200 and targeted_id in {m["id"] for m in r.json()["messages"]},
          "непривязанная legacy-сессия не отрезана от очереди")

    r = requests.get(f"{BASE}/messages/inbox/{USER}", headers=auth,
                     params={"device_id": victim})
    check(r.status_code == 200 and targeted_id in {m["id"] for m in r.json()["messages"]},
          "привязанная сессия читает inbox своего устройства")
    r = requests.post(f"{BASE}/messages/ack", headers=auth,
                      json={"message_ids": [targeted_id], "device_id": victim})
    check(r.status_code == 200, "привязанная сессия подтверждает своё сообщение")

    # --- Маршрутизация отправителя, не знающего про устройства ---
    # Аккаунт теста устройства 'primary' не имеет: раньше такое сообщение
    # уезжало в несуществующий слот и не доходило никому.
    devices = requests.get(f"{BASE}/users/{USER}/devices", headers=auth).json()["devices"]
    ids = [d["device_id"] for d in devices]
    expected = "primary" if "primary" in ids else (ids[0] if ids else "primary")
    check(expected != attacker_device, "устройство по умолчанию — не последний добавленный слот")

    r = requests.post(f"{BASE}/keys/claim/{USER}", headers=legacy_auth)
    check(r.status_code != 404, "legacy claim без device_id находит устройство аккаунта")
    if r.status_code == 200:
        check(r.json()["device_id"] == expected, "legacy claim адресован устройству по умолчанию")

    r = requests.post(f"{BASE}/messages", headers=auth, json={
        "sender_id": USER,
        "recipient_id": USER,
        "envelope": {
            "ratchet": "1",
            "olm_identity": random_key_b64(),
            "type": 0,
            "body_b64": random_key_b64(),
        },
    })
    check(r.status_code == 200, "legacy direct ratchet принят")
    message_id = r.json()["message_id"]

    def inbox_ids(headers, device_id):
        response = requests.get(
            f"{BASE}/messages/inbox/{USER}", headers=headers,
            params={"device_id": device_id},
        )
        check(response.status_code == 200, f"inbox устройства {device_id}")
        return {item["id"] for item in response.json()["messages"]}

    check(message_id in inbox_ids(legacy_auth, expected),
          "legacy direct доехал до устройства по умолчанию")
    check(message_id not in inbox_ids(attacker_auth, attacker_device),
          "legacy direct не раздан остальным устройствам аккаунта")
    r = requests.post(f"{BASE}/messages/ack", headers=legacy_auth,
                      json={"message_ids": [message_id], "device_id": expected})
    check(r.status_code == 200, "legacy-сессия подтверждает своё сообщение")

    requests.delete(f"{BASE}/sessions/device/{victim}", headers=auth)
    requests.delete(f"{BASE}/sessions/device/{attacker_device}", headers=attacker_auth)
    requests.post(f"{BASE}/logout", headers=legacy_auth)
    print("DEVICE_IDENTITY_SMOKE_OK")


if __name__ == "__main__":
    main()
