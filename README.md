# FluxChain

**v1.0.0-beta1** · 基于 GOST 的转发管理面板，支持多跳转发链、完整路径探测和自动选路。

FluxChain 基于 [Flux Panel v1.4.3](https://github.com/bqlpfy/flux-panel) 二次开发，保留原项目署名和 Apache License 2.0。项目地址：[Toto0912/flux-chain](https://github.com/Toto0912/flux-chain)。

## 功能

- 原有端口转发、隧道转发、用户配额、流量统计、倍率与限速管理。
- 管理员可创建多跳转发链，每跳配置多个候选节点。
- 枚举完整路径，从入口探测到目标的 TCP 建连延迟，为新连接选择更快的路径；已有连接不迁移。
- TLS、QUIC、明文 TCP、原生 UDP＋配套明文 TCP 四种节点链路，同时承载 TCP 和 UDP 业务。
- 探测间隔默认 5 分钟（10 秒～60 分钟），超时默认 3 秒（1～5 秒），切换阈值默认 1000ms（1ms～超时值），探测并发上限 16。
- 所有路径不可用时保留上次路径，首次部署使用第一条路径；入口计费，中间节点不重复计费。
- 转发链节点删除保护、跨转发端口占用检查及部署失败回滚。
- Docker 日志每份 50MB、最多 7 份；后端文件约 100MB 轮转、归档总量 1GB、最长 30 天。

**UDP 模式仍依据配套 TCP 链路到目标的参考延迟选路，不代表真实 UDP 延迟或 UDP 可达性。** 纯 UDP 目标的 TCP 探测会失败，此时按保留路径规则转发。更多说明见 [传输方式](PLAIN_TRANSPORTS.md)。

## 在线安装面板

首版面板支持 **x86_64 Linux**；节点支持 x86_64 和 arm64。服务器预先安装 Docker Engine、Docker Compose v2，以及 curl（或 wget）、tar、sha256sum。

在当前目录中下载并运行版本固定的脚本：

```bash
curl -fL --retry 2 https://github.com/Toto0912/flux-chain/releases/download/v1.0.0-beta1/panel_install.sh -o panel_install.sh
sudo bash panel_install.sh install
```

脚本会下载并校验部署资源，拉取对应版本的面板与 MySQL 镜像。镜像仓库不可用时会尝试同版本的 Release 镜像包。无需在服务器上编译 Java、Node.js 或 Go。

默认访问端口：前端 `6366`、后端 `6365`。默认管理员为 `admin_user / admin_user`，首次登录后修改。将网站配置中的面板地址设置为节点可以访问的后端地址，再添加节点。

> 发布资产必须构建完成后才能使用上述命令。预发布版本使用明确版本号，不依赖 GitHub 的 latest 地址。

## 安装节点

在“节点监控”添加服务器后，复制安装命令，在该节点以 root 执行。脚本会下载并校验对应架构的 FluxChain 节点。

手动安装示例：

```bash
curl -fL --retry 2 https://github.com/Toto0912/flux-chain/releases/download/v1.0.0-beta1/install.sh -o install.sh
sudo bash install.sh -a '面板地址:6365' -s '节点密钥'
```

节点需使用 systemd。安装目录和服务名保留 `/etc/gost`、`gost.service`，方便兼容已有部署。必须使用本项目节点，不能直接替换为原版 GOST。

## 更新、离线安装与源码构建

请先阅读 [部署与升级说明](LOCAL_DEPLOYMENT.md)。更新保留原 `.env` 和数据库卷，不要通过重新安装来更新现有系统。已有转发链升级后需编辑并保存一次，下发新的探测握手配置。

- 在线安装：下载对应版本的脚本和发布资源。
- 离线面板：下载 Release 的 `fluxchain-offline-amd64-v1.0.0-beta1.tar.gz`，解压后按部署说明运行。
- 源码构建：使用本仓库的 Dockerfile 和 `PANEL_IMAGE_MODE=build`；构建依赖仍需联网获取。
- 本地节点：使用 `build_node.sh` 编译，或下载 Release 节点文件，再通过 `install.sh -b` 安装。

## 发布与来源

推送 `v1.0.0-beta1` 标签会触发测试、构建镜像和节点程序，随后发布安装脚本、校验清单及离线包。流程说明见 [发布指南](docs/RELEASING.md)。

- [更新记录](CHANGELOG.md)
- [测试范围](LINUX_CONTAINER_VALIDATION.md)
- [第三方说明](THIRD_PARTY_NOTICES.md)
- [许可证](LICENSE)

使用者应在获得授权的网络环境中使用本项目。实际公网的延迟、抖动和丢包表现需在自己的网络中验证。
