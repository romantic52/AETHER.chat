#!/usr/bin/env bash
#
# AETHER — деплой server/main.py на прод VPS.
#
# Ограничения прода (docs/PROJECT_CONTEXT_FOR_AI.md §8, docs/handoff-android.md «Грабли»):
#   * scp/SFTP на VPS ОТКЛЮЧЁН → файл едет как base64 внутри ssh-сессии, декодируется python3;
#   * fail2ban банит частые ssh-подключения → ровно ОДИН вызов ssh на весь деплой
#     (backup + заливка + py_compile + restart + health + откат — всё в одной сессии).
#     ControlMaster/ControlPersist добавлены, чтобы повторный запуск в течение 60 с
#     переиспользовал уже открытое соединение, а не аутентифицировался заново.
#
# Что делает:
#   1. локальный py_compile (чтобы не тратить ssh-сессию на заведомо битый файл);
#   2. на сервере: декод base64 → sha256-сверка → py_compile ВРЕМЕННОЙ копии (прод не тронут);
#   3. бэкап текущего main.py с меткой времени: main.py.bak.YYYYmmdd-HHMMSS (UTC, время сервера);
#   4. установка нового файла (cat > target — inode/владелец/права сохраняются);
#   5. py_compile уже установленного файла — ДО рестарта;
#   6. systemctl restart secure_messenger;
#   7. ожидание + health-проба (curl), N попыток;
#   8. при любом провале после установки — автооткат на бэкап, рестарт, повторная проба.
#
# Использование:
#   ./deploy_server.sh                       # катит ./server/main.py из репозитория
#   ./deploy_server.sh /path/to/main.py      # катит указанный файл
#   DRY_RUN=1 ./deploy_server.sh             # только собрать и показать удалённый скрипт
#
# Health: в репозитории у сервера есть GET /health (server/main.py), маршрута GET / нет —
# корень отдал бы 404 и не отличил бы живой сервис от упавшего. Поэтому по умолчанию
# пробуем /health. Если нужен именно корень:
#   HEALTH_URL=https://<SERVER_HOST>/ HEALTH_OK_CODES="200 404" ./deploy_server.sh
#
# Коды выхода: 0 — успех; 1 — локальная ошибка (файл/python3/ssh); 2 — деплой провалился,
# откат выполнен, сервис поднят на старом файле; 3 — провалился и откат (нужны руки).

set -euo pipefail

# ---------------------------------------------------------------- параметры
# Локальные значения (адрес health-пробы и прочее) — вне git, чтобы боевой хост
# не возвращался в репозиторий после чистки. Файл необязательный.
# shellcheck source=/dev/null
[ -f "$(dirname -- "${BASH_SOURCE[0]}")/deploy.local.env" ] \
    && . "$(dirname -- "${BASH_SOURCE[0]}")/deploy.local.env"

SSH_HOST="${SSH_HOST:-aether-vps}"                      # алиас из ~/.ssh/config → root@<SERVER_IP>
REMOTE_FILE="${REMOTE_FILE:-/root/secure_messenger/server/main.py}"
SERVICE="${SERVICE:-secure_messenger}"
HEALTH_URL="${HEALTH_URL:-https://<SERVER_HOST>/health}"
HEALTH_OK_CODES="${HEALTH_OK_CODES:-200}"               # список через пробел
HEALTH_RETRIES="${HEALTH_RETRIES:-12}"                  # 12 × 5 с ≈ 60 с на подъём
HEALTH_INTERVAL="${HEALTH_INTERVAL:-5}"
DRY_RUN="${DRY_RUN:-0}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_FILE="${1:-${SCRIPT_DIR}/../server/main.py}"

log()  { printf '\033[1;36m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- локальные проверки
[ -f "$LOCAL_FILE" ] || die "нет файла: $LOCAL_FILE"
[ -s "$LOCAL_FILE" ] || die "файл пустой: $LOCAL_FILE"
command -v python3 >/dev/null 2>&1 || die "локально нужен python3 (base64 + sha256 + py_compile)"
command -v ssh     >/dev/null 2>&1 || die "нет ssh"

LOCAL_FILE="$(cd -- "$(dirname -- "$LOCAL_FILE")" && pwd)/$(basename -- "$LOCAL_FILE")"

log "источник: $LOCAL_FILE ($(wc -c < "$LOCAL_FILE" | tr -d ' ') байт)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/aether-deploy.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

log "локальный py_compile…"
PYTHONPYCACHEPREFIX="$WORK" python3 -m py_compile "$LOCAL_FILE" \
  || die "локальный py_compile не прошёл — ssh-сессию не тратим"

SHA_LOCAL="$(python3 - "$LOCAL_FILE" <<'PY'
import hashlib, sys
with open(sys.argv[1], 'rb') as fh:
    print(hashlib.sha256(fh.read()).hexdigest())
PY
)"
log "sha256 (локально): $SHA_LOCAL"

# ---------------------------------------------------------------- сборка удалённого скрипта
B64_LOCAL="$WORK/payload.b64"
REMOTE_SH="$WORK/remote.sh"

python3 - "$LOCAL_FILE" <<'PY' > "$B64_LOCAL"
import base64, sys
with open(sys.argv[1], 'rb') as fh:
    enc = base64.b64encode(fh.read()).decode('ascii')
for i in range(0, len(enc), 76):
    print(enc[i:i + 76])
PY

{
  printf '%s\n' 'set -euo pipefail'
  # значения подставляются с shell-квотированием — никакого разъезда кавычек
  printf 'REMOTE_FILE=%q\n'     "$REMOTE_FILE"
  printf 'SERVICE=%q\n'         "$SERVICE"
  printf 'HEALTH_URL=%q\n'      "$HEALTH_URL"
  printf 'HEALTH_OK_CODES=%q\n' "$HEALTH_OK_CODES"
  printf 'HEALTH_RETRIES=%q\n'  "$HEALTH_RETRIES"
  printf 'HEALTH_INTERVAL=%q\n' "$HEALTH_INTERVAL"
  printf 'SHA_LOCAL=%q\n'       "$SHA_LOCAL"
  # payload: base64-алфавит не может содержать строку-маркер, коллизия невозможна
  printf '%s\n' 'RTMP="$(mktemp -d /tmp/aether-deploy.XXXXXX)"'
  printf '%s\n' 'B64_FILE="$RTMP/payload.b64"'
  printf '%s\n' "cat > \"\$B64_FILE\" <<'AETHER_PAYLOAD_EOF'"
  cat "$B64_LOCAL"
  printf '%s\n' 'AETHER_PAYLOAD_EOF'
  cat <<'REMOTE_LOGIC'

STAGE="$RTMP/main.py"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BACKUP="${REMOTE_FILE}.bak.${STAMP}"
BACKED_UP=0

rlog()  { printf '  [srv] %s\n' "$*"; }
rdie()  { printf '  [srv][fail] %s\n' "$*" >&2; exit 1; }
cleanup() { rm -rf "$RTMP"; }
trap cleanup EXIT

probe_health() {
  # 0 — здоров, 1 — нет. Пустой ответ curl → код 000.
  local code i c
  for i in $(seq 1 "$HEALTH_RETRIES"); do
    sleep "$HEALTH_INTERVAL"
    if ! systemctl is-active --quiet "$SERVICE"; then
      rlog "health ${i}/${HEALTH_RETRIES}: сервис не active, ждём"
      continue
    fi
    code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$HEALTH_URL" 2>/dev/null || echo 000)"
    for c in $HEALTH_OK_CODES; do
      if [ "$code" = "$c" ]; then
        rlog "health ${i}/${HEALTH_RETRIES}: HTTP $code — OK"
        return 0
      fi
    done
    rlog "health ${i}/${HEALTH_RETRIES}: HTTP $code"
  done
  return 1
}

rollback() {
  local reason="$1"
  printf '  [srv][rollback] %s\n' "$reason" >&2
  if [ "$BACKED_UP" -ne 1 ]; then
    rdie "бэкап не создавался — откатывать нечего, прод не менялся"
  fi
  cat "$BACKUP" > "$REMOTE_FILE"
  rlog "восстановлен $BACKUP -> $REMOTE_FILE"
  systemctl restart "$SERVICE" || printf '  [srv][rollback] restart вернул ошибку\n' >&2
  if probe_health; then
    printf '  [srv][rollback] сервис поднят на прежней версии\n' >&2
    printf '  [srv] журнал новой (сломанной) версии:\n' >&2
    journalctl -u "$SERVICE" -n 40 --no-pager >&2 || true
    exit 2
  fi
  printf '  [srv][rollback] ОТКАТ НЕ ПОМОГ — сервис не отвечает, нужны руки\n' >&2
  journalctl -u "$SERVICE" -n 60 --no-pager >&2 || true
  exit 3
}

# --- 1. base64 -> файл (scp/SFTP на этом VPS отключён)
python3 - "$B64_FILE" "$STAGE" <<'PY'
import base64, sys
with open(sys.argv[1], 'rb') as fh:
    blob = fh.read()
with open(sys.argv[2], 'wb') as fh:
    fh.write(base64.b64decode(blob))
PY

# --- 2. сверка контрольной суммы (обрыв/порча внутри сессии)
SHA_REMOTE="$(python3 - "$STAGE" <<'PY'
import hashlib, sys
with open(sys.argv[1], 'rb') as fh:
    print(hashlib.sha256(fh.read()).hexdigest())
PY
)"
[ "$SHA_REMOTE" = "$SHA_LOCAL" ] || rdie "sha256 не совпал: ожидали $SHA_LOCAL, получили $SHA_REMOTE"
rlog "sha256 совпал: $SHA_REMOTE"

# --- 3. синтаксис ВРЕМЕННОЙ копии — прод ещё не тронут
python3 -m py_compile "$STAGE" || rdie "py_compile нового файла не прошёл — прод не тронут"
rlog "py_compile (staging) OK"

# --- 4. бэкап текущего main.py
[ -f "$REMOTE_FILE" ] || rdie "на сервере нет $REMOTE_FILE — деплой прерван"
cp -p "$REMOTE_FILE" "$BACKUP"
BACKED_UP=1
rlog "бэкап: $BACKUP"

# --- 5. установка (cat > сохраняет inode, владельца и права)
cat "$STAGE" > "$REMOTE_FILE"
rlog "записан $REMOTE_FILE"

SHA_INSTALLED="$(python3 - "$REMOTE_FILE" <<'PY'
import hashlib, sys
with open(sys.argv[1], 'rb') as fh:
    print(hashlib.sha256(fh.read()).hexdigest())
PY
)"
[ "$SHA_INSTALLED" = "$SHA_LOCAL" ] || rollback "sha256 установленного файла не совпал"

# --- 6. синтаксис установленного файла — ДО рестарта
python3 -m py_compile "$REMOTE_FILE" || rollback "py_compile установленного файла не прошёл"
rlog "py_compile (installed) OK"

# --- 7. рестарт
rlog "systemctl restart $SERVICE"
systemctl restart "$SERVICE" || rollback "systemctl restart вернул ошибку"

# --- 8. health
rlog "health: $HEALTH_URL (ожидаем HTTP: $HEALTH_OK_CODES)"
probe_health || rollback "health не поднялся за $((HEALTH_RETRIES * HEALTH_INTERVAL)) с"

rlog "ГОТОВО: новая версия живёт, бэкап $BACKUP"
REMOTE_LOGIC
} > "$REMOTE_SH"

if [ "$DRY_RUN" = "1" ]; then
  KEEP="${TMPDIR:-/tmp}/aether-remote-$$.sh"
  cp "$REMOTE_SH" "$KEEP"
  bash -n "$KEEP" && log "bash -n удалённого скрипта: OK"
  log "DRY_RUN=1 — скрипт собран, но НЕ отправлен: $KEEP"
  exit 0
fi

# ---------------------------------------------------------------- одна ssh-сессия
# ControlMaster/ControlPersist — чтобы повторные запуски не долбились в fail2ban;
# ServerAlive* — чтобы сессия не отвалилась, пока ждём health.
SSH_OPTS=(
  -o ControlMaster=auto
  -o ControlPath="${TMPDIR:-/tmp}/aether-ssh-%r@%h:%p"
  -o ControlPersist=60
  -o ConnectTimeout=15
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=10
)

log "ssh $SSH_HOST — одна сессия: backup → base64 → py_compile → restart → health"
set +e
ssh "${SSH_OPTS[@]}" "$SSH_HOST" 'bash -s' < "$REMOTE_SH"
RC=$?
set -e

case "$RC" in
  0) log "деплой успешен" ;;
  2) warn "деплой откачен: сервис работает на предыдущем main.py (бэкап на сервере)" ;;
  3) warn "ОТКАТ НЕ ПОМОГ — сервис лежит, зайди руками: ssh $SSH_HOST" ;;
  255) warn "ssh не подключился (ключ/сеть/fail2ban). Прод не менялся." ;;
  *) warn "удалённый скрипт завершился с кодом $RC" ;;
esac
exit "$RC"
