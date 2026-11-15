# SpecHub（契书）

**研发规格的单一 git 真源**——接口契约 + AI 分析同仓，Claude Code / 人 / IDE 插件三方永远读写同一份 OpenAPI spec 文件。

> - 产品与架构真源：[docs/SpecHub-产品方案.md](docs/SpecHub-产品方案.md)
> - 开发任务拆解真源：[docs/SpecHub-开发计划.md](docs/SpecHub-开发计划.md)（随进度勾选）

一句话：接口文档的痛不是「不好看」，而是「不可信」。SpecHub 让**文档即源码**——AI 产出的接口契约 / 接手分析 / 需求说明都以 OpenAPI 3.1 / Markdown 文件存在 git 仓里，版本化、可 diff、可回滚；三扇门（Claude Code / Web / IDE 插件）读写同一份文件，一改全改。

## 三件事记住 SpecHub

| 差异 | 一句话 |
|---|---|
| **AI 优先** | 接口、接手分析、需求说明都是文件，Claude Code 用最自然的方式直接读写，不需要「爬进入口」 |
| **契约真源** | OpenAPI 单一模型，mock / 调试 / 导出 / 契约测试从契约长出来 |
| **IDE 注入** | 编辑器内 hover 即见契约定义，从路由与注解一键导入 |

## 仓库结构（monorepo，§7.4）

```
spechub/
├── modules/                 # ── JVM 侧（Gradle，Kotlin DSL）──
│   ├── kernel/              # ★ 零框架依赖纯库，IDE 插件可直接依赖 jar
│   │   ├── spec-model/      #   OpenAPI 3.1 内部模型（Kotlin data class）
│   │   ├── spec-parser/     #   自研解析（Jackson 3 / tools.jackson）3.0/3.1→3.1 归一化
│   │   ├── git-store/       #   系统 git CLI 子进程封装
│   │   └── spec-index/      #   SQLite(sqlite-jdbc) 索引 DAO
│   ├── server/              #   Spring Boot 4 聚合层（REST / 摄取 / mock / MCP remote）
│   └── mock/                #   Mock 规则引擎（P2 I3，MVC + 虚拟线程，部署并入 server）
├── apps/                    # ── 端侧（TS npm workspace + Go module，语言单列可换）──
│   ├── web/                 #   React + Vite + Monaco（P1）
│   ├── mcp/                 #   MCP server，当前推荐 TS(Node)（P1）
│   ├── cli/                 #   CLI，当前推荐 Go（P1）
│   └── spec-client/         #   server API 生成的薄 client（TS / Go 各一份）
├── plugins/
│   ├── intellij/            #   Kotlin，依赖 kernel 产物（P3）
│   └── vscode/              #   TypeScript，复用 spec-client（P3）
├── compose/                 # docker-compose.yml / .env.example / 各服务 Dockerfile
├── docs/                    # 方案文档（真源）+ ADR/API 约定
└── e2e/                     # 端到端：真 git 仓 → 摄取 → 查询 → mock
```

**依赖方向（唯一规则，§7.4）**：`server / mock / intellij-plugin` 只允许依赖 `kernel`，**不允许依赖 server**；`mcp / cli` 走 HTTP 与 server 通信、只连 `spec-client`——TS 侧与 Go 侧永不 import 任何 JVM 产物。

## 环境与构建

### 依赖环境清单（2026-11-15 实测整理）

| 依赖 | 版本要求 | 必需? | 用途 | 说明 |
|---|---|---|---|---|
| **JDK** | **25 LTS**（`jvmToolchain(25)` 强制） | ✅ | Kotlin / Spring Boot 4 编译运行 | 低版本会被 Gradle 工具链直接拒绝 |
| **系统 git** | 任意 | ✅ | git-store 以子进程驱动镜像 / 写回 | Dockerfile 也专门 `apt-get install git` |
| **Node.js + npm** | Node ≥18（无 engines 硬约束，Vite 6 下限） | ✅（web / mcp） | React 前端 + TS MCP server | 实测 Node 24 |
| **Go** | 1.27（`toolchain go1.27.4`） | ⚠️ 仅 CLI | `apps/cli` 交叉编译 | 只跑 web/backend 可跳过 |
| **Docker + Compose** | — | ⚠️ 可选（仅 compose 档） | gitea 托管 + 容器化 backend | 用本地目录档可完全不需要 |
| **Gradle** | 9.9.1 wrapper | 由 `./gradlew` 提供，无需预装 | — | — |
| **SQLite** | 内嵌 `sqlite-jdbc` | 无需安装 | 索引存储 `spechub-index.db` | 无独立数据库服务 |

### 启动方式

**方式 A · docker compose（含 gitea，贴近生产）**
```bash
docker compose up -d --build          # gitea(:3000) + backend(:8080)，Dockerfile 内自动 bootJar
cd apps/web && npm install && npm run dev   # → http://localhost:5173（被占顺延 5174），/api 代理到 :8080
```

**方式 B · 本地目录档（无需 docker，纯本地 git 仓）**
```bash
# 1) 后端 bootJar + 起服务（SPECHUB_* 三个目录启动时自动创建）
./gradlew :server:bootJar
SPECHUB_GIT_SOURCE_DIR=/tmp/spechub-gitsource SPECHUB_SQLITE_DIR=/tmp/spechub-sqlite \
  SPECHUB_ARTIFACTS_DIR=/tmp/spechub-artifacts \
  java -jar modules/server/build/libs/spechub-server.jar --server.port=18080 &

# 2) 一个本地 bare origin（真源仓），注册即摄取
git init -q --bare -b main /tmp/spechub-origin
# 往 seed 工作仓塞一份 spec（如 kernel 的 PetStore 样例）并 push，POST /api/repos 注册

# 3) 前端代理指向本地 backend
cd apps/web && VITE_API_BASE=http://localhost:18080 npm run dev
```

**可选端**：MCP `cd apps/mcp && npm install && npm run build && node dist/index.js`（连 backend 走 HTTP）；CLI `cd apps/cli && go build`（或 `scripts/build-release.sh` 交叉编译四平台）。

> 实测踩坑：① 本地目录档的 origin 必须是 **bare 仓**（非 bare 会被 `denyCurrentBranch` 拒 push）；② Vite 端口被占自动顺延，看终端提示；③ 最小 Web 起步只需 JDK 25 + git + Node，数据全局皆走 `/tmp` 目录即可。