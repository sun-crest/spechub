# SpecHub 开发计划

> **关联**：主线方案见 [SpecHub-产品方案.md](SpecHub-产品方案.md)（产品真源）；本文件是**开发任务拆解真源**，随进度勾选更新。
> **日期**：2026-11-12　**状态**：P0 开发中（A/B/C/D/E 主线完成，E2/E7 待补）

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

- [x] A1建 monorepo 目录树 `modules/ apps/ compose/ docs/ e2e/` 与根 README [根]
- [x] A2Gradle（Kotlin DSL）根构建 + `gradle/libs.versions.toml` 版本目录；锁定 Java 25 / Spring Boot 4 当前 GA 小版本 [根]
- [x] A3`kernel/spec-model` 空壳 + JVM 侧 kernel 四模块占位（`spec-parser / git-store / spec-index`）[modules/kernel]
- [x] A4compose 蓝图 v1：`backend`（空 Spring 应用）+ `gitea` + 四个命名卷 + `.env.example` [compose]

### B OpenAPI 3.1 内部模型 [modules/kernel/spec-model]

- [x] B1核心模型类：`OpenApiDoc / Operation / PathItem / Schema / Component / Server`（Kotlin data class，空安全）
- [x] B2JSON / YAML 序列化（Jackson + jackson-dataformat-yaml，支持单/多文档）
- [x] B3归一化模型指纹 `specHash`（作为 Version 发布快照的主键，见 P2 I6）

### C 多版本归一化 [modules/kernel/spec-parser] → B

- [x] C1集成 swagger-parser，自动探测 `swagger: "2.0"` / `openapi: "3.0"` / `"3.1"`
- [x] C2升级映射：`definitions → components/schemas`、`nullable → type:[...,null]`、`exclusiveMin/Max` 布尔→数值、`consumes/produces → requestBody` 等（§6.5）
- [x] C3升级变更日志输出（"自动转换 / 待人工复核"标注，对接评审流状态）
- [x] C4解析失败容错与错误收集（坏 spec 隔离，不拖垮整次摄取）

### D git 化读写 [modules/kernel/git-store]

- [x] D1系统 git CLI 子进程封装（参数化安全、超时、`--no-pager`）
- [x] D2`clone / fetch` 镜像远程仓到 `gitsource/repos/<org>/<repo>`（§7.3）
- [x] D3`commit / push` 写回真源仓（Web 直改与平台提交，R1-C 兜底形态）
- [x] D4`diff / status` 提取 spec 变更（喂给 ChangeLog）

### E 摄取链路 + 索引（P0 核心） [modules/kernel/spec-index + modules/server]

- [x] E1Spring Boot 4 应用骨架（引导类、`SPECHUB_*` 配置绑定、单测基建）[modules/server] → A2 A4
- [ ] E2 Repo 注册 REST：添加仓地址 / 分支 / 凭据注入 [modules/server]
- [x] E3摄取器主线：`git-store → spec-parser → 索引`（增量 + 幂等续跑）[modules/server] → C D
- [x] E4SQLite 表：`project / repo / api / schema / doc / version` + 唯一键 + api↔schema 引用 [modules/kernel/spec-index]
- [x] E5全量重扫命令 `reindex`（验证"索引可重建"纪律，§6.4 决策 7）[modules/server]
- [x] E6webhook 接收端点（内容变更触发增量摄取）[modules/server]
- [ ] E7 轮询兜底（未配 webhook 的仓定时 `fetch`+diff）[modules/server]
- [x] E8首次启动初始化：目录、DB、admin 种子命令（UI 化在 P1 H6）

**P0 验收**：对一个小 git 仓触发 push → SQLite 查到归一化的 api 记录；`docker compose up -d --build` 起 gitea+backend；`reindex` 可整体重建。

---

## P1 · 双写闭环（目标：AI 与人双写不打架）

### F MCP server（TS，当前推荐） [apps/mcp] → E

- [ ] F1 脚手架 + MCP TS SDK（streamable HTTP，remote 优先；stdio 调试用）[apps/mcp]
- [ ] F2 工具集 v1：`list_projects / search_api / get_api / create_api / update_api / doc_sync_status` [apps/mcp]
- [ ] F3 权限分层：默认只读、写操作需确认（对接 server 审批开关，§6.4 决策 3）

### G CLI（Go） [apps/cli] → E

- [ ] G1 cobra 命令 `pull / push / status / diff / commit` v1 [apps/cli]
- [ ] G2 与 server HTTP 同步（token 配置、push 前校验走 server API）
- [ ] G3 交叉编译发布脚本（linux/darwin, amd64/arm64 → 单二进制）

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
- [ ] I3 Mock 服务（WebFlux）独立成 compose 服务 [modules/mock] → E4 I2
- [ ] I4 RBAC（owner / admin / editor / viewer）
- [ ] I5 评论 / 变更记录
- [ ] I6 draft → release 双层版本 + 发布快照（consumer 默认读 release）→ E3
- [ ] I7 订阅：消费团队声明依赖 → 结构变更通知 → I6
- [ ] I8 评审审批流（"写操作皆审"落地，§6.4 决策 3）

### J 导入导出 [modules/kernel/spec-parser + modules/server]

- [ ] J1 导入 Postman collection（验证归一化管道）→ C
- [ ] J2 导入 Swagger 2.0 存量 → C
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

- **测试**：解析器黄金用例集（2.0/3.0/3.1 对拍）[modules/kernel/spec-parser]；e2e 链路（真 git 仓 → 摄取 → 查询 → mock）[e2e]；Gradle 单测随模块
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

**风险前置**：C2（2.0→3.1 归一化映射）是唯一高不确定任务，P0 一开工就放第一批，早暴露边界成本。

---

## P0 首批排期建议（开工顺序）

1. A1–A4 骨架（半天~1天）——目录与环境先定死
2. B1–B3 模型 + C1–C4 归一化（映射成本先暴露）
3. D1–D4 git 封装
4. E1–E4 摄取主线贯通（先本地目录仓可走通）
5. E5–E8 重建 / webhook / 轮询 / 初始化