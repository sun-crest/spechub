# SpecHub · 阶段 B 任务拆解（团队与跨团队订阅）

> **日期**：2026-11-19　**状态**：✅ 完成（B0–B8，2026-11-19）
> **依据**：[SpecHub-ADR-001-管理模型与多租户](SpecHub-ADR-001-管理模型与多租户.md) §3.3 / §4.1 / §4.2 / §8 / §9 / §10 / §15
> **范围**：单组织内的团队实体、团队成员、订阅从「自由文本 team」切换到 `team_id`、通知按团队投递与可见性收敛、团队管理 UI。
> **不覆盖**：密码 / SSO / 服务账号（阶段 C）、多租户 SaaS 控制面与外部 Git 连接器（阶段 D1）、自助注册与域名认领（阶段 D2）、`project_group` / `api_group`（ADR 明确拒绝）。

---

## 1. 目标与完成定义

**目标**：把 I7 的「自由文本消费团队」升级为组织级 `team` 实体，让「消费团队订阅 → 结构变更通知 → 团队成员可见」这条跨团队联调链路真正成立。

**阶段 B 完成定义（DoD）**：

- [x] `team` / `team_member` 表落地，团队可创建、改名、删除、管理成员；
- [x] 团队挂在组织下（`org_id`），跨组织访问一律 404；
- [x] `subscription.team_id` 成为唯一订阅归属，旧 `team` 文本完成回填与同名合并；
- [x] 订阅必须指定 `teamId`，只有 team lead / org_admin 可代表团队订阅或取消；
- [x] 团队改名后订阅仍有效，订阅列表显示改名后的团队名；
- [x] 通知按团队投递，**只有团队成员 / org_admin 能查到**该团队通知；
- [x] 团队管理页与接口详情页订阅 Tab 代码与构建完成（浏览器手测清单见 B7.1）；
- [x] 迁移在 PostgreSQL 18.6 与 MySQL 8.4 上通过（V3→V4 过渡、V4→V5 收紧、空库 V1→V5）；
- [x] dev 免登录模式行为不回归。

**明确不做**：

- 团队加入申请 / 审批流、邀请邮件（邀请体系在阶段 C / D2）；
- 团队级 webhook 实际投递（`notify_url` 继续预留，B 阶段只落平台通知记录）；
- 团队与项目绑定（team 挂组织，`project.membership` 独立于 team）；
- 组织切换 UI、多组织团队目录（阶段 D1）；
- 团队层级（子团队）、团队 slug（ADR 只定义 `name`）。

---

## 2. 子阶段与任务总览

阶段 B 拆成三个可独立验收的子阶段：**B‑1 数据与领域** → **B‑2 团队能力与订阅语义** → **B‑3 前端与门禁**。

### 2.1 B‑1 数据与领域

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| B0 | 前置对齐与决策 | — | S | 决策记录、ADR 开放问题回填 |
| B1 | 管理库 V4 迁移 | B0 | L | `V4__teams.sql`（PG/MySQL） |
| B2 | 团队领域层与服务 | B1 | L | `TeamEntity` / `TeamService`、订阅/通知实体改造 |

### 2.2 B‑2 团队能力与订阅语义

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| B3 | 团队 API | B2 | M | `TeamController`、`/api/auth/me` 增 `teams` |
| B4 | 订阅 API 与数据模型切换 | B2 | M | `SubscriptionController` 改 `teamId` |
| B5 | 通知投递与可见性 | B2/B4 | M | Ingestor 投递链路、通知查询收敛 |

### 2.3 B‑3 前端与门禁

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| B6 | 前端团队管理与订阅改造 | B3/B4 | M | `TeamDialog`、`ApiDetail` 订阅 Tab、`api.ts` |
| B7 | 测试矩阵与双库验证 | B1–B6 | L | `TeamApiTest`、迁移/订阅/隔离测试 |
| B8 | 文档与收尾 | B7 | S | 开发计划、README、ADR 回填 |

> **B‑1 门禁**：B1 回填完成前，不改任何写路径（订阅仍写旧列），避免新旧数据交叉。
> **B‑2 门禁**：B5 的通知可见性测试全绿之前，不开放团队管理 UI。

---

## 3. 任务详情

### B0 · 前置对齐与决策（S）

ADR 未写死、但会影响实现细节的项，在 B0 一次拍板并记录。下表为**建议默认值**，未拍板前按默认执行：

| 决策项 | 建议默认 | 影响 |
|---|---|---|
| UI 文案 | 对外叫「团队」（`team` 实体与产品叙事一致；ADR §13.11 关闭） | B6 |
| 创建 / 删除团队 | `org_admin`（与创建项目一致；普通成员申请流留后续） | B3 |
| 团队改名 / 改描述 | `org_admin` 或 `team lead` | B3 |
| 成员管理 | `team lead` / `org_admin`；成员必须是 `org_membership` active 用户 | B3 |
| 订阅写权限 | `team lead` / `org_admin` 才能代表团队订阅、取消 | B4 |
| 删除团队 | 有订阅时 **409**，须先取消订阅；通知历史保留 | B3 |
| 团队成员列表可见性 | 团队成员 / `org_admin`；其他 404 | B3 |
| 通知可见性 | 本人所属团队；`org_admin` 可审计全组织；非成员指定 `teamId` 返回 404 | B5 |
| 旧 `team` 列处置 | 迁移后保留为**只读兼容快照**，写路径以 `team_id` 为准；D1 清理 | B1/B4 |
| 历史通知归属 | 能按「org + 团队名」回填的写 `team_id`；无法回填的仅 `org_admin` 可见 | B1/B5 |
| 旧数据成员回填 | 团队名与用户名相同且该用户是组织成员 → 自动加为 `lead`（旧前端默认以用户名当团队名） | B1 |
| 最后一个 lead | 不能移除 / 降级为 member（团队必须始终有 lead） | B3 |

- [x] 输出决策记录（写入本文档 §6 与 ADR 开放问题）。
- [x] 确认阶段 B 不做 `notify_url` 实际投递，只保留字段。

**状态**：✅ 2026-11-19（按建议默认值执行；ADR §13.11 已回填）。

---

### B1 · 管理库 V4 迁移（L）

迁移文件：`modules/server/src/main/resources/db/migration/postgresql/V4__teams.sql`、`modules/server/src/main/resources/db/migration/mysql/V4__teams.sql`。

**新增表**

- [x] `team`：
  ```sql
  team(id, org_id, name, description, created_at)
  -- UNIQUE (org_id, name)
  -- FK org_id → org(id) ON DELETE RESTRICT
  -- INDEX (org_id)
  ```
  `name` 非空、trim 后非空、长度 ≤ 128（与旧 `subscription.team` 列一致）。
- [x] `team_member`：
  ```sql
  team_member(team_id, user_id, role, created_at)
  -- PK (team_id, user_id)
  -- role ∈ lead | member
  -- FK team_id → team(id) ON DELETE CASCADE
  -- FK user_id → app_user(id) ON DELETE RESTRICT
  -- INDEX (user_id)
  ```

**改表（V4 过渡版）**

> 与阶段 A 的迁移纪律一致：新列先允许 NULL、旧约束保留，保证 B1 独立发布时旧代码仍可写；写入路径在 B2 补齐，再由 V5 收紧。

- [x] `subscription` 增加 `team_id BIGINT NULL`：
  - 按 `(org_id, team)` 回填：先建 `team` 行（同一 org 内**同名合并为一行**，忽略大小写与首尾空白；不同 org 同名各自建行），再回填 `team_id`；
  - `org_id` 为空的历史行用 `repo.org_id` 兜底；
  - **V4 保留** `team_id` 可空、旧唯一键 `uk_subscription_endpoint (repo_id, method, path, team)` 与旧 `team NOT NULL`；
  - 新增 FK `team_id → team(id) ON DELETE RESTRICT` 与索引 `(org_id, team_id)`；
  - 收紧（回填过渡期 NULL、去重、唯一键切 `(repo_id, method, path, team_id)`、`NOT NULL`、旧列可空）由 B2 的 `V5__team_binding_tighten.sql` 完成。
- [x] `notification` 增加 `team_id BIGINT NULL`：
  - 按 `(org_id, team)` 回填；匹配不到（团队已改名/删除）保留 NULL；
  - 新增 FK `team_id → team(id) ON DELETE SET NULL`；
  - 新增索引 `(org_id, team_id, created_at)`；
  - 旧 `team` 列保持 `NOT NULL`，作为**投递时团队名快照**。
- [x] `team_member` 回填：`team.name` 与 `app_user.username` 相同（忽略大小写）且该用户是同一组织 active 成员 → 插入 `lead`；其余用户不猜测。

**兼容与纪律**

- [x] 迁移不删除任何现有数据；旧 `team` 文本在 B 阶段保持可读。
- [x] 回填 SQL 分 vendor 维护；不写跨方言 SQL。
- [x] 迁移后校验：历史 `subscription` 全部有 `team_id`；`team` 行数与 distinct `(org_id, lower(trim(team)))` 一致；过渡期允许同名合并产生的重复订阅，由 V5 去重。

**验收**

- [x] PostgreSQL 18.6：V3 有数据 → V4，同名合并、`team_id` 回填、旧唯一键保留。
- [x] MySQL 8.4：同上。
- [x] 空库 V1 → V4 一次跑通。
- [x] 删除仍有订阅的 team 失败（FK RESTRICT）；新唯一键的拒绝行为在 V5 验证。
- [x] 双库全量 `:server:test` 与 `:server:ktlintCheck` 通过（V4 不破坏旧写入路径）。

**状态**：✅ 2026-11-19 完成（V4 过渡版；V5 收紧随 B2 交付）。

---

### B2 · 团队领域层与服务（L）

**Repository / Entity**

- [x] `TeamEntity` / `TeamRepository`：`findAllByOrgIdOrderByNameAsc`、`findByOrgIdAndNameIgnoreCase`、`findByIdAndOrgId`。
- [x] `TeamMemberEntity` / `TeamMemberRepository`：`findAllByTeamIdOrderByRoleAscUserIdAsc`、`findByTeamIdAndUserId`、`findAllByUserId`、`countByTeamId`、`countByTeamIdAndRole`、`deleteAllByTeamId`、`deleteAllByUserId`。
- [x] `SubscriptionEntity` 增加 `teamId`（`team` 列保留兼容快照）。
- [x] `NotificationEntity` 增加 `teamId`（`team` 列为名称快照）。

**Service**

- [x] `TeamService`：
  - `create(orgId, name, description, actor)`：trim、长度、组织内重名校验（忽略大小写）；
  - `update(id, orgId, name?, description?, actor)`：改名重名校验；
  - `delete(id, orgId, actor)`：有订阅 → 拒绝；事务内清 `team_member` 再删团队；
  - `roleInTeam(userId, teamId): String?`、`isLead`、`teamsOf(userId)`、`membersOf(teamId)`、`memberCount`、`subscriptionCount`；
  - `addMember` / `removeMember`：成员必须是组织 active 成员；最后一个 lead 保护；
  - `resolveOrCreate(orgId, name)`：B2/B4 过渡期按旧 `team` 文本解析团队（不存在则创建），保证新写入都有 `team_id`；并发由唯一键兜底（后到请求失败，不做事务内重查）；
  - 所有查询带 `orgId`，跨组织视为不存在。
- [x] `AuthService` 增加团队判定薄封装：`requireTeam`、`requireTeamMember`、`requireTeamLead`；`org_admin` 视同 lead；dev 免登录放行；非成员 404、非 lead 403。
- [x] `Records.kt`：
  - `SubscriptionRecord` 增加 `teamId: Long?`、`teamName: String`（`team` 字段保留兼容，值同 `teamName`）；
  - `NotificationRecord` 增加 `teamId: Long?`、`teamName: String`（`team` 保留兼容）。
- [x] `SubscriptionService`：
  - 写入路径从 B2 起填 `teamId`：保留旧 `team: String` 入参做兼容，`teamId == null` 时由 `TeamService.resolveOrCreate(orgId, name)` 解析/创建；
  - 幂等键改为 `(repoId, method, path, teamId)`（V5 后由数据库唯一键兜底）；
  - `list*` 返回 `teamName` 时批量取团队当前名（避免 N+1），历史行退回名称快照；
  - `addNotification(teamId, teamName, repoId, method, path, summary)`。
- [x] `IngestHooks` / `KernelConfig`：`addNotification` 签名加 `teamId` / `teamName`；`Ingestor` 跳过无 `teamId` 的历史订阅。
- [x] `UserService.deleteUser`：先 `teamMemberRepository.deleteAllByUserId(id)`，再删组织/项目关系。
- [x] changelog：`team.create` / `team.update` / `team.delete` / `team.member.add` / `team.member.role` / `team.member.delete`（`repo_id=null`，带 `org_id`）。

**V5 收紧迁移（B2 末，写入路径生效后）**

迁移文件：`modules/server/src/main/resources/db/migration/{postgresql,mysql}/V5__team_binding_tighten.sql`。

- [x] `team_member` 增加代理主键 `id`（Spring Data JDBC 单列 `@Id`），原 `(team_id, user_id)` 改为唯一键；MySQL 先补 FK 索引再删复合主键。
- [x] 回填 B1→B2 窗口内新增的 `subscription.team_id IS NULL` 行：按 `subscription.team` 在当前 org 查找/创建 team 并回填；
- [x] 同窗口内 `notification.team_id IS NULL` 的通知也按团队名回填（匹配不到保留 NULL）；
- [x] 按 `(repo_id, method, path, team_id)` 去重，保留 `id` 最大的一条（通知历史不动）；
- [x] 删除旧唯一键 `uk_subscription_endpoint`，新增 `UNIQUE (repo_id, method, path, team_id)`；
- [x] `subscription.team_id SET NOT NULL`；旧 `team` 列改可空（保留名称快照）；
- [x] 验收：V4 有重复/NULL 数据 → V5 后无 NULL、无重复；插入同 `(repo_id, method, path, team_id)` 失败；
- [x] 迁移测试：V3 → V4 → V5 分段验证（过渡期保留 vs 收紧后去重）。

**纪律**

- [x] 团队 + 成员多表写用 `@Transactional`；通知逐条独立写（沿用 I7 的既有例外）。
- [x] 团队名读取走批量查询，禁止在列表循环里逐条查团队。

**测试**

- [x] `TeamServiceTest`：创建 / 重名 / 改名 / 删除有订阅拒绝 / 成员增删改 / 最后一个 lead 保护 / 跨 org 不可见 / `resolveOrCreate` 幂等。
- [x] `UserService` 删除用户清理 `team_member`（`TeamServiceTest` 覆盖）。
- [x] `ManagementDbMigrationTest` 增加 V4 → V5 收紧用例；V3 → V4 保留过渡期断言。

**状态**：✅ 2026-11-19 完成；PostgreSQL / MySQL 全量 `:server:test` 与 `:server:ktlintCheck` 通过。

---

### B3 · 团队 API（M）

**端点**

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/api/teams` | 组织成员 | 当前组织团队列表，附 `myRole` / 成员数 / 订阅数 |
| `POST` | `/api/teams` | `org_admin` | 创建团队 |
| `GET` | `/api/teams/{id}` | 组织成员 | 团队详情 |
| `PATCH` | `/api/teams/{id}` | `org_admin` / `team lead` | 改名、改描述 |
| `DELETE` | `/api/teams/{id}` | `org_admin` | 删除；有订阅 409 |
| `GET` | `/api/teams/{id}/members` | 团队成员 / `org_admin` | 成员列表（其他 404） |
| `POST` | `/api/teams/{id}/members` | `team lead` / `org_admin` | 添加组织用户（`userId` 或 `username`） |
| `PATCH` | `/api/teams/{id}/members/{userId}` | `team lead` / `org_admin` | 改 `lead` / `member` |
| `DELETE` | `/api/teams/{id}/members/{userId}` | `team lead` / `org_admin` | 移除；不能移除最后一个 lead |

- [x] 新建 `TeamController`（参考 `ProjectController` / `ProjectMemberController` 的权限与错误码风格）。
- [x] DTO：`TeamView(id, name, description, memberCount, subscriptionCount, myRole, canManage, createdAt)`、`TeamMemberView(userId, username, displayName, role, orgRole)`。
- [x] 校验：团队不存在 / 跨 org → 404；可见但角色不足 → 403；重名 → 409；最后一个 lead → 409；有订阅删除 → 409。
- [x] `GET /api/auth/me` 增加 `teams: [{ id, name, role }]`（当前用户所在团队，`role ∈ lead|member`；org_admin 不自动列出全部团队，可另行通过 `/api/teams` 查看）。
- [x] dev 免登录模式：团队管理端点放行；列表 `canManage=true`、`myRole` 为 null（无成员身份）。
- [x] `POST /api/teams` 创建成功后将创建者（非 dev anonymous）加入为 `lead`，保证团队始终有 lead。

**测试**

- [x] `TeamApiTest`：org_admin 创建且创建者为 lead、普通成员创建 403、团队列表/myRole、`me.teams`、改名与重名 409、成员改角色、非成员看成员列表 404、最后一个 lead 409、删除有订阅 409、跨 org 404。
- [x] `me.teams` 由 `TeamApiTest` 覆盖（鉴权模式）；dev 未登录时字段为空列表默认值。

**状态**：✅ 2026-11-19 完成；PostgreSQL / MySQL 全量 `:server:test` 与 `:server:ktlintCheck` 通过。

---

### B4 · 订阅 API 与数据模型切换（M）

**不兼容变更**（ADR §8.2）

- [x] `POST /api/subscriptions`：`team: string` → `teamId: number`（必填）。
  - 权限：`team lead` / `org_admin`；且仍需 `requireRepoVisible(repoId, RELEASE)`（可见性矩阵不变）。
  - 团队必须属于当前组织；不存在 / 跨组织 → 404。
  - 幂等 upsert 键改为 `(repoId, method, path, teamId)`。
- [x] `DELETE /api/subscriptions/{id}`：仅该团队 lead / org_admin；否则 403；订阅不可见 404。
- [x] `GET /api/subscriptions?repoId=&teamId=`：返回 `teamId` + `teamName`（当前团队名）；支持可选 `teamId` 过滤。
- [x] `GET /api/subscriptions/notifications`：参数 `team: string` → `teamId: number`；旧 `team` 查询参数返回 400（可见性收紧留 B5）。
- [x] changelog 详情使用团队名（改名后写新名）。
- [x] 旧 `team` 字段不再接受；请求未带 `teamId` 返回 400 并提示改用 `teamId`。
- [x] MCP、CLI 当前未实现订阅，无额外兼容面；前端同步留 B6。

**测试**

- [x] `SubscriptionApiTest` 改造为 `teamId` 订阅（无鉴权 e2e：订阅 / 幂等 / 通知 / 取消）。
- [x] 新增 `SubscriptionTeamApiTest`（鉴权模式）：非 lead 403、lead 订阅与取消、团队改名后订阅仍有效且返回新名、repo 不可见 404、跨 org 404、缺 `teamId` / 旧 `team` 字段 400。
- [x] `ValidationContractTest` 订阅校验请求体改为 `teamId`。

**状态**：✅ 2026-11-19 完成；PostgreSQL / MySQL 全量 `:server:test` 与 `:server:ktlintCheck` 通过。

---

### B5 · 通知投递与可见性（M）

**投递链路**

- [x] `Ingestor.notifySubscribers`：从订阅记录取 `teamId` / `teamName`，调用 `addNotification(teamId, teamName, ...)`；逐团队独立写、失败不阻塞其它团队（保持现状）。
- [x] 通知行写 `org_id`、`team_id`、`team`（名称快照）。
- [x] 团队改名后，新通知用新名快照；历史通知保留旧快照。

**查询语义（安全核心）**

- [x] 鉴权启用时：
  - 不传 `teamId`：当前用户所属团队（按当前 org 过滤）的通知；`org_admin` 返回全组织可见 repo 的通知；
  - 传 `teamId`：必须是该团队成员或 `org_admin`，否则 **404**（`AuthService.requireTeamMember`）；
  - 始终与 `visibleRepoIds(request, RELEASE)` 求交，空集合返回空，禁止退化全量（延续 A4 纪律）。
- [x] 历史通知 `team_id` 为 NULL：普通用户不返回（`team_id IN (...)` 天然排除）；仅 `org_admin` 可见（无法安全归属到成员）。
- [x] dev 免登录：返回当前组织可见 repo 的全部通知（保持开发可用）。
- [x] `limit` 默认 50，`coerceIn(1, 200)` 防滥用。

**测试**

- [x] `SubscriptionTeamApiTest` 新增：团队成员可见、非成员不传 teamId 为空/指定 teamId 404、org_admin 可审计；历史 `team_id` 为空仅 org_admin 可见；跨团队隔离；团队删除后历史通知保留（`team_id` 置 NULL，快照名保留）。
- [x] `SubscriptionApiTest`（dev e2e）：改 spec push → 通知按 teamId 可查，幂等不重复。
- [x] `ReadIsolationTest`：非团队成员指定 B 团队 `teamId` 返回 404；可见 repo 过滤保持。

**状态**：✅ 2026-11-19 完成；PostgreSQL / MySQL 全量 `:server:test` 与 `:server:ktlintCheck` 通过。

---

### B6 · 前端团队管理与订阅改造（M）

- [x] 新建 `apps/web/src/demo/TeamDialog.tsx`：
  - 团队列表（名称、成员数、我的角色、订阅数）；
  - 创建 / 改名 / 删除（按角色显示；dev 免登录全放行）；空状态引导；
  - 成员管理（添加组织用户、改 lead/member、移除；最后一个 lead 错误由后端 409 提示）。
- [x] `App.tsx`：账号菜单增加「团队管理」入口；普通用户只看自己团队，org_admin / dev 可见全部。
- [x] `ApiDetail.tsx` 订阅 Tab：
  - 订阅表单改为团队下拉：只列 `canManage` 的团队（team lead / org_admin；dev 全放行）；无可用团队时引导去团队管理创建；
  - 订阅方列表显示 `teamName`，取消按钮按 lead / org_admin 显示；
  - 通知面板默认拉「我的团队」，可按团队筛选；显示团队名 + 变更摘要。
- [x] `api.ts`：
  - 新增 `Team` / `TeamMember` 类型与 CRUD / 成员 API；
  - `Subscription` 增加 `teamId` / `teamName`，`subscribe` 改 `teamId`；
  - `Notification` 增加 `teamId` / `teamName`，`listNotifications` 改 `teamId`；
  - `Me` 增加 `teams`。
- [x] 文案统一「团队」（B0 结论）。
- [x] `apps/web` 构建通过（`tsc -b && vite build`，含 dev 免登录团队管理分支）。
- [ ] 浏览器手动 e2e（留 B7 联调）：创建团队 → 加成员 → 订阅 → 改 spec → 成员看到通知 / 非成员看不到 → 团队改名 → 订阅仍有效。

**状态**：✅ 2026-11-19 代码与构建完成；浏览器手测随 B7 联调执行。

---

### B7 · 测试矩阵与双库验证（L）

- [x] 迁移测试（`ManagementDbMigrationTest`，4 项）：
  - V3 → V4：同名合并、`team_id` 回填、成员启发式回填、旧唯一键保留；
  - V4 → V5：过渡期 NULL 回填、重复订阅去重、唯一键切换、`NOT NULL`、`team_member` 代理主键；
  - 空库 V1 → V5。
- [x] 新增 `TeamApiTest`（6 项）：创建/权限、列表与 `me.teams`、改名/重名 409、最后 lead 409、删除有订阅 409、跨 org 404。
- [x] 改造 `SubscriptionApiTest`（2 项）：`teamId` 订阅、幂等、通知、取消。
- [x] 新增 `SubscriptionTeamApiTest`（8 项）：team lead 订阅/取消权限、团队改名后订阅仍有效、repo 不可见/跨 org 404、旧字段 400、通知可见性与跨团队隔离、团队删除后历史通知保留。
- [x] 扩展 `ReadIsolationTest`：非团队成员指定 B 团队 `teamId` 返回 404。
- [x] `ValidationContractTest` 订阅请求体改 `teamId`；`AuthApiTest` / `WebApiTest` 未涉及团队/订阅断言，无需改动。
- [x] 全量门禁：
  - `./gradlew check --rerun-tasks`（PostgreSQL）通过；
  - MySQL 全量 `./gradlew :server:test -Dspechub.test.db=mysql --rerun-tasks` 通过（113 项，0 失败）；
  - `:server:ktlintCheck` 通过；
  - `apps/web` `npm run build` 通过；
  - dev 免登录回归（Doc / Comment / Web / Subscription / ImportExport）随全量套件通过。
- [x] e2e 手动清单落文档（见下）。

### B7.1 手动 e2e 清单（浏览器）

> 自动化已覆盖 API 链路；以下清单用于 UI 联调/验收，待人工执行。

1. 以 `org_admin` 登录 Web。
2. 账号菜单 →「团队管理」创建团队 A；确认创建者出现在成员列表且角色为 `lead`。
3. 添加用户 B 为 `member`；B 登录后打开团队管理，只看到团队 A，且无创建/删除/成员管理入口。
4. 打开任一接口详情 →「订阅」Tab：团队下拉出现 A，订阅成功，订阅方显示 A。
5. B 打开同一接口：无订阅按钮（非 lead）；通知筛选可选团队 A。
6. 修改 spec 并 push（或 Web 编辑提交）触发摄取。
7. 团队 A 的成员看到结构变更通知；非成员 C（同组织、可见该接口）看不到 A 的通知，指定 `teamId=A` 返回 404。
8. 在团队管理把 A 改名为 A2；订阅方显示 A2，订阅仍有效。
9. 删除 A2 → 409（仍有订阅）；取消订阅后可删除；历史通知仍可由 `org_admin` 查到。
10. dev 免登录模式：团队管理入口可用、订阅下拉可用、通知为可见 repo 全量。

**状态**：✅ 2026-11-19 自动化门禁全部通过；浏览器手测清单已落文档，待人工执行。

---

### B8 · 文档与收尾（S）

- [x] 开发计划新增 P2.6（阶段 B）并勾选。
- [x] 本文档状态更新、任务勾选。
- [x] README 补团队与订阅说明（创建团队 → 加成员 → 订阅 → 通知可见性；API 示例与权限边界）。
- [x] ADR 开放问题 11（UI 文案）回填结论：对外统一叫「团队」。
- [x] `SpecHub-管理数据持久化选型.md` 补 `team` / `team_member` / `subscription.team_id` / `notification.team_id` 与 Phase 5。
- [x] `SpecHub-运维手册.md` 补团队删除保护、V4/V5 迁移与历史通知说明。

**状态**：✅ 2026-11-19 完成（阶段 B 收尾）。

---

## 4. 关键路径与并行

**关键路径**：

```text
B0 → B1 → B2 → B3/B4 → B5 → B6 → B7 → B8
```

**两人并行建议**：

- 一人：B1（迁移）→ B2（领域）→ B4（订阅切换）。
- 一人：B3（团队 API，B2 完成后）→ B5（通知可见性）→ B6（前端）。
- B6 在 B3 / B4 的 API 契约稳定后开始；B7 贯穿，迁移测试可在 B1 完成后先行。

**一人串行建议**：B0 → B1 → B2 → B3 → B4 → B5 → B6 → B7 → B8。

---

## 5. 安全门禁

- **门禁 1（迁移）**：B1 回填完成前，订阅写路径仍写旧列；回填后先跑双库迁移测试，再切 API。
- **门禁 2（订阅）**：订阅写操作必须校验 `teamId` 归属组织 + 用户是 team lead / org_admin；repo 可见性校验不得移除。
- **门禁 3（通知）**：通知查询必须按「团队成员身份 + 可见 repo」双过滤；指定 `teamId` 非成员一律 404；禁止空集合退化为全量。
- **门禁 4（跨 org）**：团队 / 成员 / 订阅 / 通知全部带 `org_id` 判定，跨组织 404。
- **门禁 5（dev）**：`authEnabled=false` 下团队与订阅行为不回归；启用鉴权后默认拒绝。

---

## 6. 前置假设（B0 已确认）

> 2026-11-19 按建议默认值确认并落地；ADR §13.11（UI 文案）已回填。

| 项 | 结论 | 影响 |
|---|---|---|
| UI 文案 | 团队 | B6 |
| 创建 / 删除团队 | `org_admin` | B3 |
| 团队改名 | `org_admin` / `team lead` | B3 |
| 订阅写权限 | `team lead` / `org_admin` | B4 |
| 删除团队 | 有订阅 409，通知历史保留 | B3 |
| 通知可见性 | 团队成员 / `org_admin`；非成员 404 | B5 |
| 旧 `team` 列 | 只读兼容快照，D1 清理 | B1/B4 |
| 成员回填 | 团队名 = 用户名 → `lead` | B1 |
| 最后 lead 保护 | 启用 | B3 |

---

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 旧自由文本同名合并误合并不同团队 | 迁移按 org 内同名合并并在迁移测试中固定预期；B0 记录语义；迁移后提供团队清单供人工核对 |
| 旧数据 `team` 名与真实团队不一致 | 成员回填仅做「用户名 → lead」的保守启发式；其余团队由 org_admin 在 UI 补成员 |
| 通知查询越权（任意传 `teamId`） | `AuthService.requireTeamMember` 统一入口 + 非成员 404 + 集成测试 |
| 团队改名后订阅/通知失联 | 订阅、投递、查询全部以 `team_id` 为真源；名称仅展示 |
| 删除团队导致订阅静默丢失 | 有订阅 409；删除后通知历史保留（`team_id` SET NULL + 名称快照） |
| 最后一个 lead 被移除导致团队不可管理 | 服务层与 API 双重 409 保护 |
| MySQL 唯一约束 / 回填语法差异 | V4 分 vendor 维护；迁移矩阵双库跑 |
| 旧 API 客户端传 `team` 字符串 | B4 明确不兼容并返回 400 提示；前端同步；MCP/CLI 无订阅功能 |
| 团队订阅 private 项目接口 | 保持 A 阶段可见性矩阵：订阅仍需 `requireRepoVisible`；消费方可读 internal/public release |

---

## 8. 交付物清单

- `V4__teams.sql`（过渡）+ `V5__team_binding_tighten.sql`（收紧）与迁移矩阵测试。
- `team` / `team_member` 领域层、`TeamService`、`TeamController`。
- 订阅模型切换：`subscription.team_id`、`SubscriptionRecord` / `NotificationRecord` 扩展。
- 通知按团队投递与可见性收敛（Ingestor + 查询入口）。
- 前端：`TeamDialog`、订阅 Tab 团队化、`api.ts` 类型与调用。
- 测试：`TeamApiTest`、`SubscriptionApiTest` 改造、`ReadIsolationTest` 增补、迁移测试扩展。
- 文档：开发计划 P2.6、README、ADR 开放问题回填。
