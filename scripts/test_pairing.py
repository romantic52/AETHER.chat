# -*- coding: utf-8 -*-
"""Протокол QR-привязки устройства: полный флоу и его инварианты.

Запуск (при поднятом локальном сервере): python scripts/test_pairing.py
"""
import json
import threading
import time

import requests

from smoke_common import BASE, check, db_conn, login_or_register


def main():
    # Телефон — уже доверенное устройство аккаунта
    phone_token = login_or_register()
    auth = {"Authorization": f"Bearer {phone_token}"}

    # WS-слушатель телефона: ждём device_added
    ws_events = []

    def ws_listen():
        import websockets.sync.client as wsc
        try:
            ws_url = BASE.replace("https://", "wss://").replace("http://", "ws://")
            with wsc.connect(f"{ws_url}/ws?token={phone_token}") as ws:
                deadline = time.time() + 30
                while time.time() < deadline:
                    try:
                        msg = ws.recv(timeout=5)
                        ws_events.append(json.loads(msg))
                        return
                    except TimeoutError:
                        continue
        except Exception as e:
            ws_events.append({"error": str(e)})

    listener = threading.Thread(target=ws_listen, daemon=True)
    listener.start()
    time.sleep(1)

    # 1. start
    r = requests.post(f"{BASE}/pairing/start", json={"eph_pub_b64": "A" * 43})
    check(r.status_code == 200, "pairing/start")
    pid = r.json()["pairing_id"]
    sec = r.json()["pairing_secret"]
    check(len(sec) >= 32, "секрет достаточной длины")

    # 2. Неверный секрет отклоняется
    r = requests.get(f"{BASE}/pairing/{pid}/status", headers={"X-Pairing-Secret": "wrong-secret-wrong"})
    check(r.status_code == 403, "неверный секрет -> 403")

    # Секрет в query не принимается: он оседал бы в access-логах сервера и прокси
    r = requests.get(f"{BASE}/pairing/{pid}/status?sec={sec}")
    check(r.status_code == 400, f"секрет в query больше не принимается -> 400 (получено {r.status_code})")

    # 3. approve телефоном
    r = requests.post(f"{BASE}/pairing/approve", headers=auth, json={
        "pairing_id": pid, "pairing_secret": sec,
        "encrypted_bundle_b64": "ZmFrZS1idW5kbGU",
        "platform": "desktop",
    })
    check(r.status_code == 200, "pairing/approve")
    device_id = r.json()["device_id"]
    check(device_id.startswith("desktop-") and len(device_id) == len("desktop-") + 8, f"device_id формата desktop-8hex: {device_id}")

    # 4. Повторный approve -> 409 (одноразовость)
    r = requests.post(f"{BASE}/pairing/approve", headers=auth, json={
        "pairing_id": pid, "pairing_secret": sec,
        "encrypted_bundle_b64": "ZmFrZQ",
    })
    check(r.status_code == 409, "повторный approve -> 409")

    # 5. status: одноразовая выдача токена и bundle
    r = requests.get(f"{BASE}/pairing/{pid}/status", headers={"X-Pairing-Secret": sec})
    check(r.status_code == 200 and r.json().get("status") == "approved", "status -> approved")
    data = r.json()
    check(data["encrypted_bundle_b64"] == "ZmFrZS1idW5kbGU", "bundle доехал")
    check(data["device_id"] == device_id, "device_id совпал")
    new_token = data["session_token"]
    check(bool(new_token), "токен выдан")

    # 6. Второй запрос -> claimed, без токена и bundle
    r = requests.get(f"{BASE}/pairing/{pid}/status", headers={"X-Pairing-Secret": sec})
    check(r.json().get("status") == "claimed", "повторный status -> claimed")
    check("session_token" not in r.json(), "токен повторно не выдаётся")

    # 7. Выданная сессия работает и привязана к устройству
    r = requests.post(f"{BASE}/users/me/heartbeat", headers={"Authorization": f"Bearer {new_token}"}, json={})
    check(r.status_code == 200, "heartbeat новой сессии")

    # 8. TTL: истёкший pending -> expired
    r = requests.post(f"{BASE}/pairing/start", json={"eph_pub_b64": "B" * 43})
    pid2, sec2 = r.json()["pairing_id"], r.json()["pairing_secret"]
    conn = db_conn()
    cur = conn.cursor()
    cur.execute("UPDATE pairings SET expires_at = '2020-01-01T00:00:00+00:00' WHERE pairing_id = %s", (pid2,))
    conn.commit()
    r = requests.get(f"{BASE}/pairing/{pid2}/status", headers={"X-Pairing-Secret": sec2})
    check(r.json().get("status") == "expired", "истёкший QR -> expired")
    r = requests.post(f"{BASE}/pairing/approve", headers=auth, json={
        "pairing_id": pid2, "pairing_secret": sec2, "encrypted_bundle_b64": "eA",
    })
    check(r.status_code == 410, "approve истёкшего -> 410")

    # 9. approve без auth -> 401
    r = requests.post(f"{BASE}/pairing/approve", json={
        "pairing_id": pid, "pairing_secret": sec, "encrypted_bundle_b64": "eA",
    })
    check(r.status_code == 401, "approve без auth -> 401")

    # 10. WS device_added дошёл до телефона
    listener.join(timeout=10)
    got = [e for e in ws_events if e.get("type") == "device_added"]
    check(bool(got) and got[0].get("device_id") == device_id, f"WS device_added: {ws_events}")

    # Чистка тестовой сессии
    requests.delete(f"{BASE}/sessions/device/{device_id}", headers=auth)
    print("PAIRING_SMOKE_OK")


if __name__ == "__main__":
    main()
