# apps/cli —— CLI（P1 G 组）

当前推荐 **Go**：单二进制、零依赖进 CI 与任何基础镜像、启动毫秒级。

- G1 cobra 命令 `pull / push / status / diff / commit` v1
- G2 与 server HTTP 同步（token 配置、push 前校验走 server API）
- G3 交叉编译发布脚本（linux/darwin × amd64/arm64 → 单二进制）

只连接 `spec-client`，不依赖 kernel。骨架阶段占位。