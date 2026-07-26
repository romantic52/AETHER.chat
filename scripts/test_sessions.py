# -*- coding: utf-8 -*-
"""Sliding expiry сессий: heartbeat продлевает срок, но не чаще раза в сутки.

Запуск (при поднятом локальном сервере): python scripts/test_sessions.py
"""
from datetime import datetime, timedelta, timezone

import requests

from smoke_common import BASE, check, db_conn, login_or_register


def expires_of(cur, token):
    cur.execute("SELECT expires_at FROM sessions WHERE token = %s", (token,))
    return datetime.fromisoformat(cur.fetchone()[0])


def main():
    token = login_or_register()
    auth = {"Authorization": f"Bearer {token}"}

    conn = db_conn()
    conn.autocommit = True
    cur = conn.cursor()
    fresh = expires_of(cur, token)

    requests.post(f"{BASE}/users/me/heartbeat", headers=auth, json={})
    check(expires_of(cur, token) == fresh, "свежая сессия не продлевается лишний раз")

    near = (datetime.now(timezone.utc) + timedelta(days=3)).isoformat()
    cur.execute("UPDATE sessions SET expires_at = %s WHERE token = %s", (near, token))
    requests.post(f"{BASE}/users/me/heartbeat", headers=auth, json={})
    renewed = expires_of(cur, token)
    check(renewed > datetime.fromisoformat(near) + timedelta(days=20),
          f"истекающая сессия продлена: {renewed}")

    past = (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
    cur.execute("UPDATE sessions SET expires_at = %s WHERE token = %s", (past, token))
    r = requests.post(f"{BASE}/users/me/heartbeat", headers=auth, json={})
    check(r.status_code == 401, "истёкшая сессия отвергается")
    print("SESSIONS_SMOKE_OK")


if __name__ == "__main__":
    main()
