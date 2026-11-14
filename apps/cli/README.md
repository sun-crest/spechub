# apps/cli —— spechub CLI（P1 G 组，Go 单二进制）

类 git 的 SpecHub 同步工具：日常节奏「改代码 → AI 更新文档 → diff → 提交」。与 server 走 HTTP（spec-client 薄封装），零 JVM 依赖。

## 命令（G1）

| 命令 | 说明 |
|---|---|
| `login --url --token` | 配置 server 基址与令牌（`~/.spechub.json`；环境变量 `SPECHUB_BASE_URL` / `SPECHUB_TOKEN` 优先） |
| `status` | 查看各真源仓（repo）索引同步状态与错误 |
| `pull <repo> --path X [--out DIR]` | 把某仓 spec 拉取到本地 |
| `push <repo> <file> --path X` | 上传本地 spec 写回真源（后端平台 commit + push → 自动摄取） |
| `diff <repo> <file> --path X` | 对比本地文件与 server 内容（unified diff，依赖系统 diff，缺省降级） |
| `commit <repo> <file> --path X` | 提交回真源（等价 push；git 提交语义在真源侧，后端负责） |

`<repo>` 用 id 或 url 前缀匹配（如 `2` 或 `petstore`）。

## 构建与发布（G3）

```bash
go build -o spechub .                 # 本机
./scripts/build-release.sh            # linux/darwin × amd64/arm64 → dist/
```

设计说明：token 经 `~/.spechub.json`（0600）或环境变量注入；push 前由 server 校验并执行写回（R1-C），CLI 不做本地 git 写逻辑。