#!/bin/bash
# Use fake Docker/systemctl commands; never contact a daemon or install a service.
set -euo pipefail
# The bundled Windows Git Bash omits chmod/install; Windows permissions are not
# part of these mocked command-flow tests. Linux uses the real coreutils.
if [[ "$OSTYPE" == msys* ]]; then
  if ! command -v chmod >/dev/null; then chmod() { :; }; export -f chmod; fi
  if ! command -v install >/dev/null; then
    install() { [[ "${1:-}" == -m ]] && shift 2; cp "$@"; }
    export -f install
  fi
fi
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/work-logs"
TEST_TMP=$(mktemp -d "$ROOT/work-logs/install-smoke-XXXXXX")
export TEST_TMP
mkdir -p "$TEST_TMP/bin" "$TEST_TMP/package/springboot-backend/src/main/resources/db" "$TEST_TMP/package/vite-frontend"
cp "$ROOT/panel_install.sh" "$ROOT/install.sh" "$ROOT/docker-compose-v4.yml" "$ROOT/docker-compose-v6.yml" "$ROOT/gost.sql" "$TEST_TMP/package/"
cp "$ROOT/springboot-backend/Dockerfile" "$TEST_TMP/package/springboot-backend/"
cp "$ROOT/vite-frontend/Dockerfile" "$TEST_TMP/package/vite-frontend/"
cp "$ROOT/springboot-backend/src/main/resources/db/upgrade_forward_chain.sql" "$TEST_TMP/package/springboot-backend/src/main/resources/db/"
cat > "$TEST_TMP/bin/docker" <<'MOCK'
#!/bin/bash
printf '%s\n' "$*" >> "$TEST_TMP/docker.calls"
case "$*" in
  "volume inspect mysql_data") exit 1 ;;
  "network inspect gost-network "*) exit 1 ;;
  "compose build backend frontend") exit "${MOCK_BUILD_FAIL:-0}" ;;
  "ps --format "*) printf 'springboot-backend\ngost-mysql\n' ;;
  "inspect -f "*)
    if [[ "${MOCK_DB_READY_FAIL:-0}" == 1 && "$*" == *State.Status* ]]; then echo exited; else echo healthy; fi ;;
  "exec gost-mysql sh -c "*) exit "${MOCK_DB_READY_FAIL:-0}" ;;
  "exec springboot-backend env") printf 'DB_NAME=test_db\nDB_USER=test_user\nDB_PASSWORD=test_password\nDB_HOST=mysql\n' ;;
  "exec -i gost-mysql mysql "*) cat > "$TEST_TMP/migration.sql"; exit "${MOCK_MIGRATION_FAIL:-0}" ;;
  "ps -a -q")
    [[ "${MOCK_PS_FAIL:-0}" == 1 ]] && exit 1
    [[ "${MOCK_IMAGES:-0}" == 1 ]] && printf 'cid_active\n'
    ;;
  "inspect --format "*)
    [[ "${MOCK_INSPECT_FAIL:-0}" == 1 ]] && exit 1
    [[ "${MOCK_IMAGES:-0}" == 1 ]] && printf 'sha256:active117rc1\n'
    ;;
  "images --no-trunc --format "*)
    [[ "${MOCK_IMAGES_FAIL:-0}" == 1 ]] && exit 1
    if [[ "${MOCK_IMAGES:-0}" == 1 ]]; then
      printf 'ghcr.io/toto0912/flux-chain-backend\t1.1.7-rc.1\tsha256:active117rc1\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\t1.1.6\tsha256:old116\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\t1.1.5\tsha256:old115\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\tv1.1.4\tsha256:old114\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\t1.1.6-rc1\tsha256:oldrc1\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\t1.1.7-beta.1\tsha256:otherpre\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\t1.1.7\tsha256:newer117\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\t1.1.8\tsha256:newer118\n'
      printf 'ghcr.io/toto0912/flux-chain-backend\tlatest\tsha256:latesttag\n'
      printf 'ghcr.io/toto0912/flux-chain-frontend\t<none>\tsha256:danglingfe\n'
      printf 'nginx\tlatest\tsha256:nginx1\n'
    fi
    ;;
  "images -f dangling=true "*)
    [[ "${MOCK_IMAGES_FAIL:-0}" == 1 ]] && exit 1
    [[ "${MOCK_IMAGES:-0}" == 1 ]] && printf 'sha256:danglingoci\n'
    ;;
  "rmi "*) exit 0 ;;
esac
MOCK
printf '#!/bin/bash\nexit 0\n' > "$TEST_TMP/bin/sleep"
printf '#!/bin/bash\necho "UNEXPECTED NETWORK COMMAND" >&2\nexit 99\n' > "$TEST_TMP/bin/curl"
cp "$TEST_TMP/bin/curl" "$TEST_TMP/bin/wget"
for mock in "$TEST_TMP/bin/"*; do
  sed -i "1c#!$BASH" "$mock"
done
chmod +x "$TEST_TMP/bin/"*
export PATH="$TEST_TMP/bin:$PATH" PANEL_IPV6=0 PANEL_IMAGE_MODE=build
cd "$TEST_TMP"

# Starting outside the package still reads resources beside the script.
printf '17000\n17001\n' | "$BASH" "$TEST_TMP/package/panel_install.sh" install > "$TEST_TMP/install.out"
test -s "$TEST_TMP/package/.env"
cmp "$ROOT/docker-compose-v4.yml" "$TEST_TMP/package/docker-compose.yml"
grep -q '^compose build backend frontend$' "$TEST_TMP/docker.calls"
grep -q '^compose up -d --no-build --pull never --force-recreate$' "$TEST_TMP/docker.calls"
cp "$TEST_TMP/package/.env" "$TEST_TMP/env.before"
grep -q '^compose up -d --no-build --pull never mysql$' "$TEST_TMP/docker.calls"
grep -q 'CREATE TABLE IF NOT EXISTS `forward_chain`' "$TEST_TMP/migration.sql"
python3 - "$TEST_TMP/docker.calls" <<'PY'
from pathlib import Path
import sys
calls=Path(sys.argv[1]).read_text().splitlines()
start=calls.index('compose up -d --no-build --pull never mysql')
migration=next(i for i,c in enumerate(calls) if c.startswith('exec -i gost-mysql mysql '))
application=calls.index('compose up -d --no-build --pull never --force-recreate')
assert start < migration < application
PY
echo 'PASS first install migrates the initialized database before starting the panel'

for failure in readiness migration; do
  cp -r "$TEST_TMP/package" "$TEST_TMP/$failure"
  rm "$TEST_TMP/$failure/.env"
  : > "$TEST_TMP/docker.calls"
  if [[ "$failure" == readiness ]]; then
    if printf '17002\n17003\n' | MOCK_DB_READY_FAIL=1 "$BASH" "$TEST_TMP/$failure/panel_install.sh" install > "$TEST_TMP/$failure.out" 2>&1; then echo 'FAIL expected readiness error'; exit 1; fi
  else
    if printf '17002\n17003\n' | MOCK_MIGRATION_FAIL=7 "$BASH" "$TEST_TMP/$failure/panel_install.sh" install > "$TEST_TMP/$failure.out" 2>&1; then echo 'FAIL expected migration error'; exit 1; fi
  fi
  ! grep -q '^compose up -d --no-build --pull never --force-recreate$' "$TEST_TMP/docker.calls"
done
echo 'PASS database initialization/migration errors prevent panel startup'

# Updating preserves credentials and includes the custom schema migration.
: > "$TEST_TMP/docker.calls"
"$BASH" "$TEST_TMP/package/panel_install.sh" update > "$TEST_TMP/update.out"
cmp "$TEST_TMP/env.before" "$TEST_TMP/package/.env"
grep -q 'CREATE TABLE IF NOT EXISTS `forward_chain`' "$TEST_TMP/migration.sql"
! grep -Eq '^compose (down|pull)' "$TEST_TMP/docker.calls"
echo 'PASS update preserves credentials/volumes and applies local migration'

# Panel cleanup removes strictly older FluxChain images and preserves newer/latest/active/unrelated images.
: > "$TEST_TMP/docker.calls"
MOCK_IMAGES=1 "$BASH" "$TEST_TMP/package/panel_install.sh" cleanup > "$TEST_TMP/cleanup.out"
grep -q '^rmi ghcr.io/toto0912/flux-chain-backend:1.1.6$' "$TEST_TMP/docker.calls"
grep -q '^rmi ghcr.io/toto0912/flux-chain-backend:1.1.5$' "$TEST_TMP/docker.calls"
grep -q '^rmi ghcr.io/toto0912/flux-chain-backend:v1.1.4$' "$TEST_TMP/docker.calls"
grep -q '^rmi ghcr.io/toto0912/flux-chain-backend:1.1.6-rc1$' "$TEST_TMP/docker.calls"
grep -q '^rmi sha256:danglingfe$' "$TEST_TMP/docker.calls"
grep -q '^rmi sha256:danglingoci$' "$TEST_TMP/docker.calls"
! grep -q '1.1.7-rc.1' "$TEST_TMP/docker.calls"
! grep -q '1.1.7-beta.1' "$TEST_TMP/docker.calls"
! grep -q ':1.1.7$' "$TEST_TMP/docker.calls"
! grep -q '1.1.8' "$TEST_TMP/docker.calls"
! grep -q 'latest' "$TEST_TMP/docker.calls"
! grep -q 'nginx' "$TEST_TMP/docker.calls"
! grep -q 'volume' "$TEST_TMP/docker.calls"
echo 'PASS panel cleanup removes only strictly older unused FluxChain images'

# Docker query failures must abort cleanup without deleting any image.
for query_fail in MOCK_PS_FAIL MOCK_INSPECT_FAIL MOCK_IMAGES_FAIL; do
  : > "$TEST_TMP/docker.calls"
  if env MOCK_IMAGES=1 "$query_fail=1" "$BASH" "$TEST_TMP/package/panel_install.sh" cleanup > "$TEST_TMP/$query_fail.out" 2>&1; then
    echo "FAIL expected cleanup error when $query_fail=1" >&2; exit 1
  fi
  ! grep -q '^rmi ' "$TEST_TMP/docker.calls"
done
echo 'PASS panel cleanup fails closed on Docker query errors'

# PANEL_CLEANUP_OLD_IMAGES=1 runs cleanup at the end of update.
: > "$TEST_TMP/docker.calls"
MOCK_IMAGES=1 PANEL_CLEANUP_OLD_IMAGES=1 "$BASH" "$TEST_TMP/package/panel_install.sh" update > "$TEST_TMP/update-cleanup.out"
grep -q '^rmi ghcr.io/toto0912/flux-chain-backend:1.1.5$' "$TEST_TMP/docker.calls"
echo 'PASS panel update supports automatic old image cleanup'

# A failed build never reaches container recreation.
: > "$TEST_TMP/docker.calls"
if MOCK_BUILD_FAIL=23 "$BASH" "$TEST_TMP/package/panel_install.sh" update > "$TEST_TMP/failed-build.out" 2>&1; then
  echo 'FAIL expected build error' >&2; exit 1
fi
! grep -q '^compose up ' "$TEST_TMP/docker.calls"
cmp "$TEST_TMP/env.before" "$TEST_TMP/package/.env"
echo 'PASS build failure leaves existing services untouched'

# Local image package mode never builds or pulls.
mkdir -p "$TEST_TMP/package/images"
printf 'mock image archive\n' > "$TEST_TMP/package/images/panel-images.tar.gz"
: > "$TEST_TMP/docker.calls"
PANEL_IMAGE_MODE=load "$BASH" "$TEST_TMP/package/panel_install.sh" update > "$TEST_TMP/load.out"
grep -q '^load -i ' "$TEST_TMP/docker.calls"
! grep -Eq '(^pull |^compose (build|pull))' "$TEST_TMP/docker.calls"
echo 'PASS local image archive mode does not pull/build'

# Missing inputs fail before any build or service replacement.
mv "$TEST_TMP/package/gost.sql" "$TEST_TMP/package/gost.sql.hidden"
: > "$TEST_TMP/docker.calls"
if "$BASH" "$TEST_TMP/package/panel_install.sh" update > "$TEST_TMP/missing.out" 2>&1; then
  echo 'FAIL expected missing resource error' >&2; exit 1
fi
! grep -Eq '^compose (build|up)' "$TEST_TMP/docker.calls"
echo 'PASS missing local resources stop deployment'

# Node updates validate the new executable before stopping the old service.
if "$BASH" > "$TEST_TMP/node-invalid.out" 2>&1 <<'NODE'; then
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  mkdir -p "$INSTALL_DIR"
  printf 'existing-config\n' > "$INSTALL_DIR/config.json"
  printf "#!%s\nexit 8\n" "$BASH" > "$TEST_TMP/bad-gost"
  BINARY_FILE="$TEST_TMP/bad-gost"
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/systemctl.calls"; }
  update_gost
NODE
  echo 'FAIL invalid node accepted' >&2; exit 1
fi
test ! -f "$TEST_TMP/systemctl.calls"
grep -q 'existing-config' "$TEST_TMP/node/config.json"
echo 'PASS invalid node binary does not stop original service'

(
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  printf '#!%s\necho gost-test\n' "$BASH" > "$TEST_TMP/good-gost"
  BINARY_FILE="$TEST_TMP/good-gost"
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/systemctl.calls"; [[ "$1" == "show" ]] && echo 0; return 0; }
  update_gost
) > "$TEST_TMP/node-valid.out"
grep -q '^stop gost$' "$TEST_TMP/systemctl.calls"
grep -q '^start gost$' "$TEST_TMP/systemctl.calls"
grep -q 'existing-config' "$TEST_TMP/node/config.json"
test ! -e "$TEST_TMP/node/gost.bak"
echo 'PASS local node update preserves configuration and removes backup on success'

# Node update rolls back when the new binary crashes on startup.
printf '#!%s\necho old-version\n' "$BASH" > "$TEST_TMP/node/gost"
printf '#!%s\necho new-version\n' "$BASH" > "$TEST_TMP/new-gost"
: > "$TEST_TMP/systemctl.calls"
if "$BASH" > "$TEST_TMP/node-rollback.out" 2>&1 <<'ROLLBACK'; then
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  BINARY_FILE="$TEST_TMP/new-gost"
  systemctl() {
    printf '%s\n' "$*" >> "$TEST_TMP/systemctl.calls"
    if [[ "$*" == "is-active --quiet gost" ]]; then
      local count_file="$TEST_TMP/active-checks"
      local n=0
      [[ -f "$count_file" ]] && n=$(cat "$count_file")
      n=$((n + 1))
      printf '%s' "$n" > "$count_file"
      ((n == 1)) && return 3
      return 0
    fi
    if [[ "$1" == "show" ]]; then echo 0; fi
    return 0
  }
  update_gost
ROLLBACK
  echo 'FAIL crashed node update should fail after rollback' >&2; exit 1
fi
grep -q 'old-version' "$TEST_TMP/node/gost"
test ! -e "$TEST_TMP/node/gost.bak"
echo 'PASS crashed node update rolls back to previous binary'

# Node update also rolls back when systemd NRestarts > 0 even if is-active is true.
printf '#!%s\necho old-version\n' "$BASH" > "$TEST_TMP/node/gost"
rm -f "$TEST_TMP/active-checks"
if "$BASH" > "$TEST_TMP/node-restart-rollback.out" 2>&1 <<'RESTART_ROLLBACK'; then
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  BINARY_FILE="$TEST_TMP/new-gost"
  systemctl() {
    if [[ "$1" == "show" ]]; then
      local count_file="$TEST_TMP/restart-checks"
      local n=0
      [[ -f "$count_file" ]] && n=$(cat "$count_file")
      n=$((n + 1))
      printf '%s' "$n" > "$count_file"
      ((n == 1)) && echo 1 || echo 0
      return 0
    fi
    return 0
  }
  update_gost
RESTART_ROLLBACK
  echo 'FAIL restarting node update should fail after rollback' >&2; exit 1
fi
grep -q 'old-version' "$TEST_TMP/node/gost"
test ! -e "$TEST_TMP/node/gost.bak"
echo 'PASS restarting node update rolls back to previous binary'

# Backup failure happens before systemctl stop, leaving old service running.
printf '#!%s\necho old-version\n' "$BASH" > "$TEST_TMP/node/gost"
: > "$TEST_TMP/backup-fail-systemctl.calls"
if "$BASH" > "$TEST_TMP/node-backup-fail.out" 2>&1 <<'BACKUP_FAIL'; then
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  BINARY_FILE="$TEST_TMP/new-gost"
  cp() { return 1; }
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/backup-fail-systemctl.calls"; return 0; }
  update_gost
BACKUP_FAIL
  echo 'FAIL backup failure should abort update' >&2; exit 1
fi
test ! -s "$TEST_TMP/backup-fail-systemctl.calls"
test ! -e "$TEST_TMP/node/gost.bak"
grep -q 'old-version' "$TEST_TMP/node/gost"
echo 'PASS backup failure does not stop original node service'

# Replacement (mv) failure after partial write triggers rollback and restores old service.
printf '#!%s\necho old-version\n' "$BASH" > "$TEST_TMP/node/gost"
if "$BASH" > "$TEST_TMP/node-mv-fail.out" 2>&1 <<'MV_FAIL'; then
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  BINARY_FILE="$TEST_TMP/new-gost"
  mv() {
    if [[ "${1:-}" == *"gost.new" ]]; then
      printf 'corrupted' > "${2}"
      return 1
    fi
    command mv "$@"
  }
  systemctl() { [[ "$1" == "show" ]] && echo 0; return 0; }
  update_gost
MV_FAIL
  echo 'FAIL mv failure should fail after rollback' >&2; exit 1
fi
grep -q 'old-version' "$TEST_TMP/node/gost"
test ! -e "$TEST_TMP/node/gost.bak"
echo 'PASS binary replacement failure rolls back and restores old binary'

# Double mv failure (both replace and rollback mv fail) still restarts intact old binary.
printf '#!%s\necho old-version\n' "$BASH" > "$TEST_TMP/node/gost"
: > "$TEST_TMP/double-mv-systemctl.calls"
if "$BASH" > "$TEST_TMP/node-double-mv-fail.out" 2>&1 <<'DOUBLE_MV_FAIL'; then
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  BINARY_FILE="$TEST_TMP/new-gost"
  mv() { return 1; }
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/double-mv-systemctl.calls"; [[ "$1" == "show" ]] && echo 0; return 0; }
  update_gost
DOUBLE_MV_FAIL
  echo 'FAIL double mv failure should return non-zero' >&2; exit 1
fi
grep -q '^start gost$' "$TEST_TMP/double-mv-systemctl.calls"
grep -q 'old-version' "$TEST_TMP/node/gost"
test -s "$TEST_TMP/node/gost.bak"
rm -f "$TEST_TMP/node/gost.bak"
echo 'PASS double mv failure restarts intact old binary and preserves backup'

# Unreadable NRestarts fails health confirmation and triggers rollback.
printf '#!%s\necho old-version\n' "$BASH" > "$TEST_TMP/node/gost"
if "$BASH" > "$TEST_TMP/node-nrestarts-fail.out" 2>&1 <<'NRESTARTS_FAIL'; then
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  BINARY_FILE="$TEST_TMP/new-gost"
  systemctl() {
    if [[ "$1" == "show" ]]; then
      local count_file="$TEST_TMP/show-fail-checks"
      local n=0
      [[ -f "$count_file" ]] && n=$(cat "$count_file")
      n=$((n + 1))
      printf '%s' "$n" > "$count_file"
      ((n == 1)) && return 1
      echo 0
      return 0
    fi
    return 0
  }
  update_gost
NRESTARTS_FAIL
  echo 'FAIL unreadable NRestarts should trigger rollback' >&2; exit 1
fi
grep -q 'old-version' "$TEST_TMP/node/gost"
test ! -e "$TEST_TMP/node/gost.bak"
echo 'PASS unreadable NRestarts fails closed and rolls back'

# Fresh node install writes config/service and must never invoke uninstall_gost.
test "$(grep -c '^install_gost()' "$TEST_TMP/package/install.sh")" = 1
test "$(grep -c '^uninstall_gost()' "$TEST_TMP/package/install.sh")" = 1
(
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node-fresh"
  SERVICE_FILE="$TEST_TMP/node-fresh.service"
  SERVER_ADDR="https://panel.example.com"
  SECRET="fresh-secret-123"
  BINARY_FILE="$TEST_TMP/good-gost"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/fresh-systemctl.calls"; [[ "$1" == "show" ]] && echo 0; return 0; }
  printf 'y\n' | install_gost
) > "$TEST_TMP/node-fresh.out"
test -s "$TEST_TMP/node-fresh/gost"
cmp -s "$TEST_TMP/node-fresh/config.json" <(printf '{"addr":"https://panel.example.com","secret":"fresh-secret-123"}\n')
grep -q '"services":\[\]' "$TEST_TMP/node-fresh/gost.json"
grep -q "ExecStart=$TEST_TMP/node-fresh/gost" "$TEST_TMP/node-fresh.service"
grep -q '^daemon-reload$' "$TEST_TMP/fresh-systemctl.calls"
grep -q '^enable gost$' "$TEST_TMP/fresh-systemctl.calls"
grep -q '^start gost$' "$TEST_TMP/fresh-systemctl.calls"
! grep -q '^disable --now gost$' "$TEST_TMP/fresh-systemctl.calls"
if [[ $EUID -eq 0 ]]; then
  (
    source "$TEST_TMP/package/install.sh"
    INSTALL_DIR="$TEST_TMP/node-cli"
    SERVICE_FILE="$TEST_TMP/node-cli.service"
    NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
    systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/cli-systemctl.calls"; [[ "$1" == "show" ]] && echo 0; return 0; }
    printf 'y\n' | main -b "$TEST_TMP/good-gost" -a 'https://panel.example.com' -s 'fresh-secret-123'
  ) > "$TEST_TMP/node-cli.out"
  cmp -s "$TEST_TMP/node-cli/config.json" <(printf '{"addr":"https://panel.example.com","secret":"fresh-secret-123"}\n')
  ! grep -q '^disable --now gost$' "$TEST_TMP/cli-systemctl.calls"
fi
echo 'PASS fresh node install writes config and does not trigger uninstall'

# Re-installing over an existing directory preserves gost.json.
printf '{"services":[{"name":"keep-me"}]}\n' > "$TEST_TMP/node-fresh/gost.json"
(
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node-fresh"
  SERVICE_FILE="$TEST_TMP/node-fresh.service"
  SERVER_ADDR="https://panel.example.com"
  SECRET="fresh-secret-123"
  BINARY_FILE="$TEST_TMP/good-gost"
  NODE_HEALTH_CHECKS=1 NODE_HEALTH_INTERVAL=0
  systemctl() { [[ "$1" == "show" ]] && echo 0; return 0; }
  printf 'y\n' | install_gost
) > "$TEST_TMP/node-reinstall.out"
grep -q 'keep-me' "$TEST_TMP/node-fresh/gost.json"
echo 'PASS re-install preserves existing gost.json'

# Uninstall respects confirmation (n preserves, y removes).
: > "$TEST_TMP/uninstall-systemctl.calls"
(
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node-fresh"
  SERVICE_FILE="$TEST_TMP/node-fresh.service"
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/uninstall-systemctl.calls"; return 0; }
  printf 'n\n' | uninstall_gost
) > "$TEST_TMP/node-uninstall-no.out"
test -s "$TEST_TMP/node-fresh/config.json"
(
  source "$TEST_TMP/package/install.sh"
  INSTALL_DIR="$TEST_TMP/node-fresh"
  SERVICE_FILE="$TEST_TMP/node-fresh.service"
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/uninstall-systemctl.calls"; return 0; }
  printf 'y\n' | uninstall_gost
) > "$TEST_TMP/node-uninstall-yes.out"
test ! -e "$TEST_TMP/node-fresh"
test ! -e "$TEST_TMP/node-fresh.service"
grep -q '^disable --now gost$' "$TEST_TMP/uninstall-systemctl.calls"
echo 'PASS node uninstall respects confirmation and removes service/config'
echo "Smoke-test output: $TEST_TMP"
