#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
arch="${1:-amd64}"
case "$arch" in amd64|arm64) ;; *) echo "用法: bash build_node.sh [amd64|arm64]" >&2; exit 1 ;; esac
mkdir -p "$SCRIPT_DIR/artifacts"
if command -v go >/dev/null 2>&1; then
  cd "$SCRIPT_DIR/go-gost"
  CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -mod=readonly -trimpath -o "$SCRIPT_DIR/artifacts/gost-linux-$arch" .
else
  command -v docker >/dev/null || { echo "请安装 Go 1.23.4+ 或 Docker 后编译节点" >&2; exit 1; }
  docker run --rm -e CGO_ENABLED=0 -e GOOS=linux -e GOARCH="$arch" \
    -v "$SCRIPT_DIR/go-gost:/src" -v "$SCRIPT_DIR/artifacts:/out" -w /src \
    golang:1.23.4 go build -mod=readonly -trimpath -o "/out/gost-linux-$arch" .
fi
echo "节点程序已生成：$SCRIPT_DIR/artifacts/gost-linux-$arch"
