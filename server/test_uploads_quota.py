#!/usr/bin/env python3
"""NEW-4: квота хранилища — аватарки и точность потолка.

Запускать против сервера с МАЛЕНЬКОЙ квотой, иначе набор упрётся в дефолтные
2 ГБ и ничего не проверит:

    AETHER_UPLOAD_QUOTA_MB=1 uvicorn server.main:app --port 8099
    AETHER_URL=http://127.0.0.1:8099 python3 server/test_uploads_quota.py

Что ловится:
  QUOTA-AVATAR-001  /avatars отвергает загрузку, когда квота исчерпана.
                    Раньше квоту проверял только /upload, хотя аватарки пишутся
                    в ту же таблицу и попадают в ту же сумму: аккаунт на
                    границе продолжал грузить по 5 МБ десять раз в минуту.
  QUOTA-EXACT-002   потолок точный. Раньше проверка шла ДО сохранения, поэтому
                    аккаунт на границе успевал положить сверху целый файл, и
                    реальный предел был квота + MAX_UPLOAD_BYTES (плюс 50 МБ).
  QUOTA-AVATAR-003  смена аватарки убирает предыдущую: иначе включённая на
                    аватарки квота медленно выедалась бы сменой картинки.
"""
import json
import os
import secrets
import urllib.error
import urllib.request

BASE = os.environ.get("AETHER_URL", "").rstrip("/")
if not BASE:
    raise SystemExit("Задайте AETHER_URL (например http://127.0.0.1:8099).")

QUOTA_MB = int(os.environ.get("AETHER_UPLOAD_QUOTA_MB", "1"))
QUOTA_BYTES = QUOTA_MB * 1024 * 1024
CHUNK = 200 * 1024          # 200 КБ за загрузку — хватит нескольких на 1 МБ


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def upload(path, token, payload: bytes, filename="blob.bin"):
    """multipart/form-data руками: тянуть requests ради одного поля не стоит.

    429 здесь — помеха, а не результат: /upload ограничен 20 запросами в минуту,
    и набор наполняет квоту десятками мелких файлов. Пережидаем окно."""
    for attempt in range(4):
        status, data = _upload_once(path, token, payload, filename)
        if status != 429:
            return status, data
        import time
        wait = 61 if attempt == 0 else 31
        print(f"  429 на {path}: ждём {wait} с (ограничение частоты, не дефект)")
        time.sleep(wait)
    return status, data


def _upload_once(path, token, payload: bytes, filename):
    boundary = "----aether" + secrets.token_hex(8)
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode() + payload + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def head_ok(path):
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def register():
    user = "quota_" + secrets.token_hex(4)
    password = "Quota-test-" + secrets.token_hex(4)
    status, data = call("POST", "/users/register", body={
        "user_id": user,
        "public_key_b64": secrets.token_urlsafe(32)[:43],
        "encrypted_private_key_b64": "salt:iv:ct",
        "password": password,
    })
    assert status in (200, 201), f"register: {status} {data}"
    status, data = call("POST", "/users/login", body={"user_id": user, "password": password})
    assert status == 200, f"login: {status} {data}"
    return user, data["token"]


def main():
    print(f"Сервер: {BASE}, квота {QUOTA_MB} МБ")
    # Наполнять квоту кусками по 200 КБ при 20 запросах в минуту — значит ждать
    # минуту на каждые 4 МБ. Выше 16 МБ набор превращается в многоминутное
    # ожидание, и это уже не тест, а пытка.
    if QUOTA_BYTES > 16 * 1024 * 1024:
        raise SystemExit(
            f"Квота {QUOTA_MB} МБ слишком велика для теста: поднимите сервер с "
            "AETHER_UPLOAD_QUOTA_MB=1, иначе набор будет часами упираться в лимит частоты.")

    user, token = register()

    # --- QUOTA-EXACT-002: наполняем квоту медиа и следим за потолком ---
    stored = 0
    refusal = None
    for _ in range(200):
        status, data = upload("/upload", token, secrets.token_bytes(CHUNK))
        if status == 413:
            refusal = status
            break
        assert status == 200, f"/upload: {status} {data}"
        stored += CHUNK
    assert refusal == 413, "квота так и не сработала — проверьте AETHER_UPLOAD_QUOTA_MB"
    # Ключевая проверка: сверх квоты не легло НИЧЕГО. Прежний код пропускал
    # целый файл сверх лимита, потому что проверял до записи.
    assert stored <= QUOTA_BYTES, (
        f"принято {stored} Б при квоте {QUOTA_BYTES} Б — потолок неточен")
    print(f"QUOTA-EXACT-002: ok (принято {stored // 1024} КБ при квоте {QUOTA_BYTES // 1024} КБ)")

    # --- QUOTA-AVATAR-001: аватарка тоже упирается в квоту ---
    status, data = upload("/avatars", token, secrets.token_bytes(CHUNK), "a.png")
    assert status == 413, f"/avatars при исчерпанной квоте: {status} {data}, ожидался 413"
    print("QUOTA-AVATAR-001: ok")

    # --- QUOTA-AVATAR-003: смена аватарки убирает предыдущую ---
    user2, token2 = register()
    status, first = upload("/avatars", token2, secrets.token_bytes(CHUNK), "a.png")
    assert status == 200, f"первая аватарка: {status} {first}"
    status, _ = call("PUT", "/users/me/profile", token2, {"avatar_file_id": first["file_id"]})
    assert status == 200, "профиль с первой аватаркой"
    assert head_ok(f"/avatars/{first['file_id']}") == 200, "первая аватарка не отдаётся"

    status, second = upload("/avatars", token2, secrets.token_bytes(CHUNK), "b.png")
    assert status == 200, f"вторая аватарка: {status} {second}"
    status, _ = call("PUT", "/users/me/profile", token2, {"avatar_file_id": second["file_id"]})
    assert status == 200, "профиль со второй аватаркой"

    assert head_ok(f"/avatars/{second['file_id']}") == 200, "вторая аватарка не отдаётся"
    assert head_ok(f"/avatars/{first['file_id']}") == 404, (
        "прежняя аватарка осталась на диске — квота будет течь")
    print("QUOTA-AVATAR-003: ok")

    print("\nВСЁ ЗЕЛЁНОЕ")


if __name__ == "__main__":
    main()
