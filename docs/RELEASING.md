# 发布 FluxChain

## 首次上传

本地仓库需登录 Toto0912，推送 main 后先确认 Verify and release FluxChain 的 verify 作业通过。使用 GitHub 自动提供的 GITHUB_TOKEN，无需把个人访问令牌写入源码或配置文件。

## 发布标签

确认 VERSION 及应用、脚本、Compose 中的版本一致：

```bash
python3 scripts/check_release.py
git tag v1.0.0-beta1
git push origin v1.0.0-beta1
```

标签必须与 VERSION 一致。工作流先测试，再发布 x86_64 前后端镜像、构建 amd64/arm64 节点，导出带 MySQL 的离线镜像包，生成 SHA256SUMS，全部成功后才公开 Release。带短横线的版本标记为预发布。

镜像地址：ghcr.io/toto0912/flux-chain-backend:1.0.0-beta1 与 ghcr.io/toto0912/flux-chain-frontend:1.0.0-beta1。首发后在 GitHub Packages 设置中将两个面板镜像设为 Public，用户即可匿名拉取。即使镜像仓库暂不可用，默认安装也会尝试 Release 的同版本镜像包。

## 发布附件

- panel_install.sh、install.sh：版本固定的独立安装脚本。
- gost-linux-amd64、gost-linux-arm64：修改版节点。
- fluxchain-panel-v版本.tar.gz：部署配置和数据库资源。
- fluxchain-images-amd64-v版本.tar.gz：面板与 MySQL 镜像。
- fluxchain-offline-amd64-v版本.tar.gz：完整离线面板部署包。
- SHA256SUMS：上述文件的校验值。

不要上传 .env、数据库备份、测试环境密钥、缓存或工具链。发布完成后，在新目录实际运行 README 的安装命令，核对登录、验证码、添加节点和一条 TCP/UDP 转发，再宣布可用。源码构建测试通过不等于实际发布下载链路已经验证。
