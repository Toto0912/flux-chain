#!/bin/bash
# 自动获取同版本的节点程序，也支持 -b 指定离线文件。
RELEASE_VERSION="1.0.0-beta1"
RELEASE_URL="https://github.com/Toto0912/flux-chain/releases/download/v$RELEASE_VERSION"
set -e
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/etc/gost"
SERVICE_FILE="/etc/systemd/system/gost.service"

usage() {
  echo "FluxChain v1.0.0-beta1 节点安装脚本
用法: sudo bash install.sh [-a 面板地址 -s 节点密钥] [-b 本地节点文件] [-u]"
  echo "-u 更新节点并保留现有配置；不提供参数时显示菜单。"
}

download_node_file() {
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --connect-timeout 15 --retry 2 --output "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --timeout=30 -O "$2" "$1"
  else
    echo "在线安装需要 curl 或 wget。" >&2; return 1
  fi
}

find_local_binary() {
  if [[ -n "${BINARY_FILE:-}" ]]; then
    [[ -s "$BINARY_FILE" ]] || { echo "找不到本地节点程序：$BINARY_FILE" >&2; return 1; }
    return
  fi
  local arch
  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) echo "请用 -b 指定本机架构的 Linux 节点程序。" >&2; return 1 ;;
  esac
  for candidate in "$SCRIPT_DIR/gost-linux-$arch" "$SCRIPT_DIR/artifacts/gost-linux-$arch" "$SCRIPT_DIR/gost"; do
    if [[ -s "$candidate" ]]; then BINARY_FILE="$candidate"; return; fi
  done
  local download_dir expected
  download_dir=$(mktemp -d)
  if download_node_file "$RELEASE_URL/SHA256SUMS" "$download_dir/SHA256SUMS" &&
     download_node_file "$RELEASE_URL/gost-linux-$arch" "$download_dir/gost-linux-$arch"; then
    expected=$(awk -v file="gost-linux-$arch" '$2 == file { print $1 }' "$download_dir/SHA256SUMS")
    if [[ "$expected" =~ ^[0-9a-f]{64}$ ]] &&
       (cd "$download_dir" && printf '%s  gost-linux-%s\n' "$expected" "$arch" | sha256sum -c -); then
      BINARY_FILE="$download_dir/gost-linux-$arch"
      NODE_DOWNLOAD_DIR="$download_dir"
      return
    fi
  fi
  rm -rf -- "$download_dir"
  echo "节点下载或校验失败；可用 -b 指定本项目的离线节点程序。" >&2
  return 1
}

stage_binary() {
  find_local_binary || return
  mkdir -p "$INSTALL_DIR"
  # 验证失败时不停止原服务。
  install -m 755 "$BINARY_FILE" "$INSTALL_DIR/gost.new"
  if ! "$INSTALL_DIR/gost.new" -V; then
    [[ -z "${NODE_DOWNLOAD_DIR:-}" ]] || rm -rf -- "$NODE_DOWNLOAD_DIR"
    return 1
  fi
  if [[ -n "${NODE_DOWNLOAD_DIR:-}" ]]; then
    rm -rf -- "$NODE_DOWNLOAD_DIR"
    unset NODE_DOWNLOAD_DIR BINARY_FILE
  fi
}

install_gost() {
  [[ -n "${SERVER_ADDR:-}" ]] || read -r -p "面板地址（主机:端口）: " SERVER_ADDR
  [[ -n "${SECRET:-}" ]] || read -r -p "节点密钥: " SECRET
  [[ -n "$SERVER_ADDR" && -n "$SECRET" ]] || { echo "面板地址和节点密钥不能为空" >&2; return 1; }
  if [[ "$SERVER_ADDR$SECRET" == *[\"\\]* || "$SERVER_ADDR$SECRET" == *$'\n'* || "$SERVER_ADDR$SECRET" == *$'\r'* ]]; then
    echo "面板地址或密钥包含不支持的字符" >&2; return 1
  fi
  stage_binary
  systemctl stop gost 2>/dev/null || true
  mv "$INSTALL_DIR/gost.new" "$INSTALL_DIR/gost"
  umask 077
  printf '{"addr":"%s","secret":"%s"}\n' "$SERVER_ADDR" "$SECRET" > "$INSTALL_DIR/config.json"
  if [[ ! -f "$INSTALL_DIR/gost.json" ]]; then
    printf '{"services":[],"chains":[],"authers":[]}\n' > "$INSTALL_DIR/gost.json"
  fi
  chmod 600 "$INSTALL_DIR"/*.json
  cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=FluxChain Node
After=network.target

[Service]
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/gost
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable gost
  systemctl start gost
  systemctl is-active --quiet gost
  if ! command -v tcpkill >/dev/null 2>&1; then
    echo "提示：未安装可选的 tcpkill；强制断开已有 TCP 连接的能力可能受限，可自行安装 dsniff。"
  fi
  echo "安装完成，配置目录：$INSTALL_DIR；查看日志：journalctl -u gost -f"
}

update_gost() {
  [[ -s "$INSTALL_DIR/config.json" ]] || { echo "节点尚未安装" >&2; return 1; }
  stage_binary
  systemctl stop gost
  mv "$INSTALL_DIR/gost.new" "$INSTALL_DIR/gost"
  systemctl start gost
  systemctl is-active --quiet gost
  echo "FluxChain 节点已更新，原配置已保留。"
}

uninstall_gost() {
  read -r -p "确认删除 gost 服务及 /etc/gost 内的配置吗？(y/N): " confirm
  [[ "$confirm" == y || "$confirm" == Y ]] || return 0
  systemctl disable --now gost 2>/dev/null || true
  rm -f "$SERVICE_FILE"
  rm -rf -- "$INSTALL_DIR"
  systemctl daemon-reload
}

main() {
  local update_only=0 opt
  while getopts "a:s:b:uh" opt; do
    case "$opt" in
      a) SERVER_ADDR="$OPTARG" ;;
      s) SECRET="$OPTARG" ;;
      b) BINARY_FILE="$OPTARG" ;;
      u) update_only=1 ;;
      h) usage; return ;;
      *) usage; return 1 ;;
    esac
  done
  [[ $EUID -eq 0 ]] || { echo "请使用 sudo 或 root 运行" >&2; return 1; }
  command -v systemctl >/dev/null || { echo "需要使用 systemd 的 Linux 服务器" >&2; return 1; }
  if [[ $update_only == 1 ]]; then update_gost; return; fi
  if [[ -n "${SERVER_ADDR:-}${SECRET:-}" ]]; then install_gost; return; fi
  echo "1. 安装节点  2. 更新节点  3. 卸载  4. 退出"
  read -r -p "请选择: " choice
  case "$choice" in
    1) install_gost ;;
    2) update_gost ;;
    3) uninstall_gost ;;
    4) return ;;
    *) usage; return 1 ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
