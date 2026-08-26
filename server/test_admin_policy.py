#!/usr/bin/env python3
"""Смок-тест этапа 2: политика регистрации, роли, приглашения, refresh.

Нужен аккаунт владельца:
    AETHER_URL=http://127.0.0.1:8099 AETHER_OWNER=ownertest \
    AETHER_OWNER_PASS=pass12345 python3 server/test_admin_policy.py

Тест меняет режим регистрации сервера и в конце возвращает исходный,
поэтому гонять его на боевом сервере нельзя — только на своём.
"""
import json
import os
import secrets
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("AETHER_URL", "http://127.0.0.1:8099")
OWNER = os.environ.get("AETHER_OWNER", "ownertest")
OWNER_PASS = os.environ.get("AETHER_OWNER_PASS", "pass12345")
V1 = BASE + "/api/v1"

PUB = "AAAAAAAAAAAAAAAAAAAAAA"


def call(method, url, token=None, body=None):
    req = urllib.request.Request(url, method=method)
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
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except json.JSONDecodeError:
            return e.code, {"raw": raw.decode(errors="replace")}


def check(label, ok, extra=""):
    print(("  ok   " if ok else "  FAIL ") + label + ((" — " + str(extra)) if not ok and extra else ""))
    if not ok:
        sys.exit(1)


def register(user, code=None):
    body = {"user_id": user, "public_key_b64": PUB, "password": "probnyi-parol-77"}
    if code:
        body["invite_code"] = code
    return call("POST", V1 + "/users/register", body=body)


def set_mode(token, mode):
    return call("PUT", V1 + "/admin/settings", token=token, body={"registration_mode": mode})


def main():
    tag = secrets.token_hex(3)

    print("Владелец")
    status, login = call("POST", V1 + "/auth/login",
                         body={"user_id": OWNER, "password": OWNER_PASS})
    check("вход владельца", status == 200, login)
    owner_token = login["token"]
    check("выдан refresh", bool(login.get("refresh_token")))
    check("роль в ответе входа = OWNER", login.get("role") == "OWNER", login.get("role"))

    status, me = call("GET", V1 + "/users/me", token=owner_token)
    check("/users/me отдаёт роль", status == 200 and me["role"] == "OWNER", me)
    check("/users/me отдаёт server_id", bool(me.get("server_id")))

    status, settings = call("GET", V1 + "/admin/settings", token=owner_token)
    check("владелец читает настройки", status == 200, settings)
    original_mode = settings["registration_mode"]

    print("\nРежим OPEN")
    check("режим переключён", set_mode(owner_token, "open")[0] == 200)
    user = f"pol_{tag}_open"
    status, body = register(user)
    check("обычная регистрация проходит", status == 200, body)
    check("новичок получает USER", body.get("role") == "USER", body)

    status, ulogin = call("POST", V1 + "/auth/login", body={"user_id": user, "password": "probnyi-parol-77"})
    user_token = ulogin["token"]
    status, _ = call("GET", V1 + "/admin/overview", token=user_token)
    check("USER не пускается в админку (403)", status == 403, status)
    status, _ = call("GET", V1 + "/admin/users", token=user_token)
    check("USER не видит список пользователей", status == 403, status)

    print("\nРежим INVITE_ONLY")
    check("режим переключён", set_mode(owner_token, "invite_only")[0] == 200)
    status, body = register(f"pol_{tag}_noinv")
    check("без кода — отказ invite_required",
          status == 403 and body.get("detail") == "invite_required", body)

    status, inv = call("POST", V1 + "/admin/invites", token=owner_token,
                       body={"label": "тест", "max_uses": 1})
    check("владелец создал приглашение", status == 200 and inv.get("code"), inv)
    code = inv["code"]

    status, body = register(f"pol_{tag}_inv", code)
    check("по коду регистрация проходит", status == 200, body)
    status, body = register(f"pol_{tag}_inv2", code)
    check("код на одно применение повторно не работает",
          status == 403 and body.get("detail") == "invite_invalid", body)

    status, invites = call("GET", V1 + "/admin/invites", token=owner_token)
    listed = invites.get("invites", [])
    check("в списке приглашений нет самого кода",
          all("code" not in i for i in listed), listed[:1])

    print("\nРежим CLOSED")
    check("режим переключён", set_mode(owner_token, "closed")[0] == 200)
    status, body = register(f"pol_{tag}_closed")
    check("регистрация закрыта",
          status == 403 and body.get("detail") == "registration_closed", body)

    print("\nРоли")
    status, _ = call("PUT", V1 + f"/admin/roles/{OWNER}", token=user_token, body={"role": "USER"})
    check("USER не может раздавать роли", status == 403, status)
    status, body = call("PUT", V1 + f"/admin/roles/{user}", token=owner_token,
                        body={"role": "MODERATOR"})
    check("владелец назначил модератора", status == 200, body)

    status, mlogin = call("POST", V1 + "/auth/login", body={"user_id": user, "password": "probnyi-parol-77"})
    mod_token = mlogin["token"]
    check("роль в ответе входа обновилась", mlogin.get("role") == "MODERATOR", mlogin.get("role"))
    status, _ = call("GET", V1 + "/admin/overview", token=mod_token)
    check("модератор видит overview", status == 200, status)
    status, _ = call("GET", V1 + "/admin/users", token=mod_token)
    check("модератор НЕ видит список пользователей (нужен ADMIN)", status == 403, status)
    status, body = call("PUT", V1 + f"/admin/roles/{user}", token=owner_token, body={"role": "OWNER"})
    check("владелец может назначить владельца", status == 200, body)
    status, body = call("PUT", V1 + f"/admin/roles/{OWNER}", token=owner_token, body={"role": "USER"})
    check("нельзя менять роль самому себе", status == 400, body)
    # Возвращаем подопытного в USER, чтобы не плодить владельцев.
    call("PUT", V1 + f"/admin/roles/{user}", token=owner_token, body={"role": "USER"})

    print("\nRefresh с ротацией")
    status, first = call("POST", V1 + "/auth/refresh",
                         body={"refresh_token": login["refresh_token"]})
    check("refresh обменялся на новую пару", status == 200 and first.get("refresh_token"), first)
    check("новый refresh отличается от старого",
          first["refresh_token"] != login["refresh_token"])
    status, me2 = call("GET", V1 + "/users/me", token=first["token"])
    check("новый access работает", status == 200, me2)

    status, reused = call("POST", V1 + "/auth/refresh",
                          body={"refresh_token": login["refresh_token"]})
    check("повторное использование старого refresh отвергнуто",
          status == 401 and reused.get("detail") == "token_reused", reused)
    status, _ = call("GET", V1 + "/users/me", token=first["token"])
    check("после кражи вся цепочка погашена (access мёртв)", status == 401, status)

    print("\nВосстановление режима")
    status, relogin = call("POST", V1 + "/auth/login",
                           body={"user_id": OWNER, "password": OWNER_PASS})
    check("повторный вход владельца", status == 200, relogin)
    check("режим возвращён", set_mode(relogin["token"], original_mode)[0] == 200)

    print(f"\nВсё сошлось. Режим сервера возвращён в {original_mode}.")


if __name__ == "__main__":
    main()
