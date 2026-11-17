# SpecHub · 阶段 A 任务拆解（多项目骨架）

> **日期**：2026-11-17　**状态**：待开工
> **依据**：[SpecHub-ADR-001-管理模型与多租户](SpecHub-ADR-001-管理模型与多租户.md)（已确认）
> **范围**：单组织内的多项目骨架——项目管理、多项目成员、读隔离、仓库归属项目、Git single-primary binding。
> **不覆盖**：团队/分组（阶段 B）、密码/SSO/服务账号（阶段 C）、多租户 SaaS 控制面与外部 Git 连接器（阶段 D1）、自助注册（阶段 D2）。

---

## 1. 目标与完成定义

**目标**：把当前「单 default 项目」实现升级为「单组织、多项目」的最小可用平台，并在开放多项目 UI 之前先完成读隔离。

**阶段 A 完成定义（DoD）**：

- [ ] 项目可创建、改名、归档、删除、转移 owner、设置可见性；
- [ ] 用户可在多个项目拥有不同角色，项目成员可增删改；
- [ ] 仓库必须归属项目，注册时携带 `projectId`；
- [ ] 每个逻辑仓库恰好一条 `active` binding，managed/local 可用，单主约束生效；
- [ ] 读接口全部按「可见项目 + 可见性 + stage」过滤，不可见返回 404；
- [ ] 用户只在项目 A 时，列表 / 详情 / 搜索 / 导出 / 评论 / 评审 / 订阅均看不到项目 B；
- [ ] 未启用鉴权的 dev 模式行为不回归；
- [ ] 管理库迁移在 PostgreSQL 18.6 与 MySQL 8.4 上均通过。

**明确不做**：`team` 实体、多组织切换 UI、密码/SSO、服务账号、外部 Git provider 连接器、`repo_mirror`、平台运营控制面。

---

## 2. 子阶段与任务总览

阶段 A 拆成两个可独立验收的子阶段：**A‑1 安全底座** 必须先于 **A‑2 可用骨架** 开放多项目入口。

### 2.1 A‑1 安全底座

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| A0 | 前置对齐与 ADR 修正 | — | S | 决策记录、ADR §10 措辞修正 |
| A1 | 管理库 V3 迁移 | A0 | L | `V3__multi_project.sql`（PG/MySQL） |
| A2 | 领域模型与 Service | A1 | L | Org/Project/GitConnection/RepoBinding |
| A3 | 租户上下文与权限判定 | A2 | M | `AuthService` / `TokenAuthFilter` |
| A4 | 读端点隔离 | A3 | L | 全部读 Controller + `ProjectionStore` |

**A‑1 验收门**：`ReadIsolationTest` 全绿；UI 仍保持单项目入口（或由开关隐藏项目切换）。

### 2.2 A‑2 可用骨架

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| A5 | 项目 CRUD API | A3 | M | `ProjectController` / `ProjectService` |
| A6 | 多项目成员 API + UI | A5 | M | 项目成员端点、`MembersDialog` 改造 |
| A7 | 仓库注册 + binding API | A2/A5 | L | `RepoController`、`RepoBindingService` |
| A8 | 前端多项目骨架 | A5–A7 | M | `App.tsx` / `api.ts` |
| A9 | 测试矩阵与双库迁移验证 | 全程 | L | 集成测试、e2e |
| A10 | 文档、开关与收尾 | A9 | S | 开发计划、README、ADR 修正 |

---

## 3. 任务详情

### A0 · 前置对齐与 ADR 修正（S）

- [ ] 确认并记录：`app_user` 当前已是全局身份（无 `org_id`，`username` 全局唯一），ADR §10 的「去掉 `app_user.org_id`」改为「就地升级为全局身份 + 新增 `org_membership`」。
- [ ] 确认阶段 A 范围：只加 `org_membership`，不加 `email` / `mfa_enabled` / 密码登录（留阶段 C）。
- [ ] 确认 `repo_binding.connection_id` 在阶段 A 允许为空；现有仓库先给 `managed` / `local` binding，外部 connection 留 D1。
- [ ] 确认项目 slug 规则：小写、`[a-z0-9-]`、长度 2–64、组织内唯一。
- [ ] 确认语义：`visibility` 默认 `private`；不可见资源统一 404；`internal` 非成员只读 release。
- [ ] 确认项目删除策略：先归档、再删除；删除需 owner 二次确认并写 changelog。

**产出**：ADR §10 措辞修正 + 本节勾选完成。

### A1 · 管理库 V3 迁移（L）

迁移文件：`modules/server/src/main/resources/db/migration/{postgresql,mysql}/V3__multi_project.sql`（具体版本号按现有序列命名）。

**新增表**

- [ ] `org`：`id/name/slug/status/created_at`；bootstrap 一行 `default`。
- [ ] `org_membership`：`id/user_id/org_id/org_role/status/created_at`；唯一 `(user_id, org_id)`；`org_role ∈ org_admin | org_member`。
- [ ] `git_connection`：`id/org_id/provider/external_account_id/display_name/credentials_enc/webhook_secret_enc/status/created_at`；阶段 A 允许只建表，不要求外部 provider。
- [ ] `repo_binding`：`id/repo_id/kind/connection_id/external_repo_id/remote_url/branch/status/created_at/retired_at`；`kind ∈ managed|external`，`status ∈ candidate|active|retired`；**同一 `repo_id` 至多一条 `active`**（PG 部分唯一索引；MySQL 可空标志位 + 唯一索引）。
- [ ] `org_domain` 可留到 D2，不阻塞 A。

**改表**

- [ ] `project`：加 `org_id/slug/description/visibility/owner_id/archived`；唯一 `(org_id, slug)`；现有 `default` 回填。
- [ ] `repo`：加 `connection_id`（可空）、`external_repo_id`；现有 URL 回填 `sha256(url)`；保留 `(project_id, url)` 唯一键到 D1。
- [ ] `membership`：补 `project(id)` 外键与索引（project 已在管理库）。
- [ ] `repo/comment/subscription/notification/review/mock_rule/changelog/release_marker`：加 `org_id` 并回填默认 org，补 `(org_id, ...)` 索引。
- [ ] `app_user`：不删除字段；为所有现有用户写 `org_membership`；`is_platform_admin=1` → `org_admin`。

**回填与兼容**

- [ ] 先建默认 org（`name=default`、`slug=default`），再回填所有 `org_id`。
- [ ] `project.owner_id`：默认项目取平台 admin 用户。
- [ ] 每个现有 `repo` 创建一条 `active` binding：`kind=managed`（或 `local`），`branch` 取 `repo.branch`，`connection_id` 可空。
- [ ] 迁移可重复执行（幂等）或由 Flyway 保证一次性；不删除现有数据。

**验收**

- [ ] PostgreSQL 18.6：现有 V2 数据升级成功，约束生效。
- [ ] MySQL 8.4：同上。
- [ ] 空库从 V1 直接跑到 V3。
- [ ] 单主约束：插入第二条 `active` binding 失败。

### A2 · 领域模型与 Service（L）

**Repository / Entity**

- [ ] `OrgEntity` / `OrgRepository`（`findBySlug`、`findAll`）。
- [ ] `OrgMembershipEntity` / `OrgMembershipRepository`（按 user、按 org 查询；集合式删除）。
- [ ] `GitConnectionEntity` / `GitConnectionRepository`（按 org 查询）。
- [ ] `RepoBindingEntity` / `RepoBindingRepository`（`findByRepoId`、`findActiveByRepoId`、批量查询）。
- [ ] 扩展 `ProjectEntity`（当前只有 `id/name/createdAt`）与 `RepoEntity`（补 `connectionId/externalRepoId/orgId`）。

**Service**

- [ ] `OrgService`：bootstrap 默认 org、查询当前 org、列出 org 成员。
- [ ] `ProjectService`：CRUD、归档、可见性、owner 转移、slug 唯一校验、删除前归档校验。
- [ ] `RepoBindingService`：`createCandidate` / `verify` / `activate` / `retire` / `deleteRetired` / `resolveActive`。
  - `activate`：事务内 flip，旧 `active` → `retired`、候选 → `active`；并发冲突返回 409。
  - `verify`：内容比对，返回 `empty / identical / diverged`（diverged 附 diff 摘要）。
- [ ] `RepoService`：`register(projectId, ...)` 必须带项目；`listByProjectIds`；移除写路径里的 `DEFAULT_PROJECT`。
- [ ] `UserService`：org membership 查询/写入；现有 `setMembership/membershipRole/membersOfProject` 保留。
- [ ] 写路径回填 `org_id`：comment / subscription / review / mock_rule / changelog / release。

**纪律**

- [ ] `@Transactional` 只包管理库多表写；git/网络/子进程不进入事务（见 [事务边界审计](SpecHub-事务边界审计.md)）。
- [ ] 集合式删除，不在循环内逐条数据库往返。

### A3 · 租户上下文与权限判定（M）

- [ ] `AuthService` 增加 `currentOrg`（阶段 A 为默认 org）。
- [ ] `effectiveRole` 纳入 `org_admin`、项目 `membership`、项目 `visibility`。
- [ ] 新增 `canReadProject(user, project, stage)`：
  - 项目成员：draft + release；
  - 同组织非成员：仅 `internal` / `public` 的 release；
  - 匿名：仅 `public` 的 release（平台 API 仍按鉴权策略）。
- [ ] 新增 `requireProjectVisible`（不可见 404）与 `requireProjectRole`（可见但不足 403）。
- [ ] `TokenAuthFilter`：鉴权成功挂 user + org context；dev 免登录行为不变。
- [ ] `GET /api/auth/me`：返回 `orgs`、`currentOrg`、`orgAdmin`、当前项目角色；前端据此计算写入口。
- [ ] 现有 `platform_admin` 映射为 `org_admin`，旧 admin-token 登录保持兼容。

### A4 · 读端点隔离（L，安全核心）

**投影层**

- [ ] `ProjectionStore.listAllApis` 增加 `repoIds: Collection<Long>` 参数。
- [ ] `ProjectionStore.listDocs` / `searchDocs` 支持 `repoIds` 集合。
- [ ] **空集合返回空结果，绝不退化为全量**；所有调用方必须显式传可见 repo 集合。

**端点**

| 端点 | 处理 |
|---|---|
| `GET /api/projects` | 只返回当前用户可见项目 |
| `GET /api/repos`、`/api/repos/{id}` | 按可见项目过滤 / 校验 |
| `GET /api/apis`、`/api/apis/{id}`、`/api/repos/{id}/apis` | 按可见 repo 过滤；详情按 repo→project 校验 |
| `GET /api/docs`、`/api/docs/{id}` | 多 repo 过滤；详情校验 |
| `GET /api/changelog`、`/api/apis/{id}/changelog` | 按可见 repo 过滤；org 级动作仅 org_admin |
| `GET /api/reviews`、`/api/reviews/{id}` | 按可见 repo 过滤 |
| `GET /api/subscriptions`、`/api/subscriptions/notifications` | 按可见 repo 过滤 |
| `GET /api/repos/{id}/export/*`、`/api/repos/{id}/document/*` | 按可见性 + stage 校验 |
| `/mock/{repoId}/**` | 鉴权启用时按项目可见性；dev 保持放行 |
| `POST /api/apis/{id}/try`、评论、写回 | 按 repo→project 校验 |

- [ ] 逐端点补 404/403 语义：不可见 404，可见但角色不足 403。
- [ ] 写路径的 `requireRepoRole` 改为能解析 repo→project→org。
- [ ] 新增 `ReadIsolationTest`：用户只在 A，覆盖列表、详情、搜索、导出、评论、评审、订阅。

> **A‑1 门禁**：A4 全绿之前，不开放项目切换 UI；可用 `spechub.multi-project.enabled=false` 隐藏入口。

### A5 · 项目 CRUD API（M）

- [ ] `GET/POST /api/projects`；`GET/PATCH/DELETE /api/projects/{id}`。
- [ ] `POST /api/projects/{id}/transfer`（转移 owner）。
- [ ] `POST /api/projects/{id}/archive`（归档）。
- [ ] 可见性变更：`PATCH` body 携带 `visibility`。
- [ ] 权限：`org_admin` 创建；`project owner` 改设置/转移/删除；`admin` 按需要可改可见性。
- [ ] 校验：slug 唯一、owner 必须是项目成员、删除前必须归档。
- [ ] 项目级 changelog（`repo_id=null`，带 `org_id`）。
- [ ] `ProjectApiTest`：CRUD、权限、slug 冲突、归档/删除、owner 转移。

### A6 · 多项目成员 API + UI（M）

**后端**

- [ ] `GET/POST/PATCH/DELETE /api/projects/{id}/members`。
- [ ] 替换 `AuthController` 中全部 `DEFAULT_PROJECT` 硬编码（[AuthController.kt](modules/server/src/main/kotlin/io/spechub/server/web/AuthController.kt)）。
- [ ] `GET /api/auth/users` 改为组织维度（org_admin 可见）；项目成员走项目端点。
- [ ] 成员变更写 changelog。

**前端**

- [ ] `MembersDialog` 接收 `projectId`，不再默认项目。
- [ ] 项目切换器接真实 `onSelect`（当前为空实现，[App.tsx](apps/web/src/App.tsx)）。
- [ ] 账号菜单的「成员与角色」按当前项目角色显示。
- [ ] `ProjectMembershipApiTest` + 手动 UI 验证。

### A7 · 仓库注册 + binding（L）

- [ ] `POST /api/repos` 必须携带 `projectId`；创建逻辑 repo + 首个 `active` binding（`managed` 或 `external`）。
- [ ] `GET/POST /api/repos/{id}/bindings`；`POST .../verify`；`POST .../activate`；`POST .../retire`；`DELETE`（仅 retired）。
- [ ] `verify`：空 → 允许推送当前内容；一致 → 可 flip；不一致 → 返回 diff，要求显式确认。
- [ ] `activate`：事务内 flip；并发 409；旧 binding 进入回滚窗口（默认 30 天，可配置）。
- [ ] 摄取 / Webhook / 写回只认 `active` binding；`retired` 不可读写。
- [ ] 前端仓库注册表单增加项目选择（可并入 A8）。
- [ ] `RepoBindingApiTest`：单主约束、并发 flip、verify 三分支、flip 后评论/订阅/评审不变。

### A8 · 前端多项目骨架（M）

- [ ] `App.tsx` 增加 `selectedProjectId`，`load()` 按项目拉取 repos/apis/docs（当前为全量，[App.tsx](apps/web/src/App.tsx)）。
- [ ] 项目下拉可用；项目创建/设置/归档入口；空状态引导。
- [ ] `api.ts` 增加项目 CRUD、成员、binding 的类型与调用。
- [ ] 角色可见性按当前项目重新计算，不再依赖 `me.role`（当前是 default 项目角色）。
- [ ] 侧栏「分组」保持 API tag 语义，不改成组织分组。
- [ ] 前端 build + 手动 e2e。

### A9 · 测试矩阵与双库验证（L）

- [ ] 迁移测试：PostgreSQL + MySQL 各跑「V1/V2 有数据 → V3」。
- [ ] 新增：`ProjectApiTest`、`ProjectMembershipApiTest`、`RepoBindingApiTest`、`ReadIsolationTest`。
- [ ] 更新：`AuthApiTest`、`WebApiTest`、`CommentApiTest`、`ReviewApiTest`、`SubscriptionApiTest`、`ImportExportApiTest` 中的默认项目假设。
- [ ] e2e：两个项目、用户只在 A；列表/详情/搜索/导出/评论/评审/订阅全隔离。
- [ ] 门禁：`./gradlew check`；`apps/web` 构建；dev 免登录回归。
- [ ] 双库命令：`./gradlew :server:test -Dspechub.test.db=mysql`（见 [TestManagementDb.kt](modules/server/src/test/kotlin/io/spechub/server/web/TestManagementDb.kt)）。

### A10 · 文档、开关与收尾（S）

- [ ] 开发计划 P2.5 勾选与链接更新。
- [ ] README 补多项目启动与种子数据说明。
- [ ] ADR §10 修正 `app_user` 措辞（见 A0）。
- [ ] 如需灰度，保留 `spechub.multi-project.enabled` 开关，A‑1 完成后才默认打开。

---

## 4. 关键路径与并行

**关键路径**：

```
A1 → A2 → A3 → A4 →（A‑1 门禁）→ A5/A6/A7 → A8 → A9/A10
```

**两人并行建议**：

- 一人：A1 → A2 → A7（数据与 binding）。
- 一人：A3 → A4（权限与端点隔离，可按 Controller 分组）。
- A5/A6 在 A3 完成后开始；A8 等 A5–A7 的 API 契约稳定后开始。

**一人串行建议**：先 A1–A4 拿安全底座，再 A5–A8。

---

## 5. 安全门禁

- **门禁 1（A‑1）**：`ReadIsolationTest` 全绿之前，不开放多项目 UI；项目切换入口由开关隐藏。
- **门禁 2（A‑2）**：项目 CRUD 上线前，确认所有读端点已改为「按可见 repo 集合」查询；禁止任何 `repoIds=null` 退化为全量。
- **门禁 3**：`internal` / `public` 视图不返回 draft、评论、评审、mock 规则、通知。
- **门禁 4**：dev 免登录模式回归通过；生产鉴权模式默认拒绝。

---

## 6. 前置假设与待确认

| 项 | 建议 | 影响 |
|---|---|---|
| `org_membership` 是否在 A 落 | **是**（只加表，不加密码/邮箱） | 影响 A1/A3 |
| `repo_binding.connection_id` 是否允许空 | **允许**，D1 再补外部 connection | 影响 A1/A7 |
| 项目删除策略 | **先归档、再删除** | 影响 A5 |
| `app_user` 是否已全局 | **是**（当前无 `org_id`） | 影响 A0/A1 |
| `connection_id` 为空时摄取如何取 remote | 用 `repo.url` 兜底 | 影响 A2/A7 |

---

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 读端点漏过滤导致跨项目越权 | A4 统一入口 + `ReadIsolationTest` 覆盖全部列表/详情/搜索/导出；禁止空集合退化全量 |
| 迁移回填错误导致 org/project 关系错乱 | 迁移先建默认 org，再回填；双库测试；回填后校验数量与唯一约束 |
| binding flip 期间读写不一致 | candidate 不对外服务；flip 事务；全量重建索引；旧 binding 回滚窗口 |
| 前端仍按 `me.role`（default 项目）判断写权限 | 改为按 `selectedProjectId` 重新计算；A8 验收包含切换项目后的权限变化 |
| 现有测试大量假设 default 项目 | A9 统一更新；先跑全量测试再改 UI |

---

## 8. 交付物清单

- 管理库 V3 迁移（PG/MySQL）。
- Org / Project / GitConnection / RepoBinding 领域层与 API。
- 全部读端点的项目/可见性过滤。
- 多项目成员 API 与前端项目切换/成员管理。
- 仓库注册带 `projectId` + binding 生命周期 API。
- 集成测试与 e2e：`ReadIsolationTest`、`ProjectApiTest`、`ProjectMembershipApiTest`、`RepoBindingApiTest`。
- 开发计划 P2.5 勾选、README 多项目说明、ADR §10 修正。
