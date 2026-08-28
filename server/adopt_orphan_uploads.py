#!/usr/bin/env python3
"""NEW-5: учёт осиротевших медиа — файлов на диске без строки в `uploads`.

Таблица `uploads` появилась вместе с квотой и TTL-сборщиком и заполняется
ТОЛЬКО новыми загрузками. Всё, что легло на диск до неё, невидимо: не считается
в квоту и не удаляется сборщиком, то есть лежит вечно и бесплатно.

Скрипт разовый: находит такие файлы и заводит им строки. По умолчанию НИЧЕГО не
меняет — сначала показывает, что нашёл.

    python3 server/adopt_orphan_uploads.py                     # только отчёт
    python3 server/adopt_orphan_uploads.py --adopt             # завести строки
    python3 server/adopt_orphan_uploads.py --delete-unreferenced-avatars

Владелец определяется по-разному, и это главное ограничение:

* **аватарки** привязываются к настоящему владельцу — на файл ссылается
  `users.avatar_file_id` или `groups.avatar_file_id`;
* **медиа привязать не к кому.** `file_id` лежит ВНУТРИ зашифрованного конверта
  сообщения, сервер его не читает и прочитать не может. Поэтому такие файлы
  усыновляются на служебный аккаунт (`--orphan-owner`, по умолчанию `-orphan-`):
  в чью-то квоту они не попадут — честно списать их не на кого, — но станут
  видимы TTL-сборщику и перестанут лежать вечно.

  Имя нарочно НЕрегистрируемое: `user_id` при регистрации обязан подходить под
  `^[A-Za-z0-9_]+$`, а дефис в него не входит. Иначе служебное имя мог бы занять
  живой человек и унаследовать чужие файлы вместе с их объёмом в своей квоте.

`created_at` по умолчанию — момент усыновления, а НЕ mtime файла. Это выбор в
пользу безопасности: с mtime включение TTL снесло бы всё старьё в первый же
прогон сборщика, включая файлы, на которые ссылаются живые сообщения (проверить
ссылку невозможно — file_id внутри шифротекста). С датой усыновления у
владельца остаётся полный период TTL, чтобы заметить и передумать.

`--use-file-mtime` возвращает прежнее поведение: срок считается от реального
возраста файла. Брать его стоит, только если вы осознанно хотите вычистить
старьё быстро и готовы потерять то, на что ещё ссылаются.

Медиа скрипт НЕ удаляет: на них могут ссылаться живые сообщения, а проверить
это невозможно по той же причине. Удаление отдано TTL-сборщику, который
включается через AETHER_MEDIA_TTL_DAYS. Удалять умеет только аватарки, на
которые никто не ссылается, — там ссылку видно точно.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import psycopg2
import psycopg2.extras

FILE_ID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
UPLOAD_DIR = Path(__file__).resolve().parent / "uploads"
AVATAR_DIR = UPLOAD_DIR / "avatars"


def connect():
    return psycopg2.connect(
        dbname=os.environ.get("DB_NAME", "secure_messenger"),
        user=os.environ.get("DB_USER", "sm_user"),
        password=os.environ.get("DB_PASS", "sm_pass"),
        host=os.environ.get("DB_HOST", "127.0.0.1"),
    )


def size_mb(n: int) -> str:
    return f"{n / (1024 * 1024):.1f} МБ"


def scan(directory: Path) -> dict:
    """file_id -> (size, mtime_iso). Каталог avatars внутри uploads пропускаем."""
    found = {}
    if not directory.exists():
        return found
    for entry in directory.iterdir():
        if not entry.is_file() or not FILE_ID_RE.match(entry.name):
            continue
        st = entry.stat()
        found[entry.name] = (
            st.st_size,
            datetime.fromtimestamp(st.st_mtime, timezone.utc).isoformat(),
        )
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description="Учёт осиротевших медиа (NEW-5)")
    ap.add_argument("--adopt", action="store_true",
                    help="завести строки в uploads (по умолчанию только отчёт)")
    ap.add_argument("--delete-unreferenced-avatars", action="store_true",
                    help="удалить аватарки, на которые никто не ссылается")
    ap.add_argument("--orphan-owner", default="-orphan-",
                    help="служебный владелец для медиа без владельца (по умолчанию -orphan-; "
                         "дефис делает имя нерегистрируемым)")
    ap.add_argument("--use-file-mtime", action="store_true",
                    help="считать TTL от возраста файла, а не от момента усыновления. "
                         "Опасно: включение TTL снесёт старьё сразу")
    args = ap.parse_args()

    media = scan(UPLOAD_DIR)
    avatars = scan(AVATAR_DIR)
    if not media and not avatars:
        print(f"В {UPLOAD_DIR} файлов не найдено — нечего учитывать.")
        return 0

    conn = connect()
    conn.autocommit = False
    cur = conn.cursor(cursor_factory=psycopg2.extras.DictCursor)

    cur.execute("SELECT file_id FROM uploads")
    known = {r["file_id"] for r in cur.fetchall()}

    # Кто на какую аватарку ссылается: и пользователи, и группы.
    cur.execute("SELECT LOWER(user_id) AS owner, avatar_file_id FROM users WHERE avatar_file_id IS NOT NULL")
    avatar_owner = {r["avatar_file_id"]: r["owner"] for r in cur.fetchall()}
    cur.execute("SELECT LOWER(owner_id) AS owner, avatar_file_id FROM groups WHERE avatar_file_id IS NOT NULL")
    for r in cur.fetchall():
        avatar_owner.setdefault(r["avatar_file_id"], r["owner"])

    orphan_media = {k: v for k, v in media.items() if k not in known}
    orphan_avatars = {k: v for k, v in avatars.items() if k not in known}
    referenced = {k: v for k, v in orphan_avatars.items() if k in avatar_owner}
    unreferenced = {k: v for k, v in orphan_avatars.items() if k not in avatar_owner}

    print(f"Каталог: {UPLOAD_DIR}")
    print(f"  файлов на диске:      медиа {len(media)}, аватарок {len(avatars)}")
    print(f"  учтено в uploads:     {len(known)}")
    print()
    print(f"ОСИРОТЕВШИЕ МЕДИА:      {len(orphan_media)} шт., "
          f"{size_mb(sum(s for s, _ in orphan_media.values()))}")
    print(f"  владельца не определить (file_id внутри шифротекста) →")
    print(f"  усыновляются на «{args.orphan_owner}», чтобы их увидел TTL-сборщик")
    print()
    print(f"ОСИРОТЕВШИЕ АВАТАРКИ:   {len(orphan_avatars)} шт., "
          f"{size_mb(sum(s for s, _ in orphan_avatars.values()))}")
    print(f"  с живой ссылкой:      {len(referenced)} → привязываются к владельцу")
    print(f"  ни на кого не смотрят:{len(unreferenced)} → можно удалить "
          f"(--delete-unreferenced-avatars)")

    if not args.adopt and not args.delete_unreferenced_avatars:
        print("\nРежим отчёта: ничего не изменено. "
              "Повторите с --adopt, чтобы завести строки.")
        conn.rollback()
        return 0

    inserted = deleted = 0
    adopted_at = datetime.now(timezone.utc).isoformat()

    def created_for(mtime: str) -> str:
        """Дата, от которой TTL отсчитает срок жизни файла."""
        return mtime if args.use_file_mtime else adopted_at

    if args.adopt:
        for file_id, (size, mtime) in referenced.items():
            cur.execute(
                """INSERT INTO uploads (file_id, user_id, kind, size_bytes, created_at)
                   VALUES (%s, LOWER(%s), 'avatar', %s, %s)
                   ON CONFLICT (file_id) DO NOTHING""",
                (file_id, avatar_owner[file_id], size, created_for(mtime)))
            inserted += cur.rowcount
        for file_id, (size, mtime) in orphan_media.items():
            cur.execute(
                """INSERT INTO uploads (file_id, user_id, kind, size_bytes, created_at)
                   VALUES (%s, LOWER(%s), 'media', %s, %s)
                   ON CONFLICT (file_id) DO NOTHING""",
                (file_id, args.orphan_owner, size, created_for(mtime)))
            inserted += cur.rowcount

    if args.delete_unreferenced_avatars:
        for file_id in unreferenced:
            (AVATAR_DIR / file_id).unlink(missing_ok=True)
            cur.execute("DELETE FROM uploads WHERE file_id = %s", (file_id,))
            deleted += 1

    conn.commit()
    print(f"\nГотово: заведено строк {inserted}, удалено аватарок {deleted}.")
    if args.adopt and orphan_media:
        print("Медиа теперь видны TTL-сборщику. Он выключен по умолчанию — "
              "включается переменной AETHER_MEDIA_TTL_DAYS.")
        if args.use_file_mtime:
            print("ВНИМАНИЕ: срок считается от возраста файлов. Включение TTL "
                  "удалит старьё в первый же прогон сборщика.")
        else:
            print("Срок отсчитывается от СЕЙЧАС: после включения TTL у вас есть "
                  "полный период, чтобы передумать.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
