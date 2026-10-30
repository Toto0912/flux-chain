#!/bin/bash
set -e

# 解决 macOS 下 tr 可能出现的非法字节序列问题
export LANG=en_US.UTF-8
export LC_ALL=C



# 安装和更新仅使用 Toto0912/flux-chain 的匹配版本资源。
RELEASE_VERSION="1.0.1"
RELEASE_URL="https://github.com/Toto0912/flux-chain/releases/download/v$RELEASE_VERSION"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

get_docker_compose_file() {
  case "${PANEL_IPV6:-0}" in
    1) echo "$SCRIPT_DIR/docker-compose-v6.yml" ;;
    # Legacy "auto" now has the safe default too; host IPv6 is not opt-in.
    0|auto) echo "$SCRIPT_DIR/docker-compose-v4.yml" ;;
    *) echo "PANEL_IPV6 只能为 auto、0 或 1" >&2; return 1 ;;
  esac
}

read_interface_accept_ra() {
  cat "/proc/sys/net/ipv6/conf/$1/accept_ra"
}

validate_ipv6_bridge_safety() {
  local routes addresses interfaces iface value
  command -v ip >/dev/null 2>&1 || {
    echo "容器 IPv6 模式需要 iproute2 进行只读网络检查；未修改系统配置。" >&2
    return 1
  }
  routes=$(ip -6 route show default) || return 1
  addresses=$(ip -o -6 addr show scope global) || return 1
  # Include dynamic addresses so an already-expired RA route is caught too.
  interfaces=$({
    printf '%s\n' "$routes" | awk '/proto ra([[:space:]]|$)/ { for(i=1;i<NF;i++) if($i=="dev") print $(i+1) }'
    printf '%s\n' "$addresses" | awk '/dynamic|proto kernel_ra/ { name=$2; sub(/@.*/, "", name); print name }'
  } | sort -u)
  while IFS= read -r iface; do
    [[ -n "$iface" ]] || continue
    value=$(read_interface_accept_ra "$iface") || {
      echo "无法读取网卡 $iface 的 IPv6 RA 设置，未启用容器 IPv6。" >&2
      return 1
    }
    if [[ "$value" != 2 ]]; then
      echo "拒绝启用容器 IPv6：网卡 $iface 依赖路由器通告，但 accept_ra=$value。Docker 开启 IPv6 转发后可能导致公网默认路由丢失。" >&2
      echo "脚本不会修改宿主机网络参数。请使用默认 IPv4 容器内网，或由管理员自行确认 IPv6 网络配置后再显式启用。" >&2
      return 1
    fi
  done <<< "$interfaces"
}

validate_panel_network() {
  local compose_file wanted existing
  compose_file=$(get_docker_compose_file) || return 1
  wanted=false
  if [[ "$compose_file" == "$SCRIPT_DIR/docker-compose-v6.yml" ]]; then wanted=true; fi
  if existing=$(docker network inspect gost-network --format '{{.EnableIPv6}}' 2>/dev/null); then
    case "$existing" in
      true|false) ;;
      *) echo "无法确认现有 gost-network 的网络模式，已停止，未重建服务。" >&2; return 1 ;;
    esac
    if [[ "$existing" != "$wanted" ]]; then
      echo "现有 gost-network 的容器 IPv6=$existing，与本次选择 $wanted 不同，已停止，避免隐式重建网络。" >&2
      echo "已有 IPv6 部署可用 sudo env PANEL_IPV6=1 bash panel_install.sh update 保留原模式；改用 IPv4 内网请按 README 规划迁移并保留 .env 和数据卷。" >&2
      return 1
    fi
  fi
  if [[ "$wanted" == true ]]; then validate_ipv6_bridge_safety || return 1; fi
}

download_file() {
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --connect-timeout 15 --retry 2 --output "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --timeout=30 -O "$2" "$1"
  else
    echo "在线安装需要 curl 或 wget。" >&2; return 1
  fi
}

verify_release_file() {
  local release_dir="$1" filename="$2" expected
  expected=$(awk -v file="$filename" '$2 == file { print $1 }' "$release_dir/SHA256SUMS")
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "发布校验清单中缺少 $filename" >&2; return 1; }
  (cd "$release_dir" && printf '%s  %s\n' "$expected" "$filename" | sha256sum -c -)
}

fetch_release_file() {
  local filename="$1" destination="$2" release_tmp
  release_tmp=$(mktemp -d)
  if download_file "$RELEASE_URL/SHA256SUMS" "$release_tmp/SHA256SUMS" &&
     download_file "$RELEASE_URL/$filename" "$release_tmp/$filename" &&
     verify_release_file "$release_tmp" "$filename"; then
    mv "$release_tmp/$filename" "$destination"
    rm -rf -- "$release_tmp"
  else
    rm -rf -- "$release_tmp"
    echo "无法下载或校验 FluxChain v$RELEASE_VERSION 的 $filename；请确认版本已发布。" >&2
    return 1
  fi
}

prepare_local_files() {
  local compose_file resource_tmp resource
  if [[ -e "$SCRIPT_DIR/gost.sql" || -L "$SCRIPT_DIR/gost.sql" ]]; then
    [[ -f "$SCRIPT_DIR/gost.sql" && -r "$SCRIPT_DIR/gost.sql" && -s "$SCRIPT_DIR/gost.sql" ]] || {
      echo "已有 gost.sql 为空、不可读或不是普通文件；请检查备份，安装已停止，原文件未覆盖。" >&2
      return 1
    }
  fi
  case "${PANEL_IMAGE_MODE:-pull}" in
    pull)
      resource_tmp=$(mktemp -d)
      fetch_release_file "fluxchain-panel-v$RELEASE_VERSION.tar.gz" "$resource_tmp/panel.tar.gz" || { rm -rf -- "$resource_tmp"; return 1; }
      # 只读取固定资源，忽略归档中的其他路径；不覆盖 .env 或数据卷。
      for resource in docker-compose-v4.yml docker-compose-v6.yml gost.sql springboot-backend/src/main/resources/db/upgrade_forward_chain.sql; do
        if [[ "$resource" == gost.sql && ( -e "$SCRIPT_DIR/gost.sql" || -L "$SCRIPT_DIR/gost.sql" ) ]]; then
          echo "保留并使用安装目录已有的 gost.sql。"
          continue
        fi
        mkdir -p "$SCRIPT_DIR/$(dirname "$resource")"
        tar -xOzf "$resource_tmp/panel.tar.gz" "$resource" > "$resource_tmp/resource" || { rm -rf -- "$resource_tmp"; return 1; }
        [[ -s "$resource_tmp/resource" ]] || { rm -rf -- "$resource_tmp"; return 1; }
        mv "$resource_tmp/resource" "$SCRIPT_DIR/$resource"
      done
      rm -rf -- "$resource_tmp"
      ;;
    build|load) ;;
    *) echo "PANEL_IMAGE_MODE 只能为 pull、build 或 load" >&2; return 1 ;;
  esac
  compose_file=$(get_docker_compose_file)
  for resource in "$compose_file" "$SCRIPT_DIR/gost.sql" "$SCRIPT_DIR/springboot-backend/src/main/resources/db/upgrade_forward_chain.sql"; do
    [[ -s "$resource" ]] || { echo "缺少部署资源：$resource" >&2; return 1; }
  done
  cp "$compose_file" "$SCRIPT_DIR/docker-compose.yml"
  echo "使用配置：$(basename "$compose_file")"
}

prepare_local_images() {
  case "${PANEL_IMAGE_MODE:-pull}" in
    build)
      for resource in springboot-backend/Dockerfile vite-frontend/Dockerfile; do
        [[ -s "$SCRIPT_DIR/$resource" ]] || { echo "源码构建缺少 $resource" >&2; return 1; }
      done
      export BACKEND_IMAGE="fluxchain-backend:local" FRONTEND_IMAGE="fluxchain-frontend:local"
      $DOCKER_CMD config -q
      $DOCKER_CMD build backend frontend
      docker image inspect mysql:5.7 >/dev/null 2>&1 || docker pull mysql:5.7
      ;;
    pull|load)
      export BACKEND_IMAGE="ghcr.io/toto0912/flux-chain-backend:$RELEASE_VERSION"
      export FRONTEND_IMAGE="ghcr.io/toto0912/flux-chain-frontend:$RELEASE_VERSION"
      $DOCKER_CMD config -q
      if [[ "${PANEL_IMAGE_MODE:-pull}" == pull ]]; then
        echo "下载 FluxChain v$RELEASE_VERSION 面板镜像..."
        if ! $DOCKER_CMD pull mysql backend frontend; then
          echo "镜像仓库暂不可用，改用同版本 Release 镜像包..."
          fetch_release_file "fluxchain-images-amd64-v$RELEASE_VERSION.tar.gz" "$SCRIPT_DIR/images-download.tar.gz" || return 1
          docker load -i "$SCRIPT_DIR/images-download.tar.gz" || return 1
          rm -f "$SCRIPT_DIR/images-download.tar.gz"
        fi
      else
        [[ -s "$SCRIPT_DIR/images/panel-images.tar.gz" ]] || { echo "缺少 images/panel-images.tar.gz" >&2; return 1; }
        docker load -i "$SCRIPT_DIR/images/panel-images.tar.gz"
      fi
      ;;
  esac
  for image in mysql:5.7 "$BACKEND_IMAGE" "$FRONTEND_IMAGE"; do
    docker image inspect "$image" >/dev/null || return 1
  done
}

# 检查 docker-compose 或 docker compose 命令
check_docker() {
  if command -v docker &> /dev/null; then
    if docker compose version &> /dev/null; then
      DOCKER_CMD="docker compose"
    elif command -v docker-compose &> /dev/null; then
      DOCKER_CMD="docker-compose"
    else
      echo "错误：检测到 docker，但不支持 'docker compose' 命令。请安装 docker-compose 或更新 docker 版本。"
      exit 1
    fi
  else
    echo "错误：未检测到 docker 或 docker-compose 命令。请先安装 Docker。"
    exit 1
  fi
  docker info >/dev/null 2>&1 || { echo "Docker 服务未启动或当前用户无权访问" >&2; return 1; }
  echo "检测到 Docker 命令：$DOCKER_CMD"
}

# 显示菜单
show_menu() {
  echo "==============================================="
  echo "          FluxChain v1.0.1 面板管理脚本"
  echo "==============================================="
  echo "请选择操作："
  echo "1. 安装面板"
  echo "2. 更新面板"
  echo "3. 卸载面板"
  echo "4. 导出备份"
  echo "5. 退出"
  echo "==============================================="
}

generate_random() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c16
}

# 获取用户输入的配置参数
get_config_params() {
  echo "🔧 请输入配置参数："



  read -p "前端端口（默认 6366）: " FRONTEND_PORT
  FRONTEND_PORT=${FRONTEND_PORT:-6366}

  read -p "后端端口（默认 6365）: " BACKEND_PORT
  BACKEND_PORT=${BACKEND_PORT:-6365}

  DB_NAME=$(generate_random)
  DB_USER=$(generate_random)
  DB_PASSWORD=$(generate_random)
  JWT_SECRET=$(generate_random)
}

wait_for_database_initialization() {
  local attempt state
  echo "等待 MySQL 完成 gost.sql 初始化..."
  for ((attempt = 0; attempt < 600; attempt++)); do
    # MySQL 初始化时临时服务禁用 TCP；TCP 成功意味着整个初始化文件已执行完毕。
    if docker exec gost-mysql sh -c 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" "$MYSQL_DATABASE" -Nse "SELECT 1"' >/dev/null 2>&1; then
      return 0
    fi
    state=$(docker inspect -f '{{.State.Status}}' gost-mysql 2>/dev/null || true)
    case "$state" in
      exited|dead|restarting)
        echo "MySQL 初始化失败，请检查 gost.sql 和数据库容器日志；原 SQL 文件已保留。" >&2
        return 1 ;;
    esac
    if ((attempt % 15 == 0)); then echo "正在等待数据库初始化（${attempt}/600 秒）..."; fi
    sleep 1
  done
  echo "数据库初始化超时，安装已停止；请检查数据库日志，保留 .env 与 gost.sql。" >&2
  return 1
}

migrate_database() {
  # 执行数据库字段变更
  echo "🔄 执行数据库结构更新..."

  # 创建临时迁移文件（现在有了数据库信息）
  cat > temp_migration.sql <<EOF
-- 数据库结构更新
USE \`$DB_NAME\`;

-- user 表：删除 name 字段（如果存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'user'
        AND column_name = 'name'
    ),
    'ALTER TABLE \`user\` DROP COLUMN \`name\`;',
    'SELECT "Column \`name\` not exists in \`user\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：删除 port 字段、添加 server_ip 字段（如果不存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'port'
    ),
    'ALTER TABLE \`node\` DROP COLUMN \`port\`;',
    'SELECT "Column \`port\` not exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'server_ip'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`server_ip\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;',
    'SELECT "Column \`server_ip\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 将 ip 赋值给 server_ip（如果字段都存在）
UPDATE \`node\`
SET \`server_ip\` = \`ip\`
WHERE \`server_ip\` IS NULL;

-- node 表：修改 ip 字段类型为 longtext
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'ip'
        AND data_type = 'varchar'
    ),
    'ALTER TABLE \`node\` MODIFY COLUMN \`ip\` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;',
    'SELECT "Column \`ip\` not exists or already modified in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：添加 version 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'version'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`version\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;',
    'SELECT "Column \`version\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：添加 port_sta 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'port_sta'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`port_sta\` INT(10) DEFAULT 1000 COMMENT "端口起始范围";',
    'SELECT "Column \`port_sta\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- node 表：添加 port_end 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'port_end'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`port_end\` INT(10) DEFAULT 65535 COMMENT "端口结束范围";',
    'SELECT "Column \`port_end\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有节点设置默认端口范围
UPDATE \`node\`
SET \`port_sta\` = 1000, \`port_end\` = 65535
WHERE \`port_sta\` IS NULL OR \`port_end\` IS NULL;

-- node 表：添加 http、tls、socks 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'http'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`http\` INT(10) DEFAULT 0 COMMENT "HTTP 服务端口";',
    'SELECT "Column \`http\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'tls'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`tls\` INT(10) DEFAULT 0 COMMENT "TLS 服务端口";',
    'SELECT "Column \`tls\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'node'
        AND column_name = 'socks'
    ),
    'ALTER TABLE \`node\` ADD COLUMN \`socks\` INT(10) DEFAULT 0 COMMENT "SOCKS 服务端口";',
    'SELECT "Column \`socks\` already exists in \`node\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有节点设置 http、tls、socks 默认值
UPDATE \`node\`
SET \`http\` = IFNULL(\`http\`, 0),
    \`tls\` = IFNULL(\`tls\`, 0),
    \`socks\` = IFNULL(\`socks\`, 0);

-- tunnel 表：删除废弃字段（如果存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'in_port_sta'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`in_port_sta\`;',
    'SELECT "Column \`in_port_sta\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'in_port_end'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`in_port_end\`;',
    'SELECT "Column \`in_port_end\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'out_ip_sta'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`out_ip_sta\`;',
    'SELECT "Column \`out_ip_sta\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'out_ip_end'
    ),
    'ALTER TABLE \`tunnel\` DROP COLUMN \`out_ip_end\`;',
    'SELECT "Column \`out_ip_end\` not exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- tunnel 表：添加 tcp_listen_addr、udp_listen_addr、protocol（如果不存在）

-- tcp_listen_addr
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'tcp_listen_addr'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`tcp_listen_addr\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "0.0.0.0";',
    'SELECT "Column \`tcp_listen_addr\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- udp_listen_addr
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'udp_listen_addr'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`udp_listen_addr\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "0.0.0.0";',
    'SELECT "Column \`udp_listen_addr\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- protocol
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'protocol'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`protocol\` VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "tls";',
    'SELECT "Column \`protocol\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- traffic_ratio (流量倍率)
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'traffic_ratio'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`traffic_ratio\` DECIMAL(5,1) DEFAULT 1.0 COMMENT "流量倍率";',
    'SELECT "Column \`traffic_ratio\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有数据设置默认流量倍率
UPDATE \`tunnel\`
SET \`traffic_ratio\` = 1.0
WHERE \`traffic_ratio\` IS NULL;

-- forward 表：删除 proxy_protocol 字段（如果存在）
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'proxy_protocol'
    ),
    'ALTER TABLE \`forward\` DROP COLUMN \`proxy_protocol\`;',
    'SELECT "Column \`proxy_protocol\` not exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- forward 表：修改 remote_addr 字段类型为 longtext
SET @sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'remote_addr'
        AND data_type = 'varchar'
    ),
    'ALTER TABLE \`forward\` MODIFY COLUMN \`remote_addr\` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL;',
    'SELECT "Column \`remote_addr\` not exists or already modified in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- forward 表：添加 strategy 字段（负载均衡策略）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'strategy'
    ),
    'ALTER TABLE \`forward\` ADD COLUMN \`strategy\` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT "fifo" COMMENT "负载均衡策略";',
    'SELECT "Column \`strategy\` already exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有数据设置默认负载均衡策略
UPDATE \`forward\`
SET \`strategy\` = 'fifo'
WHERE \`strategy\` IS NULL;

-- forward 表：添加 inx 字段（排序索引）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'inx'
    ),
    'ALTER TABLE \`forward\` ADD COLUMN \`inx\` INT(10) DEFAULT 0 COMMENT "排序索引";',
    'SELECT "Column \`inx\` already exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有数据设置默认排序索引
UPDATE \`forward\`
SET \`inx\` = 0
WHERE \`inx\` IS NULL;

-- tunnel 表：添加 interface_name 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'tunnel'
        AND column_name = 'interface_name'
    ),
    'ALTER TABLE \`tunnel\` ADD COLUMN \`interface_name\` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;',
    'SELECT "Column \`interface_name\` already exists in \`tunnel\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- forward 表：添加 interface_name 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'forward'
        AND column_name = 'interface_name'
    ),
    'ALTER TABLE \`forward\` ADD COLUMN \`interface_name\` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL;',
    'SELECT "Column \`interface_name\` already exists in \`forward\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 创建 vite_config 表（如果不存在）
CREATE TABLE IF NOT EXISTS \`vite_config\` (
  \`id\` int(10) NOT NULL AUTO_INCREMENT,
  \`name\` varchar(200) NOT NULL,
  \`value\` varchar(200) NOT NULL,
  \`time\` bigint(20) NOT NULL,
  PRIMARY KEY (\`id\`),
  UNIQUE KEY \`unique_name\` (\`name\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 创建 statistics_flow 表（如果不存在）
CREATE TABLE IF NOT EXISTS \`statistics_flow\` (
  \`id\` bigint(20) NOT NULL AUTO_INCREMENT,
  \`user_id\` int(10) NOT NULL,
  \`flow\` bigint(20) NOT NULL,
  \`total_flow\` bigint(20) NOT NULL,
  \`time\` varchar(100) NOT NULL,
  \`created_time\` bigint(20) NOT NULL,
  PRIMARY KEY (\`id\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- statistics_flow 表：添加 created_time 字段（如果不存在）
SET @sql = (
  SELECT IF(
    NOT EXISTS (
      SELECT 1
      FROM information_schema.COLUMNS
      WHERE table_schema = DATABASE()
        AND table_name = 'statistics_flow'
        AND column_name = 'created_time'
    ),
    'ALTER TABLE \`statistics_flow\` ADD COLUMN \`created_time\` BIGINT(20) NOT NULL DEFAULT 0 COMMENT "创建时间毫秒时间戳";',
    'SELECT "Column \`created_time\` already exists in \`statistics_flow\`";'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为现有记录设置当前毫秒时间戳（仅当 created_time 为 0 或 NULL 时）
UPDATE \`statistics_flow\`
SET \`created_time\` = UNIX_TIMESTAMP() * 1000
WHERE \`created_time\` = 0 OR \`created_time\` IS NULL;

EOF

  cat "$SCRIPT_DIR/springboot-backend/src/main/resources/db/upgrade_forward_chain.sql" >> temp_migration.sql

  # 检查数据库容器
  if ! docker ps --format "{{.Names}}" | grep -q "^gost-mysql$"; then
    echo "❌ 数据库容器 gost-mysql 未运行"
    echo "🔍 当前运行的容器："
    docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
    echo "❌ 数据库结构更新失败，请手动执行 temp_migration.sql"
    echo "📁 迁移文件已保存为 temp_migration.sql"
    return 1
  fi

  # 执行数据库迁移
  if docker exec -i gost-mysql mysql -u "$DB_USER" -p"$DB_PASSWORD" < temp_migration.sql 2>/dev/null; then
    echo "✅ 数据库结构更新完成"
  else
    echo "⚠️ 使用用户密码失败，尝试root密码..."
    if docker exec -i gost-mysql mysql -u root -p"$DB_PASSWORD" < temp_migration.sql 2>/dev/null; then
      echo "✅ 数据库结构更新完成"
    else
      echo "❌ 数据库结构更新失败，请手动执行 temp_migration.sql"
      echo "📁 迁移文件已保存为 temp_migration.sql"
      echo "🔍 数据库容器状态: $(docker inspect -f '{{.State.Status}}' gost-mysql 2>/dev/null || echo '容器不存在')"
      echo "🛑 更新终止"
      return 1
    fi
  fi

  # 清理临时文件
  rm -f temp_migration.sql

}

# 安装功能
install_panel() {
  echo "🚀 开始安装面板..."
  check_docker || return 1
  [[ "$(uname -m)" == x86_64 ]] || { echo "首版面板依赖 MySQL 5.7，仅支持 x86_64；节点另支持 arm64。" >&2; return 1; }
  if [[ -f .env ]] || docker volume inspect mysql_data >/dev/null 2>&1; then
    echo "检测到已有配置或数据库卷，请保留原 .env 并选择更新，避免覆盖数据库凭据。" >&2
    return 1
  fi
  validate_panel_network || return 1
  prepare_local_files || return 1
  get_config_params
  umask 077
  cat > .env <<EOF
DB_NAME=$DB_NAME
DB_USER=$DB_USER
DB_PASSWORD=$DB_PASSWORD
JWT_SECRET=$JWT_SECRET
FRONTEND_PORT=$FRONTEND_PORT
BACKEND_PORT=$BACKEND_PORT
EOF

  prepare_local_images || return 1
  echo "🚀 初始化数据库（优先使用已有 gost.sql）..."
  $DOCKER_CMD up -d --no-build --pull never mysql || return 1
  wait_for_database_initialization || return 1
  migrate_database || return 1
  echo "🚀 数据库迁移完成，启动面板并应用日志限制..."
  $DOCKER_CMD up -d --no-build --pull never --force-recreate || return 1

  echo "🎉 部署完成"
  echo "🌐 访问地址: http://服务器IP:$FRONTEND_PORT"
  echo "📚 部署说明: https://github.com/Toto0912/flux-chain#readme"
  echo "💡 若使用备份，请使用原账号和密码；官方初始化数据的默认账号为 admin_user / admin_user。"
  echo "⚠️  默认密码应在首次登录后修改。"


}

# 更新功能
update_panel() {
  echo "🔄 开始更新面板..."
  check_docker

  [[ -s .env ]] || { echo "更新需要原部署目录的 .env；请勿重新生成数据库凭据。" >&2; return 1; }
  validate_panel_network || return 1
  prepare_local_files
  # 先完成资源准备，再重建容器；下载或构建失败时现有服务保持运行。
  prepare_local_images
  echo "🚀 使用已准备的镜像重建服务并应用日志限制（保留数据卷）..."
  $DOCKER_CMD up -d --no-build --pull never --force-recreate

  # 等待服务启动
  echo "⏳ 等待服务启动..."

  # 检查后端容器健康状态
  echo "🔍 检查后端服务状态..."
  for i in {1..90}; do
    if docker ps --format "{{.Names}}" | grep -q "^springboot-backend$"; then
      BACKEND_HEALTH=$(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || echo "unknown")
      if [[ "$BACKEND_HEALTH" == "healthy" ]]; then
        echo "✅ 后端服务健康检查通过"
        break
      elif [[ "$BACKEND_HEALTH" == "starting" ]]; then
        # 继续等待
        :
      elif [[ "$BACKEND_HEALTH" == "unhealthy" ]]; then
        echo "⚠️ 后端健康状态：$BACKEND_HEALTH"
      fi
    else
      echo "⚠️ 后端容器未找到或未运行"
      BACKEND_HEALTH="not_running"
    fi
    if [ $i -eq 90 ]; then
      echo "❌ 后端服务启动超时（90秒）"
      echo "🔍 当前状态：$(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || echo '容器不存在')"
      echo "🛑 更新终止"
      return 1
    fi
    # 每15秒显示一次进度
    if [ $((i % 15)) -eq 1 ]; then
      echo "⏳ 等待后端服务启动... ($i/90) 状态：${BACKEND_HEALTH:-unknown}"
    fi
    sleep 1
  done

  # 检查数据库容器健康状态
  echo "🔍 检查数据库服务状态..."
  for i in {1..60}; do
    if docker ps --format "{{.Names}}" | grep -q "^gost-mysql$"; then
      DB_HEALTH=$(docker inspect -f '{{.State.Health.Status}}' gost-mysql 2>/dev/null || echo "unknown")
      if [[ "$DB_HEALTH" == "healthy" ]]; then
        echo "✅ 数据库服务健康检查通过"
        break
      elif [[ "$DB_HEALTH" == "starting" ]]; then
        # 继续等待
        :
      elif [[ "$DB_HEALTH" == "unhealthy" ]]; then
        echo "⚠️ 数据库健康状态：$DB_HEALTH"
      fi
    else
      echo "⚠️ 数据库容器未找到或未运行"
      DB_HEALTH="not_running"
    fi
    if [ $i -eq 60 ]; then
      echo "❌ 数据库服务启动超时（60秒）"
      echo "🔍 当前状态：$(docker inspect -f '{{.State.Health.Status}}' gost-mysql 2>/dev/null || echo '容器不存在')"
      echo "🛑 更新终止"
      return 1
    fi
    # 每10秒显示一次进度
    if [ $((i % 10)) -eq 1 ]; then
      echo "⏳ 等待数据库服务启动... ($i/60) 状态：${DB_HEALTH:-unknown}"
    fi
    sleep 1
  done

  # 从容器环境变量获取数据库信息
  echo "🔍 获取数据库配置信息..."

  # 等待一下让服务完全就绪
  echo "⏳ 等待服务完全就绪..."
  sleep 5

  # 先检查后端容器是否在运行
  if ! docker ps --format "{{.Names}}" | grep -q "^springboot-backend$"; then
    echo "❌ 后端容器未运行，无法获取数据库配置"
    echo "🔍 当前运行的容器："
    docker ps --format "table {{.Names}}\t{{.Status}}"
    echo "🛑 更新终止"
    return 1
  fi

  DB_INFO=$(docker exec springboot-backend env | grep "^DB_" 2>/dev/null || echo "")

  if [[ -n "$DB_INFO" ]]; then
    DB_NAME=$(echo "$DB_INFO" | grep "^DB_NAME=" | cut -d'=' -f2)
    DB_PASSWORD=$(echo "$DB_INFO" | grep "^DB_PASSWORD=" | cut -d'=' -f2)
    DB_USER=$(echo "$DB_INFO" | grep "^DB_USER=" | cut -d'=' -f2)
    DB_HOST=$(echo "$DB_INFO" | grep "^DB_HOST=" | cut -d'=' -f2)

    echo "📋 数据库配置："
    echo "   数据库名: $DB_NAME"
    echo "   用户名: $DB_USER"
    echo "   主机: $DB_HOST"
  else
    echo "❌ 无法获取数据库配置信息"
    echo "🔍 尝试诊断问题："
    echo "   容器状态: $(docker inspect -f '{{.State.Status}}' springboot-backend 2>/dev/null || echo '容器不存在')"
    echo "   健康状态: $(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || echo '无健康检查')"

    # 尝试从 .env 文件读取配置
    if [[ -f ".env" ]]; then
      echo "🔄 尝试从 .env 文件读取配置..."
      DB_NAME=$(grep "^DB_NAME=" .env | cut -d'=' -f2 2>/dev/null)
      DB_PASSWORD=$(grep "^DB_PASSWORD=" .env | cut -d'=' -f2 2>/dev/null)
      DB_USER=$(grep "^DB_USER=" .env | cut -d'=' -f2 2>/dev/null)

      if [[ -n "$DB_NAME" && -n "$DB_PASSWORD" && -n "$DB_USER" ]]; then
        echo "✅ 从 .env 文件成功读取数据库配置"
        echo "📋 数据库配置："
        echo "   数据库名: $DB_NAME"
        echo "   用户名: $DB_USER"
      else
        echo "❌ .env 文件中的数据库配置不完整"
        echo "🛑 更新终止"
        return 1
      fi
    else
      echo "❌ 未找到 .env 文件"
      echo "🛑 更新终止"
      return 1
    fi
  fi

  # 检查必要的数据库配置
  if [[ -z "$DB_PASSWORD" || -z "$DB_USER" || -z "$DB_NAME" ]]; then
    echo "❌ 数据库配置不完整（缺少必要参数）"
    echo "🛑 更新终止"
    return 1
  fi

  migrate_database || return 1

  echo "✅ 更新完成"
}

# 导出数据库备份
export_migration_sql() {
  echo "📄 开始导出数据库备份..."

  # 获取数据库配置信息
  echo "🔍 获取数据库配置信息..."

  # 先检查后端容器是否在运行
  if ! docker ps --format "{{.Names}}" | grep -q "^springboot-backend$"; then
    echo "❌ 后端容器未运行，尝试从 .env 文件读取配置..."

    # 从 .env 文件读取配置
    if [[ -f ".env" ]]; then
      DB_NAME=$(grep "^DB_NAME=" .env | cut -d'=' -f2 2>/dev/null)
      DB_PASSWORD=$(grep "^DB_PASSWORD=" .env | cut -d'=' -f2 2>/dev/null)
      DB_USER=$(grep "^DB_USER=" .env | cut -d'=' -f2 2>/dev/null)

      if [[ -n "$DB_NAME" && -n "$DB_PASSWORD" && -n "$DB_USER" ]]; then
        echo "✅ 从 .env 文件读取数据库配置成功"
      else
        echo "❌ .env 文件中的数据库配置不完整"
        return 1
      fi
    else
      echo "❌ 未找到 .env 文件"
      return 1
    fi
  else
    # 从容器环境变量获取数据库信息
    DB_INFO=$(docker exec springboot-backend env | grep "^DB_" 2>/dev/null || echo "")

    if [[ -n "$DB_INFO" ]]; then
      DB_NAME=$(echo "$DB_INFO" | grep "^DB_NAME=" | cut -d'=' -f2)
      DB_PASSWORD=$(echo "$DB_INFO" | grep "^DB_PASSWORD=" | cut -d'=' -f2)
      DB_USER=$(echo "$DB_INFO" | grep "^DB_USER=" | cut -d'=' -f2)

      echo "✅ 从容器环境变量读取数据库配置成功"
    else
      echo "❌ 无法从容器获取数据库配置，尝试从 .env 文件读取..."

      if [[ -f ".env" ]]; then
        DB_NAME=$(grep "^DB_NAME=" .env | cut -d'=' -f2 2>/dev/null)
        DB_PASSWORD=$(grep "^DB_PASSWORD=" .env | cut -d'=' -f2 2>/dev/null)
        DB_USER=$(grep "^DB_USER=" .env | cut -d'=' -f2 2>/dev/null)

        if [[ -n "$DB_NAME" && -n "$DB_PASSWORD" && -n "$DB_USER" ]]; then
          echo "✅ 从 .env 文件读取数据库配置成功"
        else
          echo "❌ .env 文件中的数据库配置不完整"
          return 1
        fi
      else
        echo "❌ 未找到 .env 文件"
        return 1
      fi
    fi
  fi

  # 检查必要的数据库配置
  if [[ -z "$DB_PASSWORD" || -z "$DB_USER" || -z "$DB_NAME" ]]; then
    echo "❌ 数据库配置不完整（缺少必要参数）"
    return 1
  fi

  echo "📋 数据库配置："
  echo "   数据库名: $DB_NAME"
  echo "   用户名: $DB_USER"

  # 检查数据库容器是否运行
  if ! docker ps --format "{{.Names}}" | grep -q "^gost-mysql$"; then
    echo "❌ 数据库容器未运行，无法导出数据"
    echo "🔍 当前运行的容器："
    docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
    return 1
  fi

  # 生成数据库备份文件
  SQL_FILE="database_backup_$(date +%Y%m%d_%H%M%S).sql"
  echo "📝 导出数据库备份: $SQL_FILE"

  # 使用 mysqldump 导出数据库
  echo "⏳ 正在导出数据库..."
  if docker exec gost-mysql mysqldump --no-tablespaces -u "$DB_USER" -p"$DB_PASSWORD" --single-transaction --routines --triggers "$DB_NAME" > "$SQL_FILE" 2>/dev/null; then
    echo "✅ 数据库导出成功"
  else
    echo "⚠️ 使用用户密码失败，尝试root密码..."
    if docker exec gost-mysql mysqldump --no-tablespaces -u root -p"$DB_PASSWORD" --single-transaction --routines --triggers "$DB_NAME" > "$SQL_FILE" 2>/dev/null; then
      echo "✅ 数据库导出成功"
    else
      echo "❌ 数据库导出失败"
      rm -f "$SQL_FILE"
      return 1
    fi
  fi

  # 检查文件大小
  if [[ -f "$SQL_FILE" ]] && [[ -s "$SQL_FILE" ]]; then
    FILE_SIZE=$(du -h "$SQL_FILE" | cut -f1)
    echo "📁 文件位置: $(pwd)/$SQL_FILE"
    echo "📊 文件大小: $FILE_SIZE"
  else
    echo "❌ 导出的文件为空或不存在"
    rm -f "$SQL_FILE"
    return 1
  fi
}


# 卸载功能
uninstall_panel() {
  echo "🗑️ 开始卸载面板..."
  check_docker

  if [[ ! -f "docker-compose.yml" ]]; then
    prepare_local_files
  fi

  read -p "确认卸载面板吗？此操作将停止并删除所有容器和数据 (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "❌ 取消卸载"
    return 0
  fi

  echo "🛑 停止并删除容器、镜像、卷..."
  $DOCKER_CMD down --rmi all --volumes --remove-orphans
  echo "🧹 删除配置文件..."
  rm -f docker-compose.yml .env
  echo "✅ 卸载完成"
}

# 主逻辑
main() {

  # 显示交互式菜单
  while true; do
    show_menu
    read -p "请输入选项 (1-5): " choice

    case $choice in
      1)
        install_panel
        exit 0
        ;;
      2)
        update_panel
        exit 0
        ;;
      3)
        uninstall_panel
        exit 0
        ;;
      4)
        export_migration_sql
        exit 0
        ;;
      5)
        echo "👋 退出脚本"
        exit 0
        ;;
      *)
        echo "❌ 无效选项，请输入 1-5"
        echo ""
        ;;
    esac
  done
}

# 支持交互菜单和显式操作；source 时不自动执行。
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
    install) install_panel ;;
    update) update_panel ;;
    backup) check_docker; export_migration_sql ;;
    uninstall) uninstall_panel ;;
    "") main ;;
    *) echo "用法: bash panel_install.sh [install|update|backup|uninstall]" >&2; exit 1 ;;
  esac
fi
