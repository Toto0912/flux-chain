FluxChain v1.0.0-beta1 首次预发布，基于 Flux Panel v1.4.3。

支持管理员多跳转发链、完整路径 TCP 延迟探测、自动选路及 TLS/QUIC/TCP/UDP 节点链路，保留原有流量与配额规则。UDP 模式使用原生 UDP 承载数据报及配套 TCP 承载 TCP 业务，选路依据 TCP 参考延迟。

安装步骤见仓库 README。面板支持 x86_64 Linux，节点支持 amd64 和 arm64。在线安装资源和离线包均附 SHA256SUMS；Docker 与 Compose 需预先安装。已有系统更新前请备份数据库和 .env。

该版本为 beta，同机测试不代表实际公网游戏的延迟和丢包表现。原项目来源和第三方许可证见 README 与 THIRD_PARTY_NOTICES.md。
