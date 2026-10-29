#!/bin/bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_TMP=$(mktemp -d)
trap 'rm -rf -- "$TEST_TMP"' EXIT
mkdir -p "$TEST_TMP/nodes" "$TEST_TMP/release" "$TEST_TMP/site"
for arch in amd64 arm64; do
  printf '#!%s\necho "FluxChain Node 1.0.0"\n' "$BASH" > "$TEST_TMP/nodes/gost-linux-$arch"
done
python3 "$ROOT/scripts/package_release.py" --nodes "$TEST_TMP/nodes" --output "$TEST_TMP/release"
VERSION=$(cat "$ROOT/VERSION")
# Mock the image stream, while exercising real downloads/checksum/extraction.
printf 'image fixture\n' > "$TEST_TMP/release/fluxchain-images-amd64-v$VERSION.tar.gz"
(cd "$TEST_TMP/release" && sha256sum fluxchain-images-*.tar.gz >> SHA256SUMS)
cp "$ROOT/panel_install.sh" "$ROOT/install.sh" "$TEST_TMP/site/"
export PANEL_IPV6=0 PANEL_IMAGE_MODE=pull
source "$TEST_TMP/site/panel_install.sh"
download_file() { printf '%s\n' "$1" >> "$TEST_TMP/downloads.calls"; cp "$TEST_TMP/release/${1##*/}" "$2"; }
docker() {
  printf '%s\n' "$*" >> "$TEST_TMP/docker.calls"
  if [[ "$*" == 'pull mysql backend frontend' && "${MOCK_PULL_FAIL:-0}" == 1 ]]; then return 17; fi
}
DOCKER_CMD=docker
prepare_local_files
prepare_local_images
test -s "$SCRIPT_DIR/gost.sql"
test -s "$SCRIPT_DIR/springboot-backend/src/main/resources/db/upgrade_forward_chain.sql"
grep -q '^pull mysql backend frontend$' "$TEST_TMP/docker.calls"
! grep -q '^build ' "$TEST_TMP/docker.calls"
echo 'PASS default online install downloads verified resources and pulls prebuilt images'
cmp "$ROOT/gost.sql" "$SCRIPT_DIR/gost.sql"
printf '%s\n' '-- existing backup fixture; must remain byte-identical' > "$SCRIPT_DIR/gost.sql"
cp "$SCRIPT_DIR/gost.sql" "$TEST_TMP/backup.before"
prepare_local_files
prepare_local_files
cmp "$SCRIPT_DIR/gost.sql" "$TEST_TMP/backup.before"
echo 'PASS existing SQL is preserved on initial preparation and subsequent updates'
: > "$SCRIPT_DIR/gost.sql"
cp "$TEST_TMP/downloads.calls" "$TEST_TMP/downloads.before"
if prepare_local_files; then echo 'FAIL empty backup was accepted'; exit 1; fi
test ! -s "$SCRIPT_DIR/gost.sql"
cmp "$TEST_TMP/downloads.calls" "$TEST_TMP/downloads.before"
cmp "$ROOT/gost.sql" "$TEST_TMP/site/gost.sql" >/dev/null 2>&1 && { echo 'FAIL empty backup was overwritten'; exit 1; }
cp "$TEST_TMP/backup.before" "$SCRIPT_DIR/gost.sql"
echo 'PASS an empty existing SQL stops installation without downloading or replacing it'


: > "$TEST_TMP/docker.calls"
MOCK_PULL_FAIL=1 prepare_local_images
grep -q '^load -i ' "$TEST_TMP/docker.calls"
echo 'PASS registry failure falls back to the matching verified Release image archive'

cp "$TEST_TMP/release/fluxchain-panel-v$VERSION.tar.gz" "$TEST_TMP/good.tar.gz"
printf 'corruption' >> "$TEST_TMP/release/fluxchain-panel-v$VERSION.tar.gz"
if prepare_local_files; then echo 'FAIL corrupt panel resource accepted'; exit 1; fi
mv "$TEST_TMP/good.tar.gz" "$TEST_TMP/release/fluxchain-panel-v$VERSION.tar.gz"
echo 'PASS corrupt deployment resource is rejected'

source "$TEST_TMP/site/install.sh"
download_node_file() { cp "$TEST_TMP/release/${1##*/}" "$2"; }
INSTALL_DIR="$TEST_TMP/node-install"
stage_binary
"$INSTALL_DIR/gost.new" -V | grep -q 'FluxChain Node 1.0.0'
echo 'PASS node downloads the matching architecture and validates before installation'

printf 'corruption' >> "$TEST_TMP/release/gost-linux-amd64"
printf 'corruption' >> "$TEST_TMP/release/gost-linux-arm64"
unset BINARY_FILE
if find_local_binary; then echo 'FAIL corrupt node accepted'; exit 1; fi
echo 'PASS node checksum failure stops installation'

(cd "$TEST_TMP/release" && sha256sum -c SHA256SUMS --ignore-missing >/dev/null 2>&1) && { echo 'FAIL fixture corruption was not detected'; exit 1; }
echo 'PASS release checksum manifest detects modified assets'

echo 'ALL online installation checks passed'
