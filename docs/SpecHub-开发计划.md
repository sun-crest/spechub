# SpecHub 开发计划

> **关联**：主线方案见 [SpecHub-产品方案.md](SpecHub-产品方案.md)（产品真源）；本文件是**开发任务拆解真源**，随进度勾选更新。
> **日期**：2026-11-19　**状态**：**P0–P2.5 全部完成**——P0 存储内核、P1 双写闭环、P2 平台化（I+J+K）、P2.5 多项目骨架（A0–A10）全部勾选；管理库 PostgreSQL / MySQL 双库各 91 项测试全绿，`./gradlew check` 与 `apps/web` 构建通过。**P2.6 团队（ADR 阶段 B）进行中（B1–B6 完成）**；P3（IDE 插件 / 契约测试 / 云托管）待规划。

---

## 0. 里程碑总览

| 阶段 | 目标 | 工作包 | 验收一句话 |
|---|---|---|---|
| **P0** | 存储内核 | A · B · C · D · E | Claude 能直接读文档；push 一个仓，SQLite 查得到 api |
| **P1** | 双写闭环 | F · G · H | AI 与人双写同一份 spec 不打架 |
| **P2** | 平台化 | I · J · K | 团队日常愿意用 |
| **P2.5** | 多项目骨架（ADR 阶段 A） | A0–A10 | 项目/成员可管理、读隔离通过、仓库归属项目 |
| **P2.6** | 团队与跨团队订阅（ADR 阶段 B） | B0–B8 | 团队可管理、订阅绑定团队、通知只发给成员 |
| **P3** | 放大差异化 | L · M · N | 插件 / 契约测试 / 托管 |

约定：`[模块]` = 对应 §7.4 工程结构的目录；`→` = 依赖的前置任务。

---

## P0 · 存储内核（目标：Claude 能直接读文档）

### A 项目骨架

- [x] A1建 monorepo 目录树 `modules/ apps/ compose/ docs/ e2e/` 与根 README [根] ✅ 2026-11-14：含 apps/plugins/e2e 占位说明，依赖方向写入根 README
- [x] A2Gradle（Kotlin DSL）根构建 + `gradle/libs.versions.toml` 版本目录；锁定 Java 25 / Spring Boot 4 当前 GA 小版本；**JUnit 对齐 `spring-boot-dependencies` BOM（6.0.3）**——教训：版本目录勿自声明 5.13.2 而让 starter-test 实际跑 6.0.3 [根] ✅ 2026-11-14：Boot 4.1.2 / Kotlin 2.3.21 对齐 BOM / Jackson 3.1.7（tools.jackson 撤双栈）；实证「声明 X、实跑 Y」漂移已消除（dependencyInsight 显 6.0.3）
- [x] A3`kernel/spec-model` 空壳 + JVM 侧 kernel 四模块占位（`spec-parser / git-store / spec-index`）[modules/kernel] ✅ 2026-11-14：依赖方向先行声明（parser/index→spec-model；git-store 零第三方），JUnit 冒烟测试全绿
- [x] A4compose 蓝图 v1：`backend`（空 Spring 应用）+ `gitea` + 四个命名卷 + `.env.example` [compose] ✅ 2026-11-14：server 最小可启动空壳（org.springframework.boot 引导类，E1 再补配置绑定/单测）；多阶段 Dockerfile + gitea:1.22 双服务健康（排障：INSTALL_LOCK 属 [security] 段）

### B OpenAPI 3.1 内部模型 [modules/kernel/spec-model]

- [x] B1核心模型类：`OpenApiDoc / Operation / PathItem / Schema / Component / Server`（Kotlin data class，空安全）✅ 2026-11-14：8 模型文件全部落地（含 PathItem/Parameter/RequestBody/Response/Components/SecurityScheme 等）；`type`/`additionalProperties` 用 JsonNode 保真 + 访问器；空安全全字段
- [x] B2JSON / YAML 序列化（Jackson + jackson-dataformat-yaml，支持单/多文档）✅ 2026-11-14：SpecModelIO（detectFormat/readDocument/多文档流 write/read + tree↔model）；黄金样例 PetStore-3.1.yaml；round-trip 幂等
- [x] B3归一化模型指纹 `specHash`（作为 Version 发布快照的主键，见 P2 I6）✅ 2026-11-14：SpecHash（键排序/数值规范/删空容器）；顺序与数字笔法不敏感、语义变化敏感，64 位小写 hex

### C 多版本归一化 [modules/kernel/spec-parser] → B

- [x] C1自研解析器（Jackson 3 / tools.jackson 直读 JSON/YAML），自动探测 `swagger: "2.0"` / `openapi: "3.0"` / `"3.1"`；**撤 swagger-parser**（其 3.1 支持弱且锁死 Jackson 2，见产品方案 §7）✅ 2026-11-14：SpecParser.parse/detect；3.1 直通、3.0 升级、2.0 拒绝（明确指出推迟 P2 J2）
- [x] C2升级映射（P0 覆盖 **3.0→3.1 语义升级**）：`nullable → type:[...,null]`、`exclusiveMin/Max` 布尔→数值；2.0 的结构升级（`definitions → components/schemas`、`host/basePath → servers`、`consumes/produces → requestBody`）**推迟到 P2 J2** ✅ 2026-11-14：OpenApiUpgrader 在 tree 层升级（源文件从不回写，§6.5）；含 type 单字符串→数组、嵌套 Schema 递归；exclusive 数值化并移除原 bound 防叠加
- [x] C3升级变更日志输出（"自动转换 / 待人工复核"标注，对接评审流状态）✅ 2026-11-14：UpgradeRecord（kind/path/summary + ReviewFlag.AUTOMATIC|NEEDS_REVIEW，如 nullable 无 type 标复核）
- [x] C4解析失败容错与错误收集（坏 spec 隔离，不拖垮整次摄取）✅ 2026-11-14：ParseResult（Success/Failure）+ ParseIssue（syntax/model-conversion/unknown-version/not-openapi 等结构化收集），坏文档不 throw；黄金用例 PetStore-3.0/3.1.yaml 落地

### D git 化读写 [modules/kernel/git-store]

- [x] D1系统 git CLI 子进程封装（参数化安全、超时、`--no-pager`）✅ 2026-11-14：GitCli 参数列表化（绝无 shell 拼接）+ waitFor 限时 destroyForcibly + GIT_TERMINAL_PROMPT=0 + 双流并发读；`-c core.quotepath=false` 中文路径不转义
- [x] D2`clone / fetch` 镜像远程仓到 `gitsource/repos/<org>/<repo>`（§7.3）✅ 2026-11-14：mirrorClone/-Fetch（bare 镜像全文对象）+ ensureMirror 幂等 + cloneInto + readFile(show) + headRevision；工作树 syncWorkingToRemote
- [x] D3`commit / push` 写回真源仓（Web 直改与平台提交，R1-C 兜底形态）✅ 2026-11-14：stage/commit/commitAllTracked（显式 -c user.name/email 平台身份，不依赖全局配置）/push；测试验证真源收到 specbot 身份提交
- [x] D4`diff / status` 提取 spec 变更（喂给 ChangeLog）✅ 2026-11-14：status porcelain 解析（含 R/C rename 旧路径）、diffStat numstat、diff 文本、log 摘要（ChangeLog 供数）

### E 摄取链路 + 索引（P0 核心） [modules/kernel/spec-index + modules/server]

- [x] E1Spring Boot 4 应用骨架（引导类、`SPECHUB_*` 配置绑定、单测基建）[modules/server] → A2 A4 ✅ 2026-11-14：SpecHubProperties（@ConfigurationProperties）+ KernelConfig bean 装配（含启动目录/DB 初始化）+ @EnableScheduling；@SpringBootTest 集成测试
- [x] E2 Repo 注册 REST：添加仓地址 / 分支 / 凭据注入（`POST /api/repos` + 可选 token 注入 origin URL，不落库）[modules/server] ✅ 2026-11-14：RepoController（register/list/get/apis）+ UrlTokenStore 内存 registry；git 命令参数传 token、clone 后 remote url 已清洗；测试实证 repo.url 无 token
- [x] E3摄取器主线：`git-store → spec-parser → 索引`（增量 + 幂等续跑）[modules/server] → C D ✅ 2026-11-14：Ingestor（ensureMirror 增量 → findSpecFiles(§6.2 specs/) → parse → index.replace 全量重建）；specHash 幂等续跑测试（同内容不增 version / 新 push 增 version）
- [x] E4SQLite 表：`project / repo / api / schema / doc / version` + 唯一键 + api↔schema 引用 [modules/kernel/spec-index] ✅ 2026-11-14：IndexDb（WAL/foreign_keys 事务外 PRAGMA）+ IndexSchema 7 表 + SpecIndexStore DAO（幂等键：repo(url)/version(hash)/api(method+path)/schema(name)；ON DELETE CASCADE；sqlite-jdbc INTEGER→Long 转换）
- [x] E5全量重扫命令 `reindex`（验证"索引可重建"纪律，§6.4 决策 7）[modules/server] ✅ 2026-11-14：ReindexRunner `--reindex` ApplicationRunner，逐仓全量重扫并计数失败
- [x] E6webhook 接收端点（内容变更触发增量摄取）[modules/server] ✅ 2026-11-14：POST /api/webhooks/{id} 触发 ingest；集成测试实证 apiCount 稳定
- [x] E7 轮询兜底（未配 webhook 的仓定时 fetch + diff；`PollingIngestor` 复用 Ingestor 幂等，间隔可配）[modules/server] ✅ 2026-11-14：@Scheduled(spechub.polling-interval-ms) 遍历注册仓摄取，失败仅告警不中断
- [x] E8首次启动初始化：目录、DB、admin 种子命令（UI 化在 P1 H6）✅ 2026-11-14：KernelConfig 启动建目录+迁移；ApplicationInit 保证 default project + 环境/`--seed-admin` 生成 admin 令牌

**P0 验收**：对一个小 git 仓触发 push → SQLite 查到归一化的 api 记录；`docker compose up -d --build` 起 gitea+backend；`reindex` 可整体重建。✅ 2026-11-14 部署实测（两档均过）：
- **本地目录档**（§7.3）：注册 `/fixtures/origin` → api=2/schema=1/version=1、sync=ok → 查到 GET/POST /pets（中文 summary 保真）→ push → webhook → api=3 无残留；SQLite `spechub-index.db` 落盘。
- **Gitea 档**：`gitea admin user create` 引导 admin → API 建 repo → host 以 token push（HTTP 认证）→ backend 注册 `http://gitea:3000/...git`（token 注入不落库）→ 摄取 api=2 → **gitea webhook 自动增量**（修复 SSRF 防护：compose 增 `GITEA__webhook__ALLOWED_HOST_LIST=private`）→ 改 spec push 后**零手动调用**投影即更新 v4；轮询兜底（E7）同时生效。

---

## P1 · 双写闭环（目标：AI 与人双写不打架）

### F MCP server（TS，当前推荐） [apps/mcp] → E

- [x] F1 脚手架 + MCP TS SDK（streamable HTTP，remote 优先；stdio 调试用）[apps/mcp] ✅ 2026-11-14：@modelcontextprotocol/sdk 1.32；stdio（默认）+ `--http` streamable HTTP（/mcp 会话 + /health；SDK onsessioninitialized 签名适配）；HTTP initialize 握手 200 冒烟
- [x] F2 工具集 v1：`list_projects / search_api / get_api / create_api / update_api / doc_sync_status` [apps/mcp] ✅ 2026-11-14：5 读 + 2 写工具全部实连 backend；写回走新增 PUT /api/repos/{id}/spec（SpecWriter R1-C：工作树 clone→平台 commit→push→自动摄取）；e2e：create_api 写 gitea 仓提交 0fb6e560 → search 命中 listWidgets
- [x] F3 权限分层：默认只读、写操作需确认（对接 server 审批开关，§6.4 决策 3）✅ 2026-11-14：默认只注册读工具（实证工具列表仅 5 读）；`SPECHUB_ALLOW_WRITE=1` 才注册写工具；backend 侧 TokenAuthFilter（配 spechub.admin-token 后 Bearer 校验）/actuator 放行

### G CLI（Go） [apps/cli] → E

- [x] G1 cobra 命令 `pull / push / status / diff / commit` v1 [apps/cli] ✅ 2026-11-14：Go 1.26 + cobra；命令齐全且真实冒烟（pull 拉取 gitea spec → 本地改动 → diff 出差异 → push 写回 commit）
- [x] G2 与 server HTTP 同步（token 配置、push 前校验走 server API）✅ 2026-11-14：`~/.spechub.json`（0600）+ 环境变量优先；client 层 Bearer 注入；push 由后端校验并执行写回（R1-C）
- [x] G3 交叉编译发布脚本（linux/darwin, amd64/arm64 → 单二进制）✅ 2026-11-14：scripts/build-release.sh → dist/spechub-{linux,darwin}-{amd64,arm64}（实测产出四平台）

### H 最小 Web UI [apps/web] → E

- [x] H1 脚手架 + 布局（**双入口**：接口主 / 文档辅，§5.3）✅ 2026-11-15：apps/web 转正（Vite 6 + React 19 + TS + Tailwind v4 + shadcn/Base UI）；三区壳 + 侧栏 Tabs 分段控件双入口；dev `/api` 代理到 backend；`tsc -b && vite build` 通过
- [x] H2 项目列表 / 接口列表（读）✅ 2026-11-15：接真后端 `GET /api/projects|repos|apis`（ApiQueryController + SpecIndexStore，`syncStatus`/`specJson` 字段对齐）；接口列表 tag/repo 过滤 + 关键词搜索 + 命中计数，侧栏仓库树带 sync 状态；顶栏项目下拉切换暂为空操作占位（当前仅 default project）
- [x] H3 接口详情 + Markdown 渲染（Doc 域预览）✅ 2026-11-15：接口详情三段式接真后端 `GET /apis/{id}`（specJson 前端渲染 Parameters/Responses/schema 递归，溯源区新增 spec 文件路径）；Doc 域真 Markdown（react-markdown + remark-gfm，标题索引与 table 支持，沿用内置样例待 P2 K 切后端）
- [x] H4 编辑即提交：改 spec 文件 → commit → push（走 git-store）✅ 2026-11-15：双栏编辑器保存走「强制 diff 确认」（Monaco DiffEditor + commit message，O1 拍板）→ `PUT /repos/{id}/spec` 写回真源（platform commit → 自动摄取）；`GET /apis/{id}` 后端 join version 带出 `specPath`（H4 直达用）；e2e：本地 bare origin 注册 → 改 title → PUT → 真源 `spechub-bot` 提交 → version 投影 +1 → 详情带 specPath；修复 `GitStore.listFiles/readFile` 对工作树仓（`repos-work/`，`.git` 在子目录）的 `--git-dir` 解析（此前仅支持 bare mirror 返回空）
- [x] H5 Monaco 双栏编辑器（YAML / JSON 编辑 + 实时渲染预览，不做 YAML↔JSON 互转）✅ 2026-11-15：左 Monaco YAML/JSON（本地打包 + `?worker`，离线可用；构建 monaco 独立 chunk 4MB 不阻塞首屏）；右 `yaml` 解析 → 操作列表 + 复用契约渲染引擎实时预览；构建/typecheck 全绿
- [x] H6 登录 + admin 初始化（对接 E8）✅ 2026-11-15：admin token 简单登录占位（§7 O3，RBAC 留 P2 I4）。后端 `AuthController`：`GET /api/auth/status`（报告 authEnabled + 当前请求 authenticated）+ `POST /api/auth/login`（校验 `spechub.admin-token`，无状态不落库）；`TokenAuthFilter` 放行 `/api/auth/*`（其余受保护端点仍全局限流）。前端：localStorage 持有 token → 全请求带 Bearer；启动探测 status，启用鉴权且未认证 → 自动弹登录框 + **只读模式**（编辑入口禁用/隐藏，§5 结论）；顶栏账号菜单登录/登出；未配 admin-token 免鉴权照常。`AuthApiTest`（真实 HTTP，配 admin-token profile）：status 报告、无 token 401、错误令牌 401、正确令牌 200 + authenticated、actuator 放行。e2e 实证：启用鉴权 backend → 登录 → 带 Bearer 访问

**P1 验收**：人用 Web、Claude 用 MCP 读同一份 spec，双写无冲突；CI 里 `spechub check` 可跑通。✅ 2026-11-15：H 组全完成（人用 Web 读+编辑+登录，F 组 MCP 读+写、G 组 CLI 四端，双写共用 SpecWriter 写回真源链路上均已实证）。

> **本地开发环境**（2026-11-15 整理，真源与完整命令见根 README「环境与构建」）：
> - **依赖**：JDK 25（toolchain 强制）+ 系统 git + Node≥18/npm（web·mcp）；Go 1.27 仅 CLI；Docker 可选（compose 档）。
> - **两档启动**：A) `docker compose up -d --build` 起 gitea+backend → web dev（/api 代理 :8080）；B) 本地目录档——`./gradlew :server:bootJar` + `java -jar spechub-server.jar --server.port=18080`（SPECHUB_* 三个目录）→ `VITE_API_BASE=http://localhost:18080 npm run dev`。
> - **踩坑**：本地目录档 origin 必须是 bare 仓（非 bare 拒 push）；Vite 端口被占自动顺延 5174；不要用内置 jar，用 bootJar（`spechub-server.jar`）。
> - 全部数据（gitsource 镜像 / SQLite 索引 / artifacts）走 `/tmp` 目录即可起步，无外部服务。

> **UI 设计对照**：本组与 P2 I 组的界面决策见 [SpecHub-UI方案.md](SpecHub-UI方案.md)（三区壳 + 双入口、详情三段式、Monaco 双栏语义、H 组范围清单）

---

## P2 · 平台化（目标：团队日常愿意用）

### I Web 完善与协作 [apps/web + modules/server]

- [x] I1 在线调试 Try it out（读 spec 发请求）[modules/server] ✅ 2026-11-15：`POST /api/apis/{id}/try`（TryController，JDK HttpClient 代理；任意 http(s) baseUrl 用户拍板，协议白名单 + 连接 5s/读 10s 超时 + 响应体 256KB 截断）；路径/query/header/body 按 spec 构造；只读能力不受「写操作皆审」。详情页「交互」Tab 起真表单（TryPanel）。
- [x] I2 Mock 规则引擎（pattern / value / script）[modules/mock] → I3 ✅ 2026-11-15：`mock_rule` 表（repo/apiId/fieldPattern/type/value）；三档规则——value（glob 路径→字面 JSON）、pattern（正则路径→内置格式样例 email/uuid/uri/date… 与 schema.format 共用）、script（DSL：`RANDOM_INT/RANDOM_STR/UUID/NOW/SEQ`）；规则匹配 api 级优先于项目级；无规则按 schema 类型生成（$ref→components、enum→首项、allOf 并集、可空剥离、深度 8 防环）。单测 5 用例全绿。
- [x] I3 Mock 并入 backend（**MVC + Java 25 虚拟线程**，不引 WebFlux）；`modules/mock` 为依赖 kernel 的独立模块、部署并入 backend 进程 [modules/mock + modules/server] → E4 I2 ✅ 2026-11-15：`modules/mock` 转正（只依赖 kernel，零 Spring）；`MockEngine` 收 method+实际路径（`{template}` 段展开）→ 挑响应（具体码>2XX>default）→ 生成样例；真实路由 `GET|POST|… /mock/{repoId}/**`（MockController，鉴权放行——只读投影联调服务，`X-Mock-Stage` 回显档位），**release 优先 / draft 回退**（I6：mock 与发布契约一致）；规则 CRUD `PUT /api/repos/{id}/mock/rules` + `DELETE`（204）+ 预览 `GET /api/apis/{id}/mock/preview`；`spring.threads.virtual.enabled=true` 已开。
- [x] I4 RBAC（owner / admin / editor / viewer）✅ 2026-11-15：`app_user`（username+token_hash，SHA-256 落库）+ `membership`（user→project 角色，owner/admin/editor/viewer）双表；`spechub.admin-token` 启动 bootstrap 平台 admin（恒 owner，与 H6 Bearer 完全兼容）；`TokenAuthFilter` 由「bearer==adminToken」升级为「token→用户解析→request attribute」；`AuthService` 统一权限判定（未启用鉴权全放行，本地/最小部署不变）。角色判定挂在写端点：注册仓库需 editor+、写回 spec/promote/mock 规则需 editor+、成员管理需 admin+。REST：login（username+token，token-only 兼容）/ me / users CRUD / role / token 重置 / delete。前端：登录改「用户名+令牌」、账号菜单显示身份角色、admin 可开「成员与角色」面板、编辑入口按角色可见（release/只读同前）。AuthApiTest 新增 6 用例全绿；RBAC e2e 15 项✔（bootstrap/登录/401-403-200/write 分层/成员管理/mock 放行）。
- [x] I5 评论 / 变更记录 ✅ 2026-11-15：**评论**：`comment` 表（repo_id + method + path **稳定寻址**、api_id 非 FK——api 是可重建投影，摄取 DELETE+INSERT 不级联丢评论；method/path 冗余列；doc_id 预留）；REST `GET/POST /api/apis/{id}/comments`、`DELETE /api/comments/{id}`（作者本人或 project admin+）；发表需登录（未启用鉴权放行）；前端详情页「评论」Tab（列表/发表/删除，时间正序）。**变更记录**：SpecWriter 写回在 changelog 附带 **diff 摘要**（`+1/-1 specs/...`，HEAD^ 对比；首次写回容错标注）；`GET /api/changelog` 全局变更流（跨 repo）；前端侧栏底部「变更记录」入口（全局流 Dialog），详情溯源区变更列表沿用。关键设计修正：changelog 的 api_id 也去 FK（评论类留痕不随投影重建级联删）。测试：SpecIndexStoreTest comment 用例 + CommentApiTest 2 用例（评论全链路 / 写回 diff 留痕）+ AuthApiTest 评论需登录；e2e 10 项✔（登录/401/列表/作者删除/写回 diff/全局流含评论动作）。
- [x] I6 draft → release 双层版本 + 发布快照（consumer 默认读 release）→ E3 ✅ 2026-11-15：`api/schema` 表加 `stage` 维（UNIQUE 含 stage；旧库 migrate 检测缺列先 drop 重建，§6.4 决策 7 可重建纪律）；摄取只重建 draft 档、`promotePath` 把最新 draft 冻结为 release 快照（同 path 其它版本降 draft，ON CONFLICT DO UPDATE 幂等）；`POST /api/repos/{id}/promote` + 版本/变更记录查询；消费查询 `GET /apis?stage=`（默认 draft）+ release 全局视图；前端顶栏 **draft/release 全球开关**（UI 方案决策 1 top pin）、release 只读禁编辑/发布、详情溯源区发布按钮 + specHash + 版本徽标；同 hash 幂等续跑不破坏已发布 release。spec-index 新增单测 5 个全绿。
- [x] I7 订阅：消费团队声明依赖 → 结构变更通知 → I6 ✅ 2026-11-15：`subscription`（repo+method+path 稳定寻址 + team + notify_url 预留 webhook）+ `notification`（team/method/path/summary，通知历史留审计）双表；REST `POST/GET /api/subscriptions` + `DELETE`（登录即可订阅、幂等 upsert、204 取消）+ `GET /api/subscriptions/notifications?team=`；**变更检测**：Ingestor 摄取时比对该 spec 文件上次 `specHash`（`latestSpecHash`）判定结构变化（幂等续跑同内容不通知），变化则对该文件下每个接口的订阅方落通知；changelog 同步留痕（subscription/subscription.delete）。前端：详情页「订阅」Tab（订阅方列表 + 添加/取消 + 登录用户团队的最近通知）。测试：SpecIndexStoreTest 订阅幂等/通知不随订阅删清空 + SubscriptionApiTest 2 用例（改动→webhook→通知、取消后不再通知）+ e2e 9 项✔（订阅/列表/无变化空/改 spec push→通知 1 条带 method+path/幂等不重复）。注：server:test 显式 `maxParallelForks=1` 消除多 @SpringBootTest 偶发 SQLITE_BUSY。
- [x] I8 评审审批流（"写操作皆审"落地，§6.4 决策 3）✅ 2026-11-15：`review` 表（repo/path/new_content/diff/old_hash/status pending|approved|rejected/requester/reviewer/reject_reason/created/resolved）；**写入口分流**：`PUT /repos/{id}/spec` 启鉴权时先入 pending 评审队列（不落真源），未启用鉴权保持直写（本地/最小部署零摩擦）；`POST /api/reviews/{id}/approve` 经 `SpecWriter` 落真源（平台 commit+push）+ 摄取、`reject {reason}` 打回；**评审人须他人**（requester 不能批自己的提交，平台 admin 可自批排障）；`GET /api/reviews?status=&repoId=` + `GET /reviews/{id}`（带 currentContent 供 diff 对比）。前端：侧栏「评审队列」入口（pending 计数 badge）+ ReviewDialog（队列 / 当前真源 vs 待批复两栏 diff / 批准 / 打回+理由）；保存成功 toast 用服务端 message（评审模式下提示「已提交评审请求 #N」）。WriteSpecResponse 增 `reviewId/status/message`（MCP/CLI 兼容：评审模式下 commit 为空、由评审决定落真源）。测试：SpecIndexStoreTest review 流转 + ReviewApiTest 2 用例（pending 真源不动/自批 403/他人批准落真源/重复批 409、reject 真源不动）+ e2e 10 项✔（HEAD 前后对比）。P2 I 组全完成——剩余 J/K 与 P3。

### J 导入导出 [modules/kernel/spec-parser + modules/server]

- [x] J1 导入 Postman collection（验证归一化管道）→ C ✅ 2026-11-15：`PostmanImporter`（kernel spec-parser，tree 构造）——item[] 递归展开（folder 嵌套）、`url.path` join（`{{var}}`/`:var` 段→`{var}` 模板，缺省回退 url.raw）、query/header→parameters、body raw/urlencoded/formdata→requestBody（formdata file→binary）、最小 200 响应、info.name→info.title；无 request → 结构化失败。黄金用例 + 集成 + e2e ✔。
- [x] J2 导入 Swagger 2.0 存量 → 自研实现 2.0→3.1 结构化升级 ✅ 2026-11-15：`Swagger20Upgrader`（kernel，tree 层结构升级，源文件从不回写）：`swagger→openapi:3.1.0`、`host/basePath/schemes→servers`、`definitions→components.schemas` + `$ref` 重写、顶层 parameters/responses→components、`securityDefinitions→components.securitySchemes`（basic/apiKey/oauth2 flow）、operation 内 `in:body→requestBody`（consumes→media）、`in:formData 组→urlencoded object schema`、query/header/path 内联 type→schema、`collectionFormat multi→style=form`、响应 `schema→content.<produces>`；`SpecParser.parse` 对 SWAGGER_20 由「明确拒绝」改为结构化升级（附 STRUCTURAL 升级日志）。黄金用例 + 旧拒绝测试更新。
- [x] J3 导出 OpenAPI 3.1 / 3.0 降级 / Postman ✅ 2026-11-15：`GET /api/repos/{id}/export/{openapi31|openapi30|postman}?stage=`（draft/release，消费默认读 release，§6.4 决策 5）；`ExportService.reconstructOpenApi` 从索引投影重建完整 OpenApiDoc（paths+operation 合并、components.schemas）；`OpenApi30Exporter` 3.1→3.0 有损降级（type 数组→nullable、exclusive* 数值→布尔近似、清 3.1 独有关键字）；`PostmanExporter` OpenApiDoc→collection v2.1。**导入落点**（用户拍板）：`POST /api/import/{postman|swagger2}` body{content, repoId?, path?}——验证归一化（返回 3.1 YAML 预览 + apiCount）+ 可选 SpecWriter 写回真源（git commit+push）再摄取，存量文档真正收编进 git；写回需 editor+。顺带 Ingestor 对多 spec 文件同 method+path 去重（先到保留）。测试：kernel ImportExportUpgradeTest 4 用例 + server ImportExportApiTest 4 用例；e2e 8 项✔；**测试基建**：6 个 web 测试类改用 companion 静态 `TEMPDIR`（createTempDirectory）+@DynamicPropertySource 每类全新投影目录，根除 SQLITE_BUSY 与跨运行残留。
- [x] J4 导出 Markdown / docx(pandoc 子进程) / PDF / 静态 HTML 门户 → `artifacts` 卷 ✅ 2026-11-15：`MarkdownExporter`（kernel：标题/描述/服务端/每接口参数表+请求体+响应 schema 递归摘要，IntelliJ 可复用）；`HtmlPortalExporter`（kernel：自包含单页门户——预渲染接口折叠块 + 方法色 pill + 内联 CSS + vanilla JS 搜索过滤，无外部依赖/单文件离线可开，可对外发布）；`GET /api/repos/{id}/export/{markdown|html}?stage=`（复用 reconstructOpenApi，release 即发布快照）；`POST /api/repos/{id}/document/{docx|pdf}`（MarkdownExporter → **pandoc 子进程**转 docx/pdf → 落 `artifacts` 卷 `docs/` + 文件流下载；pandoc 软依赖：未安装 → 503 友好提示，装 pandoc 即解锁）。测试：kernel MarkdownExport/HTML 黄金断言 + server markdown/html 集成 + docx/pdf 软依赖容错（503）；e2e 9 项✔（Markdown 结构/HTML 门户含搜索与 pill/docx-pdf 软依赖）。⚠ 导出信息由平台生成（repo 名），原 spec 的 info 描述不随文档导出（可后续增强接入 spec info）。

### K AI 文档层（Doc 域） [modules/server + apps/web]

- [x] K1 `docs/` 域 Markdown 摄取入索引（与 Api 域隔离，§5.7 / §6.2）→ E3 ✅ 2026-11-15：Ingestor 扫描 `docs/<service>/` 下 `.md/.markdown` → `replaceDocsForRepo` 入 doc 表（UNIQUE repo+path 幂等）；front-matter 解析（title/related），title 兜底首个 `#` 或文件名；`IngestResult.docCount`；摄取随 spec 同链路（注册即摄取含 Doc）。
- [x] K2 全文检索 FTS5 trigram（中文子串匹配）✅ 2026-11-15：migrate 新增 `doc_fts` FTS5 trigram 虚拟表（环境 sqlite-jdbc 支持则建，捕获 SQLException 优雅回退）；`store.searchDocs(q, repoId)` FTS5 MATCH（带引号短语，防注入 + 子串）优先、**LIKE 兜底/合并**（中文子串天然命中，经 db.fts5Available 标志判断）；spec-index 用例 + DocApiTest 集成验证「处理器/分页」命中、无关词不命中；e2e 3 项 ✔。
- [x] K3 双入口完善：目录树 / 渲染 / 评论 / Doc↔Api 链接 ✅ 2026-11-15：`DocController`（GET /api/docs?repoId=&q= 清单/检索、GET /{id} 详情、GET/POST /{id}/comments 复用 comment 表 doc_id 寻址）；front-matter `related` → 正文追加「关联接口」节（`[`/pets`](#/api/pets)`）；前端 DocPanel 接真后端（目录树=docs 路径、检索框防抖、react-markdown 渲染 + TOC、评论、关联接口链接拦截跳接口详情）；侧栏 Doc 域改由后端 docs 驱动（searchDocs/ listDocs）。测试：SpecIndexStoreTest Doc 检索 + DocApiTest 2 用例（摄取/检索、详情/关联节/评论）；e2e 9 项✔。**P2 全部完成**。

**P2 验收**：团队从 Web / AI / CLI 三端日常使用不退出；mock 与 spec 一致；订阅可感知结构变更。

---

## P2.5 · 多项目骨架（ADR 阶段 A）

> **详细任务拆解**：[SpecHub-阶段A任务拆解.md](SpecHub-阶段A任务拆解.md)（A0–A10，含依赖、文件落点、验收门禁）
> **依据**：[SpecHub-ADR-001-管理模型与多租户](SpecHub-ADR-001-管理模型与多租户.md)（已确认，2026-11-17）
> **状态**：✅ 完成（2026-11-19）——A‑1 安全底座先于 A‑2 可用骨架，门禁均通过

- [x] A0–A4 安全底座：迁移 / 领域层 / 租户上下文 / 读端点隔离（✅ 2026-11-17）
- [x] A5–A8 可用骨架：项目 CRUD / 多项目成员 / 仓库 binding / 前端按项目加载（A5–A6 ✅ 2026-11-17；A7–A8 ✅ 2026-11-18）
- [x] A9–A10 测试门禁与收尾：双库迁移、隔离测试、文档（✅ 2026-11-18/19）

**P2.5 验收**：用户只在项目 A 时，列表 / 详情 / 搜索 / 导出 / 评论 / 评审 / 订阅均看不到项目 B；每个仓库恰好一条 `active` binding；dev 免登录行为不回归。✅ 2026-11-19：`ReadIsolationTest`、`ManagementDbMigrationTest`（PostgreSQL / MySQL）与 `./gradlew check` 全部通过。

---

## P2.6 · 团队与跨团队订阅（ADR 阶段 B）

> **详细任务拆解**：[SpecHub-阶段B任务拆解.md](SpecHub-阶段B任务拆解.md)（B0–B8，含依赖、文件落点、安全门禁）
> **依据**：[SpecHub-ADR-001-管理模型与多租户](SpecHub-ADR-001-管理模型与多租户.md) §3.3 / §4.1 / §4.2 / §8.2 / §9 / §10
> **状态**：🟡 进行中（B1–B6 完成 2026-11-19）

- [ ] B0 前置对齐与决策（团队权限、通知可见性、旧 `team` 列处置）
- [x] B1 管理库 V4 迁移（`team` / `team_member`、`subscription.team_id` 过渡回填、`notification.team_id`、同名合并；双库迁移测试通过）
- [x] B2 团队领域层与服务（`TeamService`、订阅/通知实体改造、Ingestor 钩子、V5 收紧迁移；双库全量测试与 ktlint 通过）
- [x] B3 团队 API（团队 CRUD、成员管理、`me.teams`；双库全量测试与 ktlint 通过）
- [x] B4 订阅 API 切换 `teamId`（team lead 权限、旧字段 400；双库全量测试与 ktlint 通过）
- [x] B5 通知按团队投递与可见性（仅团队成员 / org_admin；双库全量测试与 ktlint 通过）
- [x] B6 前端团队管理与订阅 Tab 改造（`apps/web` 构建通过；浏览器手测随 B7）
- [ ] B7 测试矩阵与双库验证（迁移、`TeamApiTest`、订阅/隔离测试）
- [ ] B8 文档与收尾

**P2.6 验收**：创建团队、加成员、绑定订阅；团队改名后订阅仍有效；通知只投递给团队成员。迁移在 PostgreSQL / MySQL 双库通过，dev 免登录行为不回归。

---

## P3 · 放大差异化

### L IDE 插件

- [ ] L1 IntelliJ（Kotlin）：**复用 kernel jar**（spec-model/parser）→ 路由/注解扫描生成 spec → 一键 push → hover 显示定义 [plugins/intellij] → B C
- [ ] L2 VSCode（TS）：复用 `spec-client` / CLI [plugins/vscode] → G

### M AI 同步 + 契约测试 [modules/server + apps/cli]

- [ ] M1 代码 ↔ spec ↔ 响应一致性检查服务 [modules/server]
- [ ] M2 spec-driven 契约测试（先"响应校验"档，O3 收敛口径）[modules/server + apps/cli]

### N 云托管与协作 [modules/server + compose]（视 O1 而定）

- [ ] N1 K8s 部署演进 + 租户隔离（复用 compose 卷模型，§7.1）
- [ ] N2 协作计费（团队 / 席位）

**P3 验收**：IDE 内 hover 即见契约；契约测试能暴露"文档是假的"。

---

## 横向贯穿（各阶段同步）

- **测试**：解析器黄金用例集（3.0/3.1 全覆盖；2.0 于 P2 J2 补齐）[modules/kernel/spec-parser]；e2e 链路（真 git 仓 → 摄取 → 查询 → mock）[e2e]；Gradle 单测随模块
- **CI**：GitHub Actions 建 `build + test + 镜像构建推送`（CLI 交叉编译产物一并产出）[根]
- **托管适配器**：P0 先 Gitea + 本地目录两档；P1 加 GitHub / GitLab webhook 适配（§7.3 落地为代码）[modules/server]
- **文档**：每个工作包完成时同步 [docs]

---

## 关键依赖链

```
A4/E1 ─▶ E3 ─┬─▶ F(MCP) ── P1 双写
             └─▶ H(Web)
E3 ─▶ I6 ─▶ I7 ─▶ I8     （P2 协作骨架）
P2 ─▶ P2.5(A) ─▶ P2.6(B) ─▶ P3（多项目骨架 → 团队 → 平台化）
C ─▶ D ─▶ E3              （P0 摄入主线）
B/C ─▶ L1                  （IntelliJ 复用内核）
```

**风险前置**：C 组自研解析（Jackson 3 直读 + 3.0→3.1 语义升级）是确定性工程，解析器黄金用例集（3.0/3.1）P0 首批补齐；2.0 结构化升级（原 C2 一部分）整体推迟 P2 J2，不确定性收敛到导入件，不再阻塞 P0 主线。

---

## P0 首批排期建议（开工顺序）

1. A1–A4 骨架（半天~1天）——目录与环境先定死 ✅ 已完成（2026-11-14）
2. B1–B3 模型 + C1–C4 归一化（映射成本先暴露）
3. D1–D4 git 封装
4. E1–E4 摄取主线贯通（先本地目录仓可走通）
5. E5–E8 重建 / webhook / 轮询 / 初始化