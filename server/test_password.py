#!/usr/bin/env python3
"""Смок-тест паролей: Argon2id, тихая миграция старых хешей, политика.

Запуск против живого сервера:
    AETHER_URL=http://127.0.0.1:8099 python3 server/test_password.py

Проверяет главное свойство перехода: существующий пользователь со старым
PBKDF2-хешем продолжает входить тем же паролем, а хеш при этом молча
пересчитывается в Argon2id. Никого не выкидывает и менять пароль не просят.

Тест сам создаёт подопытного и сам портит ему хеш на старый формат прямо в
базе — поэтому нужен доступ к БД, а не только к API.
"""
import hashlib
import json
import os
import secrets
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("AETHER_URL", "http://127.0.0.1:8099")
V1 = BASE + "/api/v1"
PUB = "AAAAAAAAAAAAAAAAAAAAAA"


def call(method, path, body=None):
    req = urllib.request.Request(V1 + path, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, json.dumps(body).encode() if body else None, timeout=20) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except json.JSONDecodeError:
            return e.code, {"raw": raw.decode(errors="replace")}


def check(label, ok, extra=""):
    print(("  ok   " if ok else "  FAIL ") + label + ((" — " + str(extra)) if not ok and extra else ""))
    if not ok:
        sys.exit(1)


def db():
    import psycopg2
    import psycopg2.extras
    conn = psycopg2.connect(
        dbname=os.environ.get("DB_NAME", "secure_messenger"),
        user=os.environ.get("DB_USER", "sm_user"),
        password=os.environ.get("DB_PASS", "sm_pass"),
        host=os.environ.get("DB_HOST", "127.0.0.1"),
    )
    conn.autocommit = True
    return conn


def hash_prefix(conn, user):
    with conn.cursor() as cur:
        cur.execute("SELECT substring(password_hash from 1 for 9) FROM users WHERE user_id = %s", (user,))
        row = cur.fetchone()
    return row[0] if row else None


def main():
    tag = secrets.token_hex(3)
    user = f"pwtest_{tag}"
    password = f"dolgiy-parol-{tag}"

    print("Новый аккаунт")
    status, body = call("POST", "/users/register",
                        {"user_id": user, "public_key_b64": PUB, "password": password})
    check("регистрация проходит", status == 200, body)

    conn = db()
    check("новый хеш — argon2id", hash_prefix(conn, user) == "$argon2id", hash_prefix(conn, user))

    print("\nПолитика паролей")
    cases = [
        ("korotkiy1", "слишком короткий", (400, 422)),
        ("1234567890", "слишком частый", (400,)),
        ("aaaaaaaaaaaa", "слишком предсказуемый", (400,)),
        ("abcdefghijkl", "подряд идущие буквы", (400,)),
    ]
    for pwd, label, codes in cases:
        status, _ = call("POST", "/users/register",
                         {"user_id": f"pw_{secrets.token_hex(3)}", "public_key_b64": PUB, "password": pwd})
        check(f"отклонён: {label}", status in codes, status)

    status, _ = call("POST", "/users/register",
                     {"user_id": f"soderzhit_{tag}", "public_key_b64": PUB,
                      "password": f"soderzhit_{tag}-hvost"})
    check("отклонён пароль с именем пользователя", status == 400, status)

    print("\nТихая миграция старого хеша")
    # Портим хеш на старый формат — как будто аккаунт заведён до перехода.
    salt = secrets.token_hex(16)
    legacy = "pbkdf2_sha256$100000$" + salt + "$" + hashlib.pbkdf2_hmac(
        "sha256", password.encode(), salt.encode(), 100000).hex()
    with conn.cursor() as cur:
        cur.execute("UPDATE users SET password_hash = %s WHERE user_id = %s", (legacy, user))
    check("хеш вернулся к старому формату", hash_prefix(conn, user) == "pbkdf2_sh", hash_prefix(conn, user))

    status, body = call("POST", "/auth/login", {"user_id": user, "password": password})
    check("вход старым хешем тем же паролем", status == 200 and body.get("ok"), body)
    check("хеш пересчитан в argon2id", hash_prefix(conn, user) == "$argon2id", hash_prefix(conn, user))

    status, body = call("POST", "/auth/login", {"user_id": user, "password": password})
    check("повторный вход после пересчёта", status == 200 and body.get("ok"), body)

    status, _ = call("POST", "/auth/login", {"user_id": user, "password": password + "x"})
    check("чужой пароль по-прежнему отвергается", status == 401, status)

    print("\nВсё сошлось. Старые пароли работают, хеши переезжают сами.")


if __name__ == "__main__":
    main()
