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
  printf '#!%s\necho gost-test\n' "$BASH" > "$TEST_TMP/good-gost"
  BINARY_FILE="$TEST_TMP/good-gost"
  systemctl() { printf '%s\n' "$*" >> "$TEST_TMP/systemctl.calls"; }
  update_gost
) > "$TEST_TMP/node-valid.out"
grep -q '^stop gost$' "$TEST_TMP/systemctl.calls"
grep -q '^start gost$' "$TEST_TMP/systemctl.calls"
grep -q 'existing-config' "$TEST_TMP/node/config.json"
echo 'PASS local node update preserves configuration'
echo "Smoke-test output: $TEST_TMP"
