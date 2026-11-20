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
| **Docker + Compose** | — | ⚠️ 建议（起管理库 / 全套） | PostgreSQL 管理库、可选 gitea / 容器化 backend | 本机已有 PostgreSQL 18.6 / MySQL 8.4 时可不装；集成测试用 Testcontainers 也需要 Docker |
| **Gradle** | 9.9.1 wrapper | 由 `./gradlew` 提供，无需预装 | — | — |
| **PostgreSQL**（或 MySQL 8.4） | 18.6（参考默认值） | ✅ | 管理库：项目 / 仓库 / 用户 / 评论等 | **Phase 2 起后端启动必需**；Flyway 自动建表，`SPECHUB_DB_*` 可改地址与凭据 |
| **Redis** | 7.x（compose `redis:7.4.2-alpine`） | ✅ | Spring Session 会话与登录限流 | **阶段 C 起必需**；`SPECHUB_REDIS_URL` 默认 `redis://localhost:6379`；不可用时认证 fail-closed |
| **SQLite** | 内嵌 `sqlite-jdbc` | 无需安装 | 索引存储 `spechub-index.db` | 无独立数据库服务 |

### 启动方式

**方式 A · docker compose（含 gitea，贴近生产）**
```bash
cp compose/.env.example compose/.env        # 至少覆盖 SPECHUB_ADMIN_TOKEN（缺失/空串启动失败）
docker compose -f compose/docker-compose.yml up -d --build   # gitea(:3000) + db + redis + backend(:8080)
cd apps/web && npm install && npm run dev   # → http://localhost:5173（被占顺延 5174），/api 代理到 :8080
```

**方式 B · 本地目录档（本地管理库 + 纯本地 git 仓，无需 gitea / backend 容器）**

> Phase 2 起管理库是后端启动硬依赖（Flyway 启动自动建表）；阶段 C 起 **Redis 也是硬依赖**（Spring Session + 登录限流）。没有本机 PostgreSQL / Redis 时用下面的 compose 只起 db / redis；已有 PG / MySQL / Redis 则跳过第 1 步，建一个空库并把 `SPECHUB_DB_*` / `SPECHUB_REDIS_URL` 指向它。

```bash
# 1) 管理库 + Redis：compose 只起 postgres:18.6（localhost:5432，spechub/spechub）与 redis:7.4（localhost:6379）
docker compose -f compose/docker-compose.yml up -d --wait db redis

# 2) 后端 bootJar + 起服务（SPECHUB_* 三个目录启动时自动创建；admin-token 必填）
./gradlew :server:bootJar
SPECHUB_GIT_SOURCE_DIR=/tmp/spechub-gitsource SPECHUB_SQLITE_DIR=/tmp/spechub-sqlite \
  SPECHUB_ARTIFACTS_DIR=/tmp/spechub-artifacts \
  SPECHUB_DB_URL=jdbc:postgresql://localhost:5432/spechub SPECHUB_DB_USER=spechub \
  SPECHUB_DB_PASSWORD=spechub SPECHUB_REDIS_URL=redis://localhost:6379 \
  SPECHUB_ADMIN_TOKEN=dev-admin-token \
  java -jar modules/server/build/libs/spechub-server.jar --server.port=18080 &

# 3) 一个本地 bare origin（真源仓），注册即摄取
git init -q --bare -b main /tmp/spechub-origin
# 往 seed 工作仓塞一份 spec（如 kernel 的 PetStore 样例）并 push，POST /api/repos 注册

# 4) 前端代理指向本地 backend
cd apps/web && VITE_API_BASE=http://localhost:18080 npm run dev
```

> 管理库连接默认值即 `jdbc:postgresql://localhost:5432/spechub` + `spechub/spechub`，与上面 compose 的 db 完全一致；Redis 默认 `redis://localhost:6379`。本段 `SPECHUB_DB_*` / `SPECHUB_REDIS_URL` 显式写出，便于切换到 MySQL / 远程服务时直接替换。

### 多项目（阶段 A）

**bootstrap（幂等）**：后端首次启动写入默认组织 `default` 与默认项目 `default`（`ApplicationInit`）。`SPECHUB_ADMIN_TOKEN` 在所有环境必填：token 持有者为默认组织 `org_admin`、默认项目 owner；所有环境都必须真实登录（admin-token / 密码 / OIDC → Spring Session），不存在免登录模式。

**种子数据（本地 / dev 示例）**——仓库必须归属项目，指定 `projectId`：

```bash
# 1) 创建项目并取回 id（需 org_admin；登录后携带 Spring Session Cookie + CSRF）
PID=$(curl -s -X POST http://localhost:8080/api/projects -H 'Content-Type: application/json' \
  -d '{"name":"支付平台","slug":"payments"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# 2) 注册仓库（必须归属项目；注册即按 active binding 摄取 specs/ 与 docs/）
curl -s -X POST http://localhost:8080/api/repos -H 'Content-Type: application/json' \
  -d "{\"projectId\":$PID,\"url\":\"file:///tmp/spechub-origin\",\"branch\":\"main\"}"

# 3) 按项目读取（Web 前端同样使用 ?projectId=）
curl -s "http://localhost:8080/api/repos?projectId=$PID"
curl -s "http://localhost:8080/api/apis?projectId=$PID&stage=draft"
```

- **Web**：顶栏项目下拉切换；org_admin「新建项目」，项目 admin+「项目设置」（owner 改名/归档/删除，admin 改可见性）；侧栏「+」注册仓库。
- **可见性**：`private`（仅成员）/ `internal`（组织成员可读 release）/ `public`（匿名可读 release）；draft / 评论 / 评审 / 订阅仅成员可见，不可见资源统一 404。
- **真源 binding**：`GET /api/repos/{id}/bindings` → 创建 candidate → `verify`（identical / diverged / no_active）→ `activate`（diverged 需 `confirm=true`）。旧 binding 保留 `SPECHUB_REPO_BINDING_ROLLBACK_DAYS`（默认 30 天），窗口内可回滚、禁止删除。
- **成员**：org_admin 在「成员与角色」创建组织用户并加入项目；项目角色 viewer / editor / admin / owner（owner 只能通过转移接口变更）。
- **灰度开关**：A‑1 安全底座（`ReadIsolationTest`）在开放多项目 UI 前已通过，阶段 A 未引入 `spechub.multi-project.enabled`；如后续需要灰度，可在 `SpecHubProperties` 增加并仅隐藏项目切换入口。

### 团队与跨团队订阅（阶段 B）

团队是**组织级**实体（`org → team`），与项目成员独立：项目角色管「谁能写这个仓」，团队管「哪个消费团队订阅了哪些接口、变更通知发给谁」。团队角色为 `lead` / `member`。

```bash
# 1) 创建团队（org_admin；服务账号不可用），创建者自动成为 lead
TID=$(curl -s -X POST http://localhost:8080/api/teams -H 'Content-Type: application/json' \
  -d '{"name":"支付团队","description":"消费方"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# 2) 加成员（team lead / org_admin；成员必须是组织用户）
curl -s -X POST http://localhost:8080/api/teams/$TID/members -H 'Content-Type: application/json' \
  -d '{"username":"alice","role":"lead"}'

# 3) 以团队身份订阅接口（team lead / org_admin；仍需对 repo 可见）
curl -s -X POST http://localhost:8080/api/subscriptions -H 'Content-Type: application/json' \
  -d "{\"repoId\":1,\"method\":\"GET\",\"path\":\"/pets\",\"teamId\":$TID}"

# 4) 团队成员查看通知（不传 teamId = 我的全部团队；org_admin = 全组织审计视角）
curl -s "http://localhost:8080/api/subscriptions/notifications?teamId=$TID"
```

- **权限**：创建/删除团队 `org_admin`；改名、成员管理、订阅/取消订阅 `team lead` / `org_admin`；普通成员只看自己团队的通知。
- **通知可见性**：通知按 `team_id` 投递；非成员指定 `teamId` 返回 404；`org_admin` 可审计全组织通知；历史 `team_id` 为空的行仅 `org_admin` 可见。
- **改名**：团队改名不影响订阅（订阅只认 `team_id`），订阅列表显示当前团队名；历史通知保留投递时名称快照。
- **删除保护**：有订阅的团队不能删除（409），先取消订阅；团队删除后历史通知保留（`team_id` 置 NULL）。
- **旧数据**：V4/V5 迁移把旧 `subscription.team` 文本按同组织同名合并为 `team` 行并回填 `team_id`；旧列保留为只读快照，D1 清理。
- **Web**：账号菜单 →「团队管理」创建团队、管理成员；接口详情 →「订阅」Tab 选择团队订阅；通知面板按「我的团队」展示并可按团队筛选。

### 账号与安全（阶段 C）

**架构**：Spring Security 双链 + Spring Session Data Redis（dev / prod 同一实现）。
- **Web 链**：浏览器会话 Cookie `spechub_session`（HttpOnly + SameSite=Lax；prod 加 Secure）；密码 / OIDC 登录；会话写请求必须带 CSRF（`X-CSRF-Token`）；空闲 24h / 绝对 7 天。
- **Bearer 链**：`Authorization: Bearer <token>`，无会话、无 CSRF；用户 API 令牌 / 项目级服务账号。
- **所有环境都必须真实登录**：没有 `authEnabled=false` / dev 免登录；Redis 不可用时认证 fail-closed（不降级匿名 / 内存会话）。

**首次 bootstrap（dev 与生产一致）**：`.env` 配置 `SPECHUB_ADMIN_TOKEN` → 用 `admin` + 该 token 调用 `POST /api/auth/login` 换取 Spring Session → Web「账号与安全 → 修改密码」设置密码（或绑定 OIDC）→ 之后用密码 / OIDC 登录。admin-token 仅保留 bootstrap / break-glass。

```bash
# 会话登录（返回 csrfToken，并写入 HttpOnly Cookie）
curl -s -c .cookies -H 'Content-Type: application/json' \
  -d '{"username":"admin","token":"dev-admin-token"}' http://localhost:8080/api/auth/login

# 会话写请求：Cookie + CSRF
curl -b .cookies -H 'X-CSRF-Token: <csrfToken>' -H 'Content-Type: application/json' \
  -d '{"name":"支付平台","slug":"payments"}' http://localhost:8080/api/projects

# 机器调用：Bearer（无 Cookie / CSRF）
curl -H "Authorization: Bearer $SPECHUB_TOKEN" http://localhost:8080/api/projects
```

**登录方式**：用户名或邮箱 + 密码；OIDC（`SPECHUB_OIDC_ENABLED=true`，回调 `${SPECHUB_EXTERNAL_URL}/login/oauth2/code/spechub`）；兼容令牌登录（登录框高级选项，仅换取会话，不再写 localStorage）。

**令牌类型与前缀**（服务端只存 SHA-256 hash 与展示前缀，明文仅在创建 / 轮换响应返回一次）：

| 类型 | 前缀 | 说明 |
|---|---|---|
| 用户 API 令牌 | `shu_` | Web「账号与安全 → 我的令牌」创建；1–365 天过期、可吊销；吊销后立即 401 |
| 服务账号令牌 | `shs_` | 项目级机器身份；viewer 只读 / editor 可写；默认 90 天；不可登录 Web |
| legacy / admin-token | 无前缀 | 由旧 `app_user` 令牌列迁移；V7 后仍有效；只用于 bootstrap / 兼容 |

**服务账号**：Web「服务账号」入口（当前项目 admin+ / org_admin）按项目创建、轮换、吊销；跨项目访问统一 404；用于 CI / MCP / CLI。

**审计**：`org_admin` 在 Web「审计日志」查询登录 / 禁用 / 会话 / 令牌 / 服务账号 / 项目 / 团队动作（`GET /api/audit`，过滤 + 分页）；保留 365 天；`detail` 不含密码 / 令牌 / Cookie。

**CLI / MCP**：`SPECHUB_TOKEN` 传 `shu_` 或 `shs_`，Bearer 请求无需 Cookie / CSRF；MCP 写工具还需 `SPECHUB_ALLOW_WRITE=1`，服务账号 viewer 只读、editor 可写。详见 [apps/mcp/README.md](apps/mcp/README.md) / [apps/cli/README.md](apps/cli/README.md)。

**Web 端**：启动探测 `/api/auth/status` + `/api/auth/me`，未认证弹登录框；`mustChangePassword` 强制改密；账号菜单含「账号与安全」（资料 / 改密 / 活动会话 / 令牌）、「服务账号」、「审计日志」、「团队管理」；浏览器的 localStorage 不保存任何长期令牌。

**质量门禁（R12）**：`./gradlew check`（ktlintCheck + Jackson 2 源码校验 + 全量测试）；CI 见 [.github/workflows/ci.yml](.github/workflows/ci.yml)。单独跑格式检查/修复：`./gradlew ktlintCheck` / `./gradlew ktlintFormat`。

**可选端**：MCP `cd apps/mcp && npm install && npm run build && node dist/index.js`（连 backend 走 HTTP）；CLI `cd apps/cli && go build`（或 `scripts/build-release.sh` 交叉编译四平台）。

> **Profile / 登录**：所有 profile 都启用鉴权，`SPECHUB_ADMIN_TOKEN` 必填（缺失/空串在配置绑定阶段启动失败）。dev 用 `.env` 的 dev 值以 `admin` + token 完成首次 bootstrap，再设置密码 / 绑定 OIDC；生产设 `SPRING_PROFILES_ACTIVE=prod` 并注入正式 token。compose 档通过 `.env` 的 `SPRING_PROFILES_ACTIVE` / `SPECHUB_ADMIN_TOKEN` 注入。
>
> **OIDC（可选，C6）**：设置 `SPECHUB_OIDC_ENABLED=true` + `SPECHUB_OIDC_ISSUER_URI` / `CLIENT_ID` / `CLIENT_SECRET`；回调地址 `${SPECHUB_EXTERNAL_URL}/login/oauth2/code/spechub` 需在 IdP 注册，`external-url` 必须是浏览器可访问地址。Vite 开发代理下将 `external-url` / `redirect-uri` 指向前端开发地址（如 `http://localhost:5173`）保证回调与 Cookie 同源；`auto-provision` 默认关闭，OIDC-only 用户密码为空。

> **API 文档（R11）**：dev 启动后访问 `http://localhost:8080/doc.html`（Knife4j Next UI）或 `/v3/api-docs`（OpenAPI JSON）；prod 关闭文档端点并保持鉴权保护（401）。文档层 Jackson 2 仅作为 springdoc / Knife4j 的运行时传递依赖，业务代码只使用 Jackson 3（约束见 `CLAUDE.md`）。

> 实测踩坑：① 本地目录档的 origin 必须是 **bare 仓**（非 bare 会被 `denyCurrentBranch` 拒 push）；② Vite 端口被占自动顺延，看终端提示；③ 最小 Web 起步需 JDK 25 + git + Node + **一个管理库**（SQLite 索引内嵌、无需安装），业务数据目录可全走 `/tmp`；④ postgres 18+ 镜像必须挂载 `/var/lib/postgresql`（compose 已按此配置，旧路径 `/data` 会被 entrypoint 拒绝启动）。