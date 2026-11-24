#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

if [[ "${1:-}" == "--uninstall" || "${1:-}" == "-u" ]]; then
  [[ $(id -u) == 0 ]] || fail '请使用 root 或 sudo 运行。'
  [[ $(uname -s) == Linux ]] || fail 'UDP 回显服务需要 Linux。'
  command -v systemctl >/dev/null || fail '缺少 systemctl。'
  systemctl disable --now fluxchain-udp-echo.service 2>/dev/null || true
  rm -rf /etc/systemd/system/fluxchain-udp-echo.service /usr/local/bin/fluxchain-udp-echo /usr/local/bin/fluxchain-udp-echo.new /etc/fluxchain-udp-echo
  systemctl daemon-reload
  systemctl reset-failed fluxchain-udp-echo.service 2>/dev/null || true
  printf 'FluxChain UDP 回显服务已卸载。\n'
  exit 0
fi

base_url=${1:-}
base_url=${base_url%/}
echo_key=${2:-}
echo_port=${3:-19302}
[[ "$base_url" == http://* || "$base_url" == https://* ]] || fail '请提供安装命令中的面板下载地址。'
[[ "$echo_key" =~ ^[a-fA-F0-9]{64}$ ]] || fail '请提供面板生成的 64 位十六进制 UDP 回显密钥。'
[[ "$echo_port" =~ ^[0-9]+$ ]] && (( echo_port >= 1 && echo_port <= 65535 )) || fail 'UDP 回显端口必须为 1 到 65535 之间的整数。'
[[ $(id -u) == 0 ]] || fail '请使用 root 或 sudo 运行。'
[[ $(uname -s) == Linux ]] || fail 'UDP 回显服务需要 Linux。'

case $(uname -m) in
  x86_64) arch=amd64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) fail 'UDP 回显服务支持 amd64 和 arm64。' ;;
esac

for dependency in curl sha256sum systemctl; do
  command -v "$dependency" >/dev/null || fail "缺少 $dependency。"
done
systemctl show --property=Version --value >/dev/null 2>&1 || fail '请在运行 systemd 的目标服务器上安装。'

binary="fluxchain-udp-echo-linux-$arch"
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT

download() {
  curl -fsSL --retry 2 --connect-timeout 10 --max-time 90 "$base_url/$1" -o "$tmp_dir/$1"
}
download SHA256SUMS
download "$binary"
checksum=$(awk -v binary="$binary" '$2 == binary {print $1; exit}' "$tmp_dir/SHA256SUMS")
[[ "$checksum" =~ ^[a-fA-F0-9]{64}$ ]] || fail '校验文件缺少本机架构的记录。'
(cd "$tmp_dir" && printf '%s  %s\n' "$checksum" "$binary" | sha256sum -c -)

cat > "$tmp_dir/fluxchain-udp-echo.service" <<'UNIT'
[Unit]
Description=FluxChain UDP echo
After=network.target

[Service]
Type=notify
EnvironmentFile=/etc/fluxchain-udp-echo/env
ExecStart=/usr/local/bin/fluxchain-udp-echo
TimeoutStartSec=10s
Restart=on-failure
RestartSec=2s
DynamicUser=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6

[Install]
WantedBy=multi-user.target
UNIT

install -d -m 0700 /etc/fluxchain-udp-echo
(umask 077 && printf 'FLUXCHAIN_UDP_ECHO_KEY=%s\nFLUXCHAIN_UDP_ECHO_PORT=%s\n' "$echo_key" "$echo_port" > "$tmp_dir/env")
install -m 0600 "$tmp_dir/env" /etc/fluxchain-udp-echo/env
install -D -m 0755 "$tmp_dir/$binary" /usr/local/bin/fluxchain-udp-echo.new
mv -f /usr/local/bin/fluxchain-udp-echo.new /usr/local/bin/fluxchain-udp-echo
install -D -m 0644 "$tmp_dir/fluxchain-udp-echo.service" /etc/systemd/system/fluxchain-udp-echo.service
systemctl daemon-reload
systemctl restart fluxchain-udp-echo.service || fail "启动失败，请检查 ${echo_port}/UDP 是否被占用，并运行 journalctl -u fluxchain-udp-echo -n 30。"
systemctl enable fluxchain-udp-echo.service
printf 'FluxChain UDP 回显服务已启动，监听 %s/UDP。\n' "$echo_port"
