#!/usr/bin/env python3
"""Смок-тест отзыва сессии: после POST /logout токен недействителен.

Запуск против живого сервера (по умолчанию прод):
    AETHER_URL=http://127.0.0.1:8000 python3 server/test_logout.py

Создаёт одноразовый аккаунт logout_test_<hex>, логинится, проверяет что
токен работает, выходит — и убеждается, что тот же токен получает 401.
"""
import json
import os
import secrets
import urllib.error
import urllib.request

BASE = os.environ.get("AETHER_URL", "https://YOUR-SERVER-HOST.nip.io")


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
        return e.code, {}


def main() -> None:
    uid = "logout_test_" + secrets.token_hex(4)
    pwd = secrets.token_hex(12)

    status, _ = call("POST", "/users/register", body={
        "user_id": uid,
        "password": pwd,
        "public_key_b64": "A" * 43,
    })
    assert status == 200, f"register: ожидали 200, получили {status}"

    status, resp = call("POST", "/users/login", body={"user_id": uid, "password": pwd})
    assert status == 200 and resp.get("token"), f"login: {status}"
    token = resp["token"]

    status, _ = call("GET", "/keys/count", token)
    assert status == 200, f"запрос с живым токеном: ожидали 200, получили {status}"

    status, _ = call("POST", "/logout", token, body={})
    assert status == 200, f"logout: ожидали 200, получили {status}"

    status, _ = call("GET", "/keys/count", token)
    assert status == 401, f"токен ЖИВ после logout: ожидали 401, получили {status}"

    # Повторный logout тем же токеном тоже должен быть отвергнут.
    status, _ = call("POST", "/logout", token, body={})
    assert status == 401, f"повторный logout: ожидали 401, получили {status}"

    print(f"ok: сессия {uid} недействительна после выхода")


if __name__ == "__main__":
    main()
