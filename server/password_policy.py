"""Хеширование паролей и требования к ним.

ЗАЧЕМ ЭТО ПЕРЕПИСАНО. Раньше пароль хешировался PBKDF2-HMAC-SHA256 со 100 000
итераций. Это ниже нынешней рекомендации OWASP (600 000 для этого алгоритма) и,
что важнее, PBKDF2 прекрасно считается на видеокартах: одна современная карта
перебирает такое со скоростью порядка 10^5 паролей в секунду. Словарный пароль
вскрывается за минуты, восьмизначный из букв и цифр — за месяцы на одной карте.

Argon2id memory-hard: он требует память, а не только такты, и параллелить его
на GPU дорого. Те же 64 МиБ на попытку превращают перебор из «месяц на одной
карте» в задачу другого порядка.

Совместимость сохранена полностью: старые хеши продолжают проверяться, а при
первом успешном входе тихо пересчитываются в новый формат. Никого не выкидывает
и просить сменить пароль не нужно.
"""

from __future__ import annotations

import hashlib
import re
import secrets
from typing import Optional, Tuple

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

# 64 МиБ памяти, 3 прохода. На этом сервере хеш считается ~140 мс — незаметно
# для входа при действующем ограничении частоты и дорого для перебора.
_hasher = PasswordHasher(time_cost=3, memory_cost=65_536, parallelism=1)

MIN_LENGTH = 10

# Пароли, которые ломаются первым же словарём. Список намеренно короткий: это
# не защита от перебора (её даёт Argon2id), а страховка от самого очевидного.
_COMMON = {
    "password", "parol", "qwerty", "qwertyui", "123456789", "1234567890",
    "password1", "aether", "aetherchat", "letmein", "welcome", "admin123",
    "iloveyou", "12345678", "987654321", "qwerty123", "пароль",
}


def hash_password(password: str) -> str:
    """Новый хеш. Всегда Argon2id — PBKDF2 больше не создаётся."""
    return _hasher.hash(password)


def verify_password(password: str, hashed: str) -> bool:
    """Проверить пароль против хеша любого поколения."""
    if not hashed:
        return False
    if hashed.startswith("$argon2"):
        try:
            return _hasher.verify(hashed, password)
        except (VerifyMismatchError, VerificationError, InvalidHashError):
            return False
    return _verify_pbkdf2(password, hashed)


def _verify_pbkdf2(password: str, hashed: str) -> bool:
    """Старый формат: pbkdf2_sha256$итерации$соль$хеш."""
    try:
        parts = hashed.split("$")
        if len(parts) != 4 or parts[0] != "pbkdf2_sha256":
            return False
        iterations, salt, expected = int(parts[1]), parts[2], parts[3]
        computed = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt.encode("utf-8"), iterations
        ).hex()
        return secrets.compare_digest(computed, expected)
    except Exception:
        return False


def needs_rehash(hashed: str) -> bool:
    """Пора ли пересчитать хеш в актуальный формат и параметры."""
    if not hashed or not hashed.startswith("$argon2"):
        return True
    try:
        return _hasher.check_needs_rehash(hashed)
    except InvalidHashError:
        return True


def check_strength(password: str, user_id: str = "") -> Tuple[bool, Optional[str]]:
    """Годится ли пароль для НОВОЙ учётной записи.

    Проверяется только при регистрации и смене пароля: существующих
    пользователей выкидывать нельзя, они ни в чём не виноваты.

    Правила намеренно про предсказуемость, а не про «спецсимвол обязателен».
    Требование символов гонит людей в «Password1!», который словарь берёт так
    же легко, зато длину человек выбирает сам.
    """
    if len(password) < MIN_LENGTH:
        return False, f"password_too_short:{MIN_LENGTH}"

    lowered = password.lower()
    if lowered in _COMMON:
        return False, "password_too_common"

    # Один символ на всю длину: aaaaaaaaaa.
    if len(set(password)) <= 2:
        return False, "password_too_simple"

    # Подряд идущие цифры или буквы: 1234567890, abcdefghij.
    if _is_sequential(lowered):
        return False, "password_too_simple"

    if user_id and len(user_id) >= 3 and user_id.lower() in lowered:
        return False, "password_contains_username"

    return True, None


def _is_sequential(value: str) -> bool:
    if len(value) < MIN_LENGTH or not re.fullmatch(r"[a-z0-9]+", value):
        return False
    deltas = {ord(b) - ord(a) for a, b in zip(value, value[1:])}
    return deltas in ({1}, {-1})
