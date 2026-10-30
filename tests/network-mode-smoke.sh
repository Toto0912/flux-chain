#!/bin/bash
# Read-only network preflight tests. All host observations below are mocked.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_TMP=$(mktemp -d)
trap 'rm -rf -- "$TEST_TMP"' EXIT
source "$ROOT/panel_install.sh"

EXISTING=missing
RA_VALUE=1
ROUTES='default via fe80::1 dev ens3 proto ra metric 1024 expires 89sec'
ADDRESSES='2: ens3 inet6 2001:db8::2/64 scope global dynamic proto kernel_ra'
IP_FAIL=0
docker() {
  printf '%s\n' "$*" >> "$TEST_TMP/docker.calls"
  if [[ "$*" == 'network inspect gost-network '* ]]; then
    [[ "$EXISTING" != missing ]] || return 1
    printf '%s\n' "$EXISTING"
  else
    echo 'FAIL unexpected Docker mutation' >&2; return 99
  fi
}
ip() {
  printf '%s\n' "$*" >> "$TEST_TMP/ip.calls"
  [[ "$IP_FAIL" == 0 ]] || return 1
  case "$*" in
    '-6 route show default') printf '%s\n' "$ROUTES" ;;
    '-o -6 addr show scope global') printf '%s\n' "$ADDRESSES" ;;
    *) echo 'FAIL unexpected ip command' >&2; return 99 ;;
  esac
}
read_interface_accept_ra() {
  printf '%s\n' "$1" >> "$TEST_TMP/ra.calls"
  printf '%s\n' "$RA_VALUE"
}
sysctl() { echo 'FAIL sysctl mutation attempted' >&2; return 99; }

unset PANEL_IPV6
[[ "$(get_docker_compose_file)" == "$ROOT/docker-compose-v4.yml" ]]
validate_panel_network
[[ ! -e "$TEST_TMP/ip.calls" && ! -e "$TEST_TMP/ra.calls" ]]
echo 'PASS fresh default uses IPv4 without probing or altering host IPv6'
PANEL_IPV6=auto
[[ "$(get_docker_compose_file)" == "$ROOT/docker-compose-v4.yml" ]]
validate_panel_network
echo 'PASS legacy auto also selects IPv4 even on an RA-dependent IPv6 host'
PANEL_IPV6=invalid
if get_docker_compose_file >/dev/null 2>&1; then echo 'FAIL invalid mode accepted'; exit 1; fi
echo 'PASS invalid network mode rejected'

PANEL_IPV6=1
if validate_panel_network >"$TEST_TMP/error" 2>&1; then echo 'FAIL unsafe RA host accepted'; exit 1; fi
grep -q 'ens3' "$TEST_TMP/error"
grep -q 'accept_ra=1' "$TEST_TMP/error"
echo 'PASS explicit IPv6 rejects the forwarding/RA conflict on the actual interface'
ROUTES=''
if validate_panel_network >/dev/null 2>&1; then echo 'FAIL already-expired RA route overlooked'; exit 1; fi
echo 'PASS dynamic IPv6 address detects an already-expired default route'
RA_VALUE=2
validate_panel_network
echo 'PASS explicit IPv6 accepts administrator-configured RA forwarding compatibility'
RA_VALUE=1
ADDRESSES='2: ens3 inet6 2001:db8::2/64 scope global'
ROUTES='default via 2001:db8::1 dev ens3 proto static metric 1024'
validate_panel_network
echo 'PASS static IPv6 gateway does not require a host RA change'
IP_FAIL=1
if validate_panel_network >/dev/null 2>&1; then echo 'FAIL failed safety inspection accepted'; exit 1; fi
IP_FAIL=0
echo 'PASS explicit IPv6 fails closed when network inspection fails'

EXISTING=true
PANEL_IPV6=0
if validate_panel_network >"$TEST_TMP/error" 2>&1; then echo 'FAIL implicit IPv6 network migration accepted'; exit 1; fi
grep -q 'PANEL_IPV6=1' "$TEST_TMP/error"
PANEL_IPV6=1
validate_panel_network
EXISTING=false
validate_error=0
validate_panel_network >/dev/null 2>&1 || validate_error=$?
[[ "$validate_error" != 0 ]]
PANEL_IPV6=0
validate_panel_network
EXISTING=unrecognized
if validate_panel_network >/dev/null 2>&1; then echo 'FAIL unknown network state accepted'; exit 1; fi
echo 'PASS existing networks are retained only with matching mode; implicit migrations stop'
! grep -Eq 'network (create|rm)|compose| up | down ' "$TEST_TMP/docker.calls"
echo 'ALL network-mode checks passed without Docker or host network mutations'
