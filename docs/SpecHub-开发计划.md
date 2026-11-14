# SpecHub 开发计划

> **关联**：主线方案见 [SpecHub-产品方案.md](SpecHub-产品方案.md)（产品真源）；本文件是**开发任务拆解真源**，随进度勾选更新。
> **日期**：2026-11-14　**状态**：**P0 完成 + P1 F 组（MCP server）完成**——A–E 六组 40 测试全绿、部署实测两档通过；F 组 MCP（TS + streamable HTTP/stdio + 读默认写需开 + 写回 REST）e2e 实证（create_api 写回 gitea → 摄取 → 可检索）。Horizontal 下一个：G 组 CLI / H 组 Web（2026-11-13 四项技术决策定案）

---

## 0. 里程碑总览

| 阶段 | 目标 | 工作包 | 验收一句话 |
|---|---|---|---|
| **P0** | 存储内核 | A · B · C · D · E | Claude 能直接读文档；push 一个仓，SQLite 查得到 api |
| **P1** | 双写闭环 | F · G · H | AI 与人双写同一份 spec 不打架 |
| **P2** | 平台化 | I · J · K | 团队日常愿意用 |
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

- [ ] H1 脚手架 + 布局（**双入口**：接口主 / 文档辅，§5.3）
- [ ] H2 项目列表 / 接口列表（读）
- [ ] H3 接口详情 + Markdown 渲染（Doc 域预览）
- [ ] H4 编辑即提交：改 spec 文件 → commit → push（走 git-store）[apps/web]
- [ ] H5 Monaco 双栏编辑器（YAML / JSON + schema 表单雏形）
- [ ] H6 登录 + admin 初始化（对接 E8）

**P1 验收**：人用 Web、Claude 用 MCP 读同一份 spec，双写无冲突；CI 里 `spechub check` 可跑通。

---

## P2 · 平台化（目标：团队日常愿意用）

### I Web 完善与协作 [apps/web + modules/server]

- [ ] I1 在线调试 Try it out（读 spec 发请求）[modules/server]
- [ ] I2 Mock 规则引擎（pattern / value / script）[modules/server] → I3
- [ ] I3 Mock 并入 backend（**MVC + Java 25 虚拟线程**，不引 WebFlux）；`modules/mock` 为依赖 kernel 的独立模块、部署并入 backend 进程 [modules/mock + modules/server] → E4 I2
- [ ] I4 RBAC（owner / admin / editor / viewer）
- [ ] I5 评论 / 变更记录
- [ ] I6 draft → release 双层版本 + 发布快照（consumer 默认读 release）→ E3
- [ ] I7 订阅：消费团队声明依赖 → 结构变更通知 → I6
- [ ] I8 评审审批流（"写操作皆审"落地，§6.4 决策 3）

### J 导入导出 [modules/kernel/spec-parser + modules/server]

- [ ] J1 导入 Postman collection（验证归一化管道）→ C
- [ ] J2 导入 Swagger 2.0 存量 → 自研实现 2.0→3.1 结构化升级（承接原 C2，产品方案 §6.5）
- [ ] J3 导出 OpenAPI 3.1 / 3.0 降级 / Postman
- [ ] J4 导出 Markdown / docx(pandoc 子进程) / PDF / 静态 HTML 门户 → `artifacts` 卷

### K AI 文档层（Doc 域） [modules/server + apps/web]

- [ ] K1 `docs/` 域 Markdown 摄取入索引（与 Api 域隔离，§5.7 / §6.2）→ E3
- [ ] K2 全文检索 FTS5 trigram（中文子串匹配）
- [ ] K3 双入口完善：目录树 / 渲染 / 评论 / Doc↔Api 链接

**P2 验收**：团队从 Web / AI / CLI 三端日常使用不退出；mock 与 spec 一致；订阅可感知结构变更。

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