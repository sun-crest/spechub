# SpecHub · 阶段 A 任务拆解（多项目骨架）

> **日期**：2026-11-17（完成于 2026-11-19）　**状态**：✅ A0–A10 全部完成
> **依据**：[SpecHub-ADR-001-管理模型与多租户](SpecHub-ADR-001-管理模型与多租户.md)（已确认）
> **范围**：单组织内的多项目骨架——项目管理、多项目成员、读隔离、仓库归属项目、Git single-primary binding。
> **不覆盖**：团队/分组（阶段 B）、密码/SSO/服务账号（阶段 C）、多租户 SaaS 控制面与外部 Git 连接器（阶段 D1）、自助注册（阶段 D2）。

---

## 1. 目标与完成定义

**目标**：把当前「单 default 项目」实现升级为「单组织、多项目」的最小可用平台，并在开放多项目 UI 之前先完成读隔离。

**阶段 A 完成定义（DoD）**：

- [x] 项目可创建、改名、归档、删除、转移 owner、设置可见性；
- [x] 用户可在多个项目拥有不同角色，项目成员可增删改；
- [x] 仓库必须归属项目，注册时携带 `projectId`；
- [x] 每个逻辑仓库恰好一条 `active` binding，managed/local 可用，单主约束生效；
- [x] 读接口全部按「可见项目 + 可见性 + stage」过滤，不可见返回 404；
- [x] 用户只在项目 A 时，列表 / 详情 / 搜索 / 导出 / 评论 / 评审 / 订阅均看不到项目 B；
- [x] 未启用鉴权的 dev 模式行为不回归；
- [x] 管理库迁移在 PostgreSQL 18.6 与 MySQL 8.4 上均通过。

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

- [x] 确认并记录：`app_user` 当前已是全局身份（无 `org_id`，`username` 全局唯一），ADR §10 的「去掉 `app_user.org_id`」改为「就地升级为全局身份 + 新增 `org_membership`」。
- [x] 确认阶段 A 范围：只加 `org_membership`，不加 `email` / `mfa_enabled` / 密码登录（留阶段 C）。
- [x] 确认 `repo_binding.connection_id` 在阶段 A 允许为空；现有仓库先给 `managed` / `local` binding，外部 connection 留 D1。
- [x] 确认项目 slug 规则：小写、`[a-z0-9-]`、长度 2–64、组织内唯一。
- [x] 确认语义：`visibility` 默认 `private`；不可见资源统一 404；`internal` 非成员只读 release。
- [x] 确认项目删除策略：先归档、再删除；删除需 owner 二次确认并写 changelog。

**状态**：✅ 2026-11-17 完成；决策已同步到 ADR §4.1 / §7.4 / §9 / §10。

### A1 · 管理库 V3 迁移（L）

迁移文件：[postgresql/V3__multi_project.sql](modules/server/src/main/resources/db/migration/postgresql/V3__multi_project.sql)、[mysql/V3__multi_project.sql](modules/server/src/main/resources/db/migration/mysql/V3__multi_project.sql)。

**新增表**

- [x] `org`：`id/name/slug/status/plan/trial_ends_at/quota_json/suspended_reason/created_at`；bootstrap 一行 `default`。
- [x] `org_membership`：`id/user_id/org_id/org_role/status/created_at`；唯一 `(user_id, org_id)`；`org_role ∈ org_admin | org_member`。
- [x] `git_connection`：`id/org_id/provider/external_account_id/display_name/credentials_enc/webhook_secret_enc/status/created_at`；阶段 A 只建表，不要求外部 provider。
- [x] `repo_binding`：`id/repo_id/kind/connection_id/external_repo_id/remote_url/branch/status/created_at/retired_at`；`kind ∈ managed|external`，`status ∈ candidate|active|retired`；**同一 `repo_id` 至多一条 `active`**（PG 部分唯一索引；MySQL 生成列 `active_flag` + 唯一键）。
- [x] `org_domain` 留到 D2，不阻塞 A。

**改表**

- [x] `project`：加 `org_id/slug/description/visibility/owner_id/archived`；唯一 `(org_id, slug)`；现有 `default` 回填。
- [x] `repo`：加 `org_id`、`connection_id`（可空）、`external_repo_id`；现有 URL 回填 `md5(url)` 占位（D1 换 provider 稳定 ID）；保留 `(project_id, url)` 唯一键到 D1。
- [x] `membership`：补 `project(id)` 外键（V1 已有索引）。
- [x] `repo/comment/subscription/notification/review/mock_rule/changelog/release_marker`：加 `org_id` 并回填默认 org，补 `(org_id, ...)` 索引。
- [x] `app_user`：不删除字段；为所有现有用户写 `org_membership`；`is_platform_admin=1` → `org_admin`。

**回填与兼容**

- [x] 过渡策略：新列先允许 NULL，A2 写入路径补齐；回填保证现有数据可读、旧代码可启动。
- [x] 先建默认 org（`name=default`、`slug=default`），再回填所有 `org_id`。
- [x] `project.owner_id`：默认项目取平台 admin 用户（不存在则留空）。
- [x] 每个现有 `repo` 创建一条 `active` binding：`kind=managed`、`branch` 取 `repo.branch`、`connection_id` 为空。
- [x] 迁移由 Flyway 一次性执行；不删除现有数据。

**验收**

- [x] PostgreSQL 18.6：现有 V2 数据升级 + 回填验证通过（临时容器）。
- [x] MySQL 8.4：现有 V2 数据升级 + 回填验证通过（临时容器）。
- [x] 空库从 V1 直接跑到 V3（`AuthApiTest`，双库）。
- [x] 单主约束：插入第二条 `active` binding 失败（PG/MySQL 均验证）。

**状态**：✅ 2026-11-17 完成。

### A2 · 领域模型与 Service（L）

**Repository / Entity**

- [x] `OrgEntity` / `OrgRepository`（`findBySlug`、`findAll`）。
- [x] `OrgMembershipEntity` / `OrgMembershipRepository`（按 user、按 org 查询；集合式删除）。
- [x] `GitConnectionEntity` / `GitConnectionRepository`（按 org 查询）。
- [x] `RepoBindingEntity` / `RepoBindingRepository`（`findAllByRepoId`、`findByRepoIdAndStatus`、`countByRepoIdAndStatus`）。
- [x] 扩展 `ProjectEntity`（补 `orgId/slug/description/visibility/ownerId/archived`）与 `RepoEntity`（补 `orgId/connectionId/externalRepoId`）。

**Service**

- [x] `OrgService`：默认 org 幂等兜底、org membership 查询/写入/删除、`orgRole/isOrgAdmin`。
- [x] `ProjectService`：CRUD、归档、可见性、owner 转移、slug 唯一校验、删除前归档/无仓库校验、`ensureDefaultProjectOwner`。
- [x] `RepoBindingService`：`createCandidate` / `verify` / `activate` / `retire` / `deleteRetired` / `active` / `createActiveManaged` / `ensureActiveManaged`。
  - `activate`：事务内 flip，旧 `active` → `retired`、候选 → `active`；单主唯一约束兜底并发。
  - `verify`：元数据相同直接 `identical`；否则 clone 两侧做文件级快照比对，返回 `identical / diverged / no_active / error`（API 在 A7 暴露）。
- [x] `RepoService`：新增 `register(projectId, ...)`、`listReposByProjectIds`、`listReposByOrgId`、`orgIdOf`；注册时同时创建首条 active binding；旧 `register(projectName)` 保留兼容。
- [x] `UserService`：`bootstrapAdmin` 与新用户写 `org_membership`；删除用户先清组织关系；`orgRole/isOrgAdmin`；现有项目成员方法保留。
- [x] 写路径回填 `org_id`：comment / subscription / notification / review / mock_rule / changelog / release。
- [x] `GitConnectionService` 骨架：列表/创建/禁用；阶段 A 不接收密钥，KMS 加密留 D1。

**纪律**

- [x] `@Transactional` 只包管理库多表写；`RepoBindingService.verify` 的 git clone/比对不在事务内。
- [x] 集合式删除（`deleteAllByUserId`），不在循环内逐条数据库往返。

**测试**

- [x] `ProjectServiceTest`：创建/slug 唯一/可见性默认/归档后删除/有仓库拒绝删除。
- [x] `RepoBindingServiceTest`：注册自动 active、verify 元数据短路、activate 翻主、retire/delete 状态机。
- [x] `UserOrgMembershipTest`：bootstrap admin → org_admin、新用户 → org_member、删除用户清组织关系。
- [x] 上述测试 + 全量 `:server:test` 在 PostgreSQL 与 MySQL 双库通过。

**状态**：✅ 2026-11-17 完成。

### A3 · 租户上下文与权限判定（M）

- [x] `AuthService` 增加 `currentOrg` / `currentOrgId` / `orgForUser`（阶段 A 为默认 org，filter 命中用户时预填）。
- [x] `effectiveRole` 纳入 `org_admin`、项目 `membership`：平台 admin / org_admin 恒 owner，普通用户按项目角色。
- [x] 新增 `orgRole/isOrgAdmin/canManageProject`。
- [x] 新增 `canReadProject(user, project, stage)`：
  - 项目成员：draft + release；
  - 同组织非成员：仅 `internal` / `public` 的 release；
  - 匿名：仅 `public` 的 release（平台 API 仍按鉴权策略）。
- [x] 新增 `requireProjectVisible` / `requireRepoVisible`（不可见统一 404）与 `requireProjectRole`（可见但不足 403）。
- [x] `TokenAuthFilter`：鉴权成功挂 user + org context；dev 免登录行为不变。
- [x] `GET /api/auth/me`：返回 `orgs`、`currentOrgId/currentOrgSlug`、`orgAdmin`、当前项目角色；`canManage` 纳入 org_admin。
- [x] 现有 `platform_admin` 映射为 `org_admin`（平台 admin 对组织视为 org_admin），旧 admin-token 登录保持兼容。

**测试**

- [x] `AuthVisibilityTest`：org admin / 项目成员 / 组织非成员 / 匿名 × private/internal/public × draft/release。
- [x] `AuthApiTest` 回归；PostgreSQL 与 MySQL 双库通过；`:server:ktlintCheck` 通过。

**状态**：✅ 2026-11-17 完成。

### A4 · 读端点隔离（L，安全核心）

**投影层**

- [x] `ProjectionStore.listAllApis` 增加 `repoIds: Collection<Long>` 参数。
- [x] `ProjectionStore.listDocs` / `searchDocs` 支持 `repoIds` 集合。
- [x] **空集合返回空结果，绝不退化为全量**；所有调用方必须显式传可见 repo 集合。

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

- [x] 逐端点补 404/403 语义：不可见 404，可见但角色不足 403。
- [x] 写路径的 `requireRepoRole` 改为能解析 repo→project→org（`effectiveRole` 纳入 org_admin）。
- [x] 新增 `ReadIsolationTest`：用户只在 A，覆盖列表、详情、搜索、导出、评审、订阅、mock。

**测试**

- [x] `ReadIsolationTest`：两私有项目 + 只属于 A 的 viewer；projects/repos/apis/docs 列表、详情、搜索、导出、changelog、reviews、subscriptions、mock 全部看不到 B。
- [x] 全量 `:server:test` PostgreSQL / MySQL 双库通过；`:server:ktlintCheck` 通过。

**状态**：✅ 2026-11-17 完成（A‑1 安全底座门禁通过）。

> **A‑1 门禁**：A4 全绿之前，不开放项目切换 UI；下一步 A5–A8 才接入多项目 UI。

### A5 · 项目 CRUD API（M）

- [x] `GET/POST /api/projects`；`GET/PATCH/DELETE /api/projects/{id}`（独立 `ProjectController`，替换原只读列表端点）。
- [x] `POST /api/projects/{id}/transfer`（转移 owner；新 owner 必须是项目成员，旧 owner 降为 admin）。
- [x] `POST /api/projects/{id}/archive`（归档）。
- [x] 可见性变更：`PATCH` body 携带 `visibility`（仅可见性时 admin+ 可改，其余 owner）。
- [x] 权限：`org_admin` 创建；`project owner` 改设置/转移/删除；`project admin` 可改可见性。
- [x] 校验：slug 唯一（冲突 409）、owner 必须是组织成员/项目成员、删除前必须归档且无仓库（冲突 409）。
- [x] 删除时先清项目成员关系（`membership` FK RESTRICT）。
- [x] 项目级 changelog（`repo_id=null`，带 `org_id`）：create/update/archive/transfer/delete。
- [x] `ProjectApiTest`：CRUD、列表可见、slug 冲突、权限分层、归档/删除、owner 转移。

**状态**：✅ 2026-11-17 完成；PostgreSQL / MySQL 全量测试与 `:server:ktlintCheck` 通过。

### A6 · 多项目成员 API + UI（M）

**后端**

- [x] `GET/POST/PATCH/DELETE /api/projects/{id}/members`（`ProjectMemberController`）。
- [x] 成员角色仅 viewer/editor/admin；owner 只能走项目转移接口；不能移除 owner。
- [x] 添加成员支持 `userId` 或 `username`，且必须是组织成员；项目 admin+ 可管理。
- [x] 替换 `AuthController` 中全部 `DEFAULT_PROJECT` 硬编码：组织用户目录 / 创建组织用户 / 组织角色 / token 重置 / 删除。
- [x] `GET /api/auth/users` 改为组织维度（org_admin 可见）。
- [x] `GET /api/auth/me` 返回 `projectRoles`（projectId → 角色），不再绑定 default 项目。
- [x] 成员变更写 changelog（project.member.add / role / delete）。

**前端**

- [x] `MembersDialog` 接收 `projectId`，不再默认项目；按项目列成员/加成员/改角色/移除。
- [x] 项目切换器接真实 `onSelect`，顶栏显示当前项目。
- [x] 账号菜单的「成员与角色」按当前项目角色显示。
- [x] `api.ts` 增加项目成员 API、`projectRoles`、组织用户字段。
- [x] org_admin 可在同一弹窗创建/重置/删除组织用户；项目 admin 可按用户名加入已有用户。

**测试**

- [x] `ProjectMembershipApiTest`：owner/成员增删改、项目 admin 权限、owner 锁定、非成员 404。
- [x] `AuthApiTest` 适配组织用户语义；`ValidationContractTest` 改为校验 `orgRole`。
- [x] PostgreSQL / MySQL 全量测试、`:server:ktlintCheck`、`apps/web` 构建通过。

**状态**：✅ 2026-11-17 完成。

### A7 · 仓库注册 + binding（L）

- [x] `POST /api/repos` 必须携带 `projectId`；创建逻辑 repo + 首个 `active` binding（`managed` 或 `external`）。
- [x] `GET/POST /api/repos/{id}/bindings`；`POST .../verify`；`POST .../activate`；`POST .../retire`；`DELETE`（仅 retired）。
- [x] `verify`：空 → 允许推送当前内容；一致 → 可 flip；不一致 → 返回 diff，要求显式确认（`activate` 未带 `confirm=true` 时 409）。
- [x] `activate`：事务内 flip；并发 409；旧 binding 进入回滚窗口（默认 30 天，`spechub.repo-binding-rollback-days` 可配），窗口内可重新激活（回滚），窗口结束前禁止删除。
- [x] 摄取 / Webhook / 写回只认 `active` binding；`retired` 不可读写（无 active 时摄取 409/跳过，写回 409）。
- [x] 前端仓库注册表单增加项目选择（A8 落地：`RepoRegisterDialog` 归属项目选择）。
- [x] `RepoBindingApiTest`：单主约束、并发 flip、verify 三分支、flip 后评论/订阅/评审不变。

**状态**：✅ 2026-11-18 完成；PostgreSQL / MySQL 全量 `:server:test`（88 项）与 `:server:ktlintCheck` 通过。

### A8 · 前端多项目骨架（M）

- [x] `App.tsx` 增加 `selectedProjectId`，`load()` 按项目拉取 repos/apis/docs（后端新增 `?projectId=` 过滤）。
- [x] 项目下拉可用；项目创建/设置/归档入口；空状态引导（无项目 / 无仓库均有引导与入口）。
- [x] `api.ts` 增加项目 CRUD、成员、binding 的类型与调用。
- [x] 角色可见性按当前项目重新计算，不再依赖 `me.role`（`me.projectRoles[selectedProjectId]`）。
- [x] 侧栏「分组」保持 API tag 语义，不改成组织分组。
- [x] 前端 build + 手动 e2e（dev 模式：创建项目 A/B → 注册仓库 → 按项目拉取 repos/apis/docs 互不可见；项目/仓库对话框入口）。

**状态**：✅ 2026-11-18 完成；`apps/web` 构建通过，后端 PostgreSQL / MySQL 测试通过。

### A9 · 测试矩阵与双库验证（L）

- [x] 迁移测试：`ManagementDbMigrationTest`（PostgreSQL + MySQL）：V1/V2 造数据 → V3 回填断言（org/project/repo/binding/租户列/单主约束）；空库 V1→V3。
- [x] 新增：`ProjectApiTest`、`ProjectMembershipApiTest`、`RepoBindingApiTest`、`ReadIsolationTest`。
- [x] 更新：`AuthApiTest`、`WebApiTest`、`CommentApiTest`、`ReviewApiTest`、`SubscriptionApiTest`、`ImportExportApiTest` 中的默认项目假设（A7 随 `projectId` 必填一并调整）。
- [x] e2e：两个项目、用户只在 A；列表/详情/搜索/导出/评论/评审/订阅/通知/变更记录全隔离（`ReadIsolationTest` 扩展）。
- [x] 门禁：`./gradlew check --rerun-tasks` 通过；`apps/web` 构建通过；dev 免登录回归（Doc/Comment/Web/Subscription/ImportExport 集成测试）通过。
- [x] 双库命令：`./gradlew :server:test -Dspechub.test.db=mysql`（见 [TestManagementDb.kt](modules/server/src/test/kotlin/io/spechub/server/web/TestManagementDb.kt)），PostgreSQL / MySQL 各 91 项全绿。

**状态**：✅ 2026-11-18 完成；顺带修复 A4 投影 API 变更后遗留的 `ProjectionStoreTest` 编译失败（`repoIds` 必填）。

### A10 · 文档、开关与收尾（S）

- [x] 开发计划 P2.5 勾选与链接更新（P0–P2.5 全部完成）。
- [x] README 补多项目启动、bootstrap 与种子数据说明（创建项目 / 注册仓库 / 按项目读取）。
- [x] ADR §10 修正 `app_user` 措辞（A0 已改「就地升级为全局身份」；本轮明确「不新增 `org_id`、保持全局身份」、`release_marker` 命名、阶段 B 项标注与迁移验证说明）。
- [x] 开关：A‑1 安全底座在开放多项目 UI 前已通过（`ReadIsolationTest`），**无需灰度**，故不引入 `spechub.multi-project.enabled`；已在 README 记录该决策与后续灰度落点。

**状态**：✅ 2026-11-19 完成（阶段 A 收尾）。

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

- **门禁 1（A‑1）**：`ReadIsolationTest` 全绿之前，不开放多项目 UI（实际按任务顺序 A4 → A5–A8 满足，未引入灰度开关）。
- **门禁 2（A‑2）**：项目 CRUD 上线前，确认所有读端点已改为「按可见 repo 集合」查询；禁止任何 `repoIds=null` 退化为全量。
- **门禁 3**：`internal` / `public` 视图不返回 draft、评论、评审、mock 规则、通知。
- **门禁 4**：dev 免登录模式回归通过；生产鉴权模式默认拒绝。

---

## 6. 前置假设（A0 已确认）

| 项 | 结论 | 影响 |
|---|---|---|
| `org_membership` 是否在 A 落 | **是**（只加表，不加密码/邮箱） | 影响 A1/A3 |
| `repo_binding.connection_id` 是否允许空 | **允许**，D1 再补外部 connection | 影响 A1/A7 |
| 项目删除策略 | **先归档、再删除** | 影响 A5 |
| `app_user` 是否已全局 | **是**（当前无 `org_id`） | 影响 A0/A1 |
| `connection_id` 为空时摄取如何取 remote | 用 `repo.url` 兜底 | 影响 A2/A7 |

以上各项已于 2026-11-17 确认，对应 ADR §4.1 / §7.4 / §9 / §10 的修正。

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
