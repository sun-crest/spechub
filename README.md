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

- **JVM 侧**：Java 25 LTS（JDK≥25）、Gradle 9.9.1（wrapper）；构建入口 `./gradlew build`
- **部署**：compose 蓝图见 [compose/docker-compose.yml](compose/docker-compose.yml)，`docker compose -f compose/docker-compose.yml up -d --build` 一键拉起 gitea + backend
- **未完成前置**：P0 骨架仅含构建链与部署骨架，摄取 / 索引 / API 能力随开发计划 B–E 组落地