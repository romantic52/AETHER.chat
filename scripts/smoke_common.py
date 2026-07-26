# -*- coding: utf-8 -*-
"""Общее для серверных смоуков: адрес, тестовый аккаунт, проверки.

Сервер поднимается локально (scripts/run_server.ps1). Адрес можно переопределить
переменной AETHER_SMOKE_SERVER, аккаунт — AETHER_SMOKE_USER/AETHER_SMOKE_PASS.
"""
import base64
import os
import sys

import requests

BASE = os.environ.get("AETHER_SMOKE_SERVER", "http://127.0.0.1:8765").rstrip("/")
# user_id на сервере — [A-Za-z0-9_], без дефисов.
USER = os.environ.get("AETHER_SMOKE_USER", "smoke_probe")
PASS = os.environ.get("AETHER_SMOKE_PASS", "smoke-probe-pass-1")


def check(cond, label):
    print(("OK  " if cond else "FAIL ") + label)
    if not cond:
        sys.exit(1)


def random_key_b64() -> str:
    return base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("=")


def login_or_register() -> str:
    """Токен тестового аккаунта; при первом запуске аккаунт создаётся.

    Ключи для регистрации случайные: смоуки проверяют серверные инварианты,
    настоящая крипта живёт в клиентских тестах (desktop: gradlew msgsmoke).
    """
    r = requests.post(f"{BASE}/users/login", json={"user_id": USER, "password": PASS})
    if r.status_code == 200:
        return r.json()["token"]
    r = requests.post(f"{BASE}/users/register", json={
        "user_id": USER,
        "password": PASS,
        "public_key_b64": random_key_b64(),
        "encrypted_private_key_b64": random_key_b64(),
    })
    check(r.status_code == 200, f"регистрация тестового аккаунта {USER}")
    r = requests.post(f"{BASE}/users/login", json={"user_id": USER, "password": PASS})
    check(r.status_code == 200, "логин тестового аккаунта")
    return r.json()["token"]


def db_conn():
    """Подключение к локальной БД сервера (смоуки правят expires_at и TTL)."""
    import psycopg2

    return psycopg2.connect(
        dbname=os.environ.get("DB_NAME", "secure_messenger"),
        user=os.environ.get("DB_USER", "sm_user"),
        password=os.environ.get("DB_PASS", "sm_pass"),
        host=os.environ.get("DB_HOST", "127.0.0.1"),
    )
