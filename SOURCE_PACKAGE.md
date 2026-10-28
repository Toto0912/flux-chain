# 开发源码说明

产品名称 FluxChain，版本由根目录 VERSION 标识。前端 package.json、后端 pom.xml、节点版本和独立安装脚本保持一致，scripts/check_release.py 会在 CI 中检查。

包含定制节点源码、面板源码、自动化测试、部署资源及许可证。依赖缓存、构建产物、离线镜像、测试数据库及本机配置不提交仓库。

默认安装下载 Toto0912/flux-chain 对应 Release 的已构建资源。开发者可选择 PANEL_IMAGE_MODE=build；无网络服务器可使用离线包。旧版 Android/iOS 客户端未纳入本次发行范围。

首次 GitHub 发布流程见 docs/RELEASING.md。版本更新时运行 scripts/check_release.py，并同步发布说明中的明确版本地址。
