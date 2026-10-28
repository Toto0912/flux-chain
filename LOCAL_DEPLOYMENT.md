# FluxChain 部署与升级

## 环境

面板首版使用 MySQL 5.7，支持 x86_64 Linux；不要将此数据库镜像当作 arm64 原生支持。节点另支持 amd64 与 arm64。需要 Docker Engine、Compose v2、curl 或 wget、tar、sha256sum；Docker 和 Compose 不包含在离线包内。

## 在线面板

按 README 下载版本固定的 `panel_install.sh`，在单独部署目录运行 `sudo bash panel_install.sh install`。默认下载已构建的镜像；不会在服务器编译应用。GHCR 下载失败时自动尝试 Release 镜像包。

安装生成随机数据库凭据与 JWT 密钥，写入权限受限的 `.env`。保留此文件和数据卷。默认端口为前端 6366、后端 6365，安装时可选择其他端口。

```bash
# 显式选择网络配置；默认自动检测 IPv6
sudo env PANEL_IPV6=0 bash panel_install.sh install
sudo env PANEL_IPV6=1 bash panel_install.sh install
```

脚本不会修改 Docker daemon 配置。当前沿用原项目容器名及全局数据卷名，每台 Docker 主机只部署一套；不要与另一套 Flux 面板共用这些名字。

## 升级

先在原部署目录备份，再下载目标版本脚本并执行 update。下列地址对应本次首版；将来升级时改为目标版本号。

```bash
cd /opt/fluxchain
sudo bash panel_install.sh backup
curl -fL --retry 2 https://github.com/Toto0912/flux-chain/releases/download/v1.0.0-beta1/panel_install.sh -o panel_install.sh
sudo bash panel_install.sh update
```

下载或构建完成后才重建容器，失败时不停止现有服务。保留 `.env`、数据库和日志卷；容器重建及迁移期间面板短暂中断。升级 SQL 仅将旧默认站点名 flux/flux-panel 改为 FluxChain，自定义名称保留。已有转发链需编辑并保存一次，重新下发服务；保存可能中断现有业务。

不要删除 `mysql_data`，不要重新生成原数据库密码。移动目录时保留原 Compose 项目名。卸载选项会删除数据，使用前另行保存备份。

## 源码构建

下载完整仓库，在根目录运行：

```bash
sudo env PANEL_IMAGE_MODE=build bash panel_install.sh install
# 更新已有部署
sudo env PANEL_IMAGE_MODE=build bash panel_install.sh update
```

该模式使用本目录的源码和 Dockerfile，构建时会联网下载基础镜像、Maven、前端和 Go 依赖。开发机无需提交 node_modules、target、dist 或本地工具链。

## 离线面板

在可联网的机器下载同一 Release 的 `fluxchain-offline-amd64-v1.0.0-beta1.tar.gz` 与 `SHA256SUMS`，校验包后上传到已装 Docker/Compose 的 x86_64 Linux 服务器。

```bash
# 用 SHA256SUMS 中同名文件的记录进行校验
sha256sum fluxchain-offline-amd64-v1.0.0-beta1.tar.gz
mkdir fluxchain
cd fluxchain
tar -xzf ../fluxchain-offline-amd64-v1.0.0-beta1.tar.gz
sudo env PANEL_IMAGE_MODE=load bash panel_install.sh install
# 已有部署则在原目录使用 update，保留 .env
```

离线包包含面板、MySQL 镜像和部署资源，不包含测试数据库、运行密钥、Docker/Compose 安装程序。它不会在线拉取镜像。包内 artifacts/ 含两种架构的节点程序，也提供独立 Release 附件，可按下面方式离线安装。

## 节点安装与更新

默认安装脚本自动下载并校验同版本节点。离线时下载所需的 `gost-linux-amd64` 或 `gost-linux-arm64`，与 `install.sh` 一起上传：

```bash
sudo bash install.sh -b ./gost-linux-amd64 -a '面板地址:6365' -s '节点密钥'
sudo bash install.sh -u -b ./gost-linux-amd64
```

在线更新需先下载目标版本 install.sh，再执行 `sudo bash install.sh -u`；若脚本同目录留有旧节点文件，使用 -b 明确指定新文件，或移开旧文件后在线下载。新程序校验失败时不会停止原服务。升级保留 config.json 与 gost.json。

源码编译：`bash build_node.sh amd64` 或 `bash build_node.sh arm64`，需要 Go 1.23.4+ 或 Docker。节点服务日志由宿主机 journald 管理。可选 tcpkill 不会自动安装。

## 流量与日志

Docker 控制台日志每份 50MB、最多 7 份；后端 application.log 约 100MB 或跨天轮转，归档 gzip、总量上限 1GB、最长 30 天，先达到的条件先清理。当前写入文件不计入历史归档上限，异步清理可短暂超出。

流量统计由节点实时上报并入库，不依赖运行日志。旧版不同命名的日志需在备份后单独清理，不会自动纳入新规则。查看 Docker 限制：

```bash
docker inspect -f '{{json .HostConfig.LogConfig}}' springboot-backend
docker inspect -f '{{json .HostConfig.LogConfig}}' vite-frontend
docker inspect -f '{{json .HostConfig.LogConfig}}' gost-mysql
```
