# -*- coding: utf-8 -*-
"""Подменить identity чужого слота device_id нельзя (только своей сессией).

Запуск (при поднятом локальном сервере): python scripts/test_device_identity.py
"""
import requests

from smoke_common import BASE, USER, check, login_or_register, random_key_b64


def main():
    token = login_or_register()
    auth = {"Authorization": f"Bearer {token}"}

    victim = "idsmoke-victim"
    attacker_device = "idsmoke-attacker"

    # Первый запуск устройства: сессия ещё не привязана — публиковать можно.
    r = requests.put(f"{BASE}/keys/upload", headers=auth,
                     json={"identity_key_b64": random_key_b64(),
                           "one_time_keys": {"k1": random_key_b64()},
                           "device_id": victim})
    check(r.status_code == 200, "непривязанная сессия публикует ключи нового устройства")

    r = requests.put(f"{BASE}/sessions/me/device", headers=auth, json={"device_id": attacker_device})
    check(r.status_code == 200, "bind сессии к своему устройству")
    r = requests.put(f"{BASE}/keys/upload", headers=auth,
                     json={"identity_key_b64": random_key_b64(),
                           "one_time_keys": {"k1": random_key_b64()},
                           "device_id": attacker_device})
    check(r.status_code == 200, "публикация ключей своего устройства разрешена")

    r = requests.put(f"{BASE}/keys/upload", headers=auth,
                     json={"identity_key_b64": random_key_b64(),
                           "one_time_keys": {"k1": random_key_b64()},
                           "device_id": victim})
    check(r.status_code == 403, f"подмена identity чужого слота -> 403 (получено {r.status_code})")

    # Идемпотентный ретрай (тот же identity, новые OTK) ломаться не должен.
    devices = {d["device_id"]: d["identity_key_b64"]
               for d in requests.get(f"{BASE}/users/{USER}/devices", headers=auth).json()["devices"]}
    r = requests.put(f"{BASE}/keys/upload", headers=auth,
                     json={"identity_key_b64": devices[victim],
                           "one_time_keys": {"k2": random_key_b64()},
                           "device_id": victim})
    check(r.status_code == 200, "тот же identity чужого слота (ретрай OTK) разрешён")

    for dev in (victim, attacker_device):
        requests.delete(f"{BASE}/sessions/device/{dev}", headers=auth)
    print("DEVICE_IDENTITY_SMOKE_OK")


if __name__ == "__main__":
    main()
