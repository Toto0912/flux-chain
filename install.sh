#!/bin/bash
# 自动获取同版本的节点程序，也支持 -b 指定离线文件。
RELEASE_VERSION="1.0.0"
RELEASE_URL="https://github.com/Toto0912/flux-chain/releases/download/v$RELEASE_VERSION"
set -e
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/etc/gost"
SERVICE_FILE="/etc/systemd/system/gost.service"
NODE_DOWNLOAD_DIR=""

usage() {
  echo "FluxChain v1.0.0 节点安装脚本
用法: sudo bash install.sh [-a 面板地址 -s 节点密钥] [-b 本地节点文件] [-u]"
  echo "-u 更新节点并保留现有配置；不提供参数时显示菜单。"
}

cleanup_node_download() {
  # 仅清理由本脚本创建的下载目录，不删除手动指定的本地节点文件。
  case "${NODE_DOWNLOAD_DIR:-}" in
    */fluxchain-node.*)
      [[ -d "$NODE_DOWNLOAD_DIR" && ! -L "$NODE_DOWNLOAD_DIR" ]] && rm -rf -- "$NODE_DOWNLOAD_DIR" ;;
  esac
  unset NODE_DOWNLOAD_DIR
  return 0
}

download_node_file() {
  local request_timeout="${3:-${NODE_DOWNLOAD_TIMEOUT:-300}}" status=0
  if [[ ! "$request_timeout" =~ ^[0-9]+$ ]] || ((request_timeout < 10 || request_timeout > 3600)); then
    echo "NODE_DOWNLOAD_TIMEOUT 必须为 10～3600 秒。" >&2
    return 1
  fi
  echo "下载：${1##*/}（单次限时 ${request_timeout} 秒，失败最多重试一次）" >&2
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --show-error --progress-bar \
      --connect-timeout 15 --max-time "$request_timeout" \
      --speed-limit 1024 --speed-time 30 \
      --retry 1 --retry-delay 2 --retry-max-time "$((request_timeout * 2 + 15))" \
      --output "$2" "$1" || status=$?
  elif command -v wget >/dev/null 2>&1; then
    if ! command -v timeout >/dev/null 2>&1; then
      echo "wget 下载需要 coreutils 提供的 timeout，或请安装 curl。" >&2
      return 1
    fi
    timeout --signal=TERM --kill-after=5 "$((request_timeout * 2 + 15))" \
      wget --tries=2 --dns-timeout=15 --connect-timeout=15 --read-timeout=30 \
      --progress=bar:force:noscroll -O "$2" "$1" || status=$?
  else
    echo "在线安装需要 curl 或 wget。" >&2
    return 1
  fi
  if ((status != 0)); then
    echo "下载失败（退出码 $status）：$1" >&2
    echo "请检查该节点到 GitHub/Release 附件服务器的连接，或使用 -b 指定离线程序。" >&2
    return "$status"
  fi
}

find_local_binary() {
  if [[ -n "${BINARY_FILE:-}" ]]; then
    [[ -s "$BINARY_FILE" ]] || { echo "找不到本地节点程序：$BINARY_FILE" >&2; return 1; }
    echo "使用指定节点程序：$BINARY_FILE"
    return 0
  fi
  local arch candidate
  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) echo "请用 -b 指定本机架构的 Linux 节点程序。" >&2; return 1 ;;
  esac
  for candidate in "$SCRIPT_DIR/gost-linux-$arch" "$SCRIPT_DIR/artifacts/gost-linux-$arch" "$SCRIPT_DIR/gost"; do
    if [[ -s "$candidate" ]]; then BINARY_FILE="$candidate"; echo "使用本地节点程序：$BINARY_FILE"; return 0; fi
  done
  local download_dir expected
  download_dir=$(mktemp -d -t fluxchain-node.XXXXXX) || return 1
  NODE_DOWNLOAD_DIR="$download_dir"
  echo "[1/4] 获取 FluxChain v$RELEASE_VERSION 节点（Linux/$arch）..."
  echo "发布地址：$RELEASE_URL"
  if download_node_file "$RELEASE_URL/SHA256SUMS" "$download_dir/SHA256SUMS" 45 &&
     download_node_file "$RELEASE_URL/gost-linux-$arch" "$download_dir/gost-linux-$arch"; then
    echo "正在验证节点程序 SHA-256..."
    expected=$(awk -v file="gost-linux-$arch" '$2 == file { print $1 }' "$download_dir/SHA256SUMS")
    if [[ "$expected" =~ ^[0-9a-f]{64}$ ]] &&
       (cd "$download_dir" && printf '%s  gost-linux-%s\n' "$expected" "$arch" | sha256sum -c -); then
      BINARY_FILE="$download_dir/gost-linux-$arch"
      NODE_DOWNLOAD_DIR="$download_dir"
      return
    fi
  fi
  cleanup_node_download
  echo "节点下载或校验失败；安装未继续，原有服务未停止。" >&2
  echo "离线安装：将本项目对应架构的 gost-linux-$arch 上传后，使用 -b 指定文件。" >&2
  return 1
}

stage_binary() {
  echo "准备节点程序；如果需要下载，将显示下载进度。"
  find_local_binary || return 1
  echo "[2/4] 校验节点程序是否可执行..."
  mkdir -p "$INSTALL_DIR" || return 1
  # 验证失败时不停止原服务。
  install -m 755 "$BINARY_FILE" "$INSTALL_DIR/gost.new" || { cleanup_node_download; return 1; }
  if ! "$INSTALL_DIR/gost.new" -V; then
    cleanup_node_download
    echo "节点程序校验失败，请确认 CPU 架构及文件是否完整；原有服务未停止。" >&2
    return 1
  fi
  if [[ -n "${NODE_DOWNLOAD_DIR:-}" ]]; then
    cleanup_node_download
    unset BINARY_FILE
  fi
}

install_gost() {
  if [[ -z "${SERVER_ADDR:-}" ]]; then
    read -r -p "面板地址（主机:端口）: " SERVER_ADDR || { echo "未读取到面板地址。" >&2; return 1; }
  fi
  if [[ -z "${SECRET:-}" ]]; then
    read -r -s -p "节点密钥（输入不回显）: " SECRET || { printf '\n'; echo "未读取到节点密钥。" >&2; return 1; }
    printf '\n'
  fi
  [[ -n "$SERVER_ADDR" && -n "$SECRET" ]] || { echo "面板地址和节点密钥不能为空" >&2; return 1; }
  if [[ "$SERVER_ADDR$SECRET" == *[\"\\]* || "$SERVER_ADDR$SECRET" == *$'\n'* || "$SERVER_ADDR$SECRET" == *$'\r'* ]]; then
    echo "面板地址或密钥包含不支持的字符" >&2; return 1
  fi
  stage_binary || return 1
  echo "[3/4] 写入配置并注册 systemd 服务..."
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
  systemctl daemon-reload || return 1
  systemctl enable gost || return 1
  echo "[4/4] 启动节点服务..."
  if ! systemctl start gost || ! systemctl is-active --quiet gost; then
    echo "节点服务未能启动，请执行：journalctl -u gost -n 80 --no-pager" >&2
    return 1
  fi
  if ! command -v tcpkill >/dev/null 2>&1; then
    echo "提示：未安装可选的 tcpkill；强制断开已有 TCP 连接的能力可能受限，可自行安装 dsniff。"
  fi
  echo "安装完成，配置目录：$INSTALL_DIR；查看日志：journalctl -u gost -f"
}

update_gost() {
  [[ -s "$INSTALL_DIR/config.json" ]] || { echo "节点尚未安装" >&2; return 1; }
  stage_binary || return 1
  echo "[3/4] 替换节点程序，保留现有配置..."
  systemctl stop gost || return 1
  mv "$INSTALL_DIR/gost.new" "$INSTALL_DIR/gost" || return 1
  echo "[4/4] 重新启动节点服务..."
  if ! systemctl start gost || ! systemctl is-active --quiet gost; then
    echo "节点服务未能启动，请执行：journalctl -u gost -n 80 --no-pager" >&2
    return 1
  fi
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
  local update_only=0 opt choice
  OPTIND=1
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
  echo "FluxChain v$RELEASE_VERSION 节点管理"
  echo "1. 安装节点  2. 更新节点  3. 卸载  4. 退出"
  read -r -p "请选择: " choice || { echo "未读取到选项，请下载脚本后在终端运行，或使用 -a/-s 参数。" >&2; return 1; }
  choice="${choice//[[:space:]]/}"
  case "$choice" in
    1) install_gost ;;
    2) update_gost ;;
    3) uninstall_gost ;;
    4) return ;;
    *) usage; return 1 ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  trap cleanup_node_download EXIT
  trap 'printf "\n安装已中断。\n" >&2; exit 130' INT
  trap 'printf "\n安装已终止。\n" >&2; exit 143' TERM
  main "$@"
fi
