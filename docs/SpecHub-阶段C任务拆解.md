# SpecHub · 阶段 C 任务拆解（账号与审计）

> **日期**：2026-11-19　**状态**：待启动（C0–C10）
> **依据**：[SpecHub-ADR-001-管理模型与多租户](SpecHub-ADR-001-管理模型与多租户.md) §4.1 / §4.2 / §7.1 / §8.1 / §9 / §10 / §15
> **范围**：密码 / OIDC 登录、HttpOnly 会话、账号禁用与邀请、可过期可吊销令牌、项目级服务账号、管理动作审计查询。
> **不覆盖**：多组织切换 / 组织生命周期 / 平台运营控制面 / break-glass（阶段 D1）、自助注册 / 邮箱验证 / 域名认领（阶段 D2）、SAML / SCIM、MFA / WebAuthn、组织级服务账号、`project_group` / `api_group`。

---

## 1. 目标与完成定义

**目标**：把当前「用户名 + 长期 token」的临时身份体系升级为可运维的账号体系：人用密码或 OIDC 登录并持有 HttpOnly 会话，机器用可过期、可吊销的项目级服务账号，所有管理动作可审计。

**阶段 C 完成定义（DoD）**：

- [ ] Web 登录默认走 **HttpOnly 会话**，浏览器不再把长期令牌放 localStorage；密码登录可用，配置 OIDC 后可用 IdP 登录；
- [ ] `app_user` 具备 `email` / `password_hash` / `status`，密码使用 **Argon2id** 存储；
- [ ] 账号可禁用 / 启用 / 邀请 / 重置密码；**禁用后该用户所有会话与令牌在下一次请求立即失效**；
- [ ] 用户令牌带前缀、可设置过期时间、可吊销；旧 `app_user.token_hash` 迁移为 `user_token` 后**旧令牌继续有效**（ADR §10）；
- [ ] 项目级服务账号 `viewer` / `editor` 可用，**不可登录 Web、不可跨项目访问**，吊销立即生效；
- [ ] 登录成功 / 失败、用户管理、令牌、服务账号、项目 / 团队 / binding 等管理动作写入 `audit_event`；`GET /api/audit` 仅 `org_admin` 可查；
- [ ] `app_user.is_platform_admin` 与 `app_user.token_hash` 在阶段 C 内移除（V7），代码中无引用；
- [ ] 管理库迁移在 PostgreSQL 18.6 与 MySQL 8.4 上通过（V5→V6→V7、空库 V1→V7）；
- [ ] dev 免登录模式行为不回归；
- [ ] `apps/web` 构建通过，浏览器手测清单落文档。

**明确不做**：

- 自助注册、邮箱验证、邀请邮件、域名认领（阶段 D2）；
- SAML / SCIM / 多 IdP / 组到角色映射（Business 起，D1 后再评估）；
- MFA / TOTP / WebAuthn（`mfa_enabled` 仅预留列，不实现校验）；
- 平台运营账号、break-glass、平台审计（阶段 D1）；
- 组织级服务账号、服务账号细粒度 scope（仅 `viewer` / `editor` 两档）；
- 细粒度资源级 ACL（沿用 ADR §14 非目标）；
- 计费、配额、组织暂停（D1 / N2）。

---

## 2. 子阶段与任务总览

阶段 C 拆成四个可独立验收的子阶段：**C‑1 身份底座** → **C‑2 登录与账号生命周期** → **C‑3 机器身份与 SSO** → **C‑4 审计、前端与门禁**。

### 2.1 C‑1 身份底座

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| C0 | 前置对齐与决策 | — | S | 决策记录、ADR §13 回填 |
| C1 | 管理库 V6 / V7 迁移 | C0 | L | `V6__identity_and_audit.sql`、`V7__identity_tighten.sql`（PG/MySQL） |
| C2 | 身份领域层与统一认证入口 | C1 | L | `UserSession` / `UserToken` / `ServiceAccount` / `UserIdentity` 领域层、`AuthPrincipal`、`TokenAuthFilter` 双通道 |

### 2.2 C‑2 登录与账号生命周期

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| C3 | 密码登录与会话端点 | C2 | M | `login` / `logout` / `me` / `password` / `password/setup`、CSRF、登录限流 |
| C4 | 账号生命周期与令牌管理 | C2 / C3 | M | 禁用 / 启用 / 邀请 / 重置、用户令牌 CRUD、V7 收紧 |

### 2.3 C‑3 机器身份与 SSO

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| C5 | 项目级服务账号 | C2 | M | `ServiceAccountService` / `ServiceAccountController`、跨项目隔离、MCP/CLI 文档 |
| C6 | OIDC 登录 | C3 | L | Spring Security OIDC 链、`user_identity` 绑定、自动建号开关、配置与测试 |

### 2.4 C‑4 审计、前端与门禁

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| C7 | 管理动作审计 | C3 / C4 / C5 | M | `audit_event`、`AuditService`、`GET /api/audit`、关键写点接入、保留策略 |
| C8 | 前端账号与安全改造 | C3–C7 | M | 登录框、账号与安全、令牌、服务账号、审计页、去掉 localStorage 长期令牌 |
| C9 | 测试矩阵与双库验证 | C1–C8 | L | 迁移 / 会话 / 密码 / 禁用 / 服务账号 / OIDC / 审计测试、手动 e2e |
| C10 | 文档与收尾 | C9 | S | 开发计划、README、ADR、持久化选型、运维手册、UI 方案 |

> **C‑1 门禁**：C1 回填完成、C2 双通道认证测试全绿之前，不改 Web 登录形态（前端仍走旧 token 登录）。
> **C‑2 门禁**：禁用用户会话 / 令牌立即失效的集成测试全绿之前，不把「禁用账号」开放到 UI。
> **C‑3 门禁**：服务账号跨项目隔离测试全绿之前，不把 MCP / CLI 文档切到服务账号。
> **C‑4 门禁**：V7 删除旧列之前，代码中 `is_platform_admin` / `app_user.token_hash` 引用必须清零（grep 门禁）；dev 免登录回归必须全绿。

### 2.5 C 阶段 API 变更总表

| 方法 | 路径 | 说明 | 任务 |
|---|---|---|---|
| `POST` | `/api/auth/login` | 密码 / 旧 token 登录；成功创建 HttpOnly 会话并返回 CSRF token | C3 |
| `POST` | `/api/auth/logout` | 吊销当前会话、清 Cookie | C3 |
| `GET` | `/api/auth/me` | 增加 `authMethod` / `csrfToken` / `email` / `status` / `mustChangePassword` / `oidcEnabled` 等 | C3 |
| `POST` | `/api/auth/password` | 修改自己的密码；吊销其它会话 | C3 |
| `POST` | `/api/auth/password/setup` | 用一次性邀请 / 重置令牌设置密码 | C3 |
| `PATCH` | `/api/auth/users/{id}` | 改 `displayName` / `email`（org_admin） | C4 |
| `POST` | `/api/auth/users/{id}/disable` | 禁用账号；事务内吊销全部会话与令牌 | C4 |
| `POST` | `/api/auth/users/{id}/enable` | 启用账号；已吊销令牌不恢复 | C4 |
| `POST` | `/api/auth/users/{id}/password/reset` | 吊销会话 / 令牌并返回一次性重置令牌（只显示一次） | C4 |
| `GET` / `POST` | `/api/auth/tokens` | 列出 / 创建自己的 API 令牌 | C4 |
| `DELETE` | `/api/auth/tokens/{id}` | 吊销自己的令牌 | C4 |
| `GET` | `/api/auth/users/{id}/tokens` | org_admin 查看指定用户令牌（不含明文） | C4 |
| `DELETE` | `/api/auth/users/{id}/tokens/{tokenId}` | org_admin 吊销指定用户令牌 | C4 |
| `GET` / `POST` | `/api/service-accounts` | 项目级服务账号列表 / 创建（明文只返回一次） | C5 |
| `DELETE` | `/api/service-accounts/{id}` | 吊销服务账号 | C5 |
| `POST` | `/api/service-accounts/{id}/rotate` | 轮换密钥（吊销旧密钥、返回新密钥一次） | C5 |
| `GET` | `/api/audit` | 管理动作审计查询（org_admin） | C7 |
| `GET` | `/oauth2/authorization/spechub` | OIDC 授权入口（Spring Security 提供） | C6 |
| `GET` | `/login/oauth2/code/spechub` | OIDC 回调（Spring Security 提供） | C6 |

兼容保留：`POST /api/auth/users/{id}/token` 继续可用，语义改为「吊销旧令牌并签发一条新的用户 API 令牌」，响应仍返回 `token` 一次。

---

## 3. 任务详情

### C0 · 前置对齐与决策（S）

ADR 未写死、但会影响实现细节的项，在 C0 一次拍板并记录。下表为**建议默认值**，未拍板前按默认执行：

| 决策项 | 建议默认 | 理由 / 影响 |
|---|---|---|
| 认证架构 | **渐进式**：保留 `TokenAuthFilter` 作为唯一认证入口，新增会话 / 服务账号解析；OIDC 用独立的 Spring Security 链，仅匹配 OIDC 端点 | 避免一次性重写 113 项测试覆盖的鉴权路径；全量 Spring Security 迁移留 D1 评估 |
| 会话存储 | DB 表 `user_session` + 不透明 Cookie；只存 SHA-256，不存明文 | 禁用 / 登出立即失效、多实例可用；不用 JWT，避免无法撤销 |
| Cookie | `HttpOnly; SameSite=Lax; Path=/`；prod 加 `Secure`；dev 可配 `secure=false` | 防 XSS 窃取；Vite 代理同源下 Lax 可用 |
| 会话时效 | 绝对 7 天 + 空闲 24 小时；可配置；登录时轮换会话 ID | 兼顾安全与日常使用；轮换防会话固定 |
| CSRF | 会话内存同步令牌（synchronizer token）：会话行存 `csrf_token`，`/api/auth/me` 返回，非安全方法必须带 `X-CSRF-Token`；Bearer 认证豁免；新增 `AUTH_CSRF(403)` 便于机器分支 | 比双提交 Cookie 更强；CLI / MCP 不受影响 |
| 密码哈希 | **Argon2id**（`spring-security-crypto` 的 `Argon2PasswordEncoder` + `bcprov-jdk18on`），参数集中配置 | 满足 ADR §7.1；不引入完整 Spring Security 也需该加密库 |
| 密码策略 | 长度 10–128；不强制大小写 / 符号组合；禁止等于用户名 / 邮箱；管理员重置后强制改密 | NIST 风格；避免弱口令与可用性冲突 |
| 登录标识 | `username` 或 `email` 二选一（忽略大小写）；失败统一 401「用户名或密码错误」 | 不暴露账号是否存在 |
| 令牌格式 | 用户令牌 `shu_`、服务账号 `shs_`、会话 `shsess_`；只存 SHA-256；列表只显示前缀 | 便于 secret 扫描与运维识别 |
| 旧令牌迁移 | V6 把 `app_user.token_hash` 逐行复制为 `user_token(purpose=legacy)`；旧令牌继续有效；V7 删列 | 满足 ADR §10「旧 token 继续有效」；两阶段迁移可回滚 |
| `is_platform_admin` | C 内移除；平台 admin 由 `org_membership.org_role='org_admin'` 表达；`/api/auth/me` 删除 `platformAdmin` 字段 | 与 ADR §10 一致；前端改用 `orgAdmin` |
| 服务账号 | **仅项目级**（`project_id NOT NULL`）；角色仅 `viewer` / `editor`；默认过期 90 天、最长 365 天；支持轮换；不可登录 Web | ADR §4.2 / §7.1；组织级服务账号留 D1 |
| OIDC | 单 IdP；Authorization Code + PKCE；按 `(issuer, subject)` 优先、已验证邮箱兜底绑定；`auto-provision` 默认关闭；不做组映射 | 安全默认；多 IdP / SAML / SCIM 留后续 |
| 邮箱 | C 只存储与唯一校验，不发信；密码重置令牌由管理员一次性查看；邮件自助重置留 D2 | 避免在 D2 邮件基础设施前引入半成品 |
| 禁用语义 | `app_user.status=disabled` 为全局禁用；禁用事务内吊销全部会话与令牌；启用不恢复旧凭据；`org_membership.status` 留 D1 | 满足阶段 C 验收；组织级禁用 D1 再做 |
| 审计存储 | **新增 `audit_event` 表**；`changelog` 继续作为产品变更流；认证 / 用户 / 令牌 / 服务账号 / 组织级管理动作写审计，内容写操作仍只写 changelog | 不把安全审计混入产品变更流；与 ADR §7.1「后续可独立 audit_event 表」衔接 |
| 审计保留 | 默认 365 天，每日清理；可配置 | 控制表增长；付费档保留期 D1 再定 |
| 登录限流 | 内存固定窗口：同一 `identifier` 5 次失败 / 5 分钟，锁 15 分钟；同 IP 20 次 / 5 分钟；新增 `TOO_MANY_REQUESTS(429)` | C 单实例足够；多实例共享限流留 D1 |
| 认证开关 | 继续以 `spechub.admin-token` 是否配置作为 `authEnabled`；OIDC 是启用鉴权后的附加登录方式；prod 仍要求 admin-token | 与 ADR §7.1「自托管保留 admin-token bootstrap」一致；SaaS 强制鉴权由 D1 平台配置 |
| `admin-token` 登录 | 继续可用；成功登录同样创建会话，Web 不再保存 token | 兼容旧部署，同时满足「Web 不落 localStorage」 |
| 迁移节奏 | V6 加列 / 建表 / 回填 → 代码切换 → V7 删旧列 | 与 B 阶段 V4/V5 的过渡纪律一致 |

- [ ] 输出决策记录（写入本文档 §6 与 ADR §13 开放问题）。
- [ ] 确认 V6 / V7 版本号与两阶段迁移门禁。
- [ ] 确认 OIDC 依赖方案（Spring Security OAuth2 Client 仅用于 OIDC 链）与配置项。
- [ ] 确认「最后一名 `org_admin` 不可禁用 / 降级 / 删除」保护（与团队最后一个 lead 保护同风格）。

**状态**：待启动。

---

### C1 · 管理库 V6 / V7 迁移（L）

迁移文件：

- `modules/server/src/main/resources/db/migration/postgresql/V6__identity_and_audit.sql`
- `modules/server/src/main/resources/db/migration/mysql/V6__identity_and_audit.sql`
- `modules/server/src/main/resources/db/migration/postgresql/V7__identity_tighten.sql`
- `modules/server/src/main/resources/db/migration/mysql/V7__identity_tighten.sql`

> 与 A / B 阶段纪律一致：V6 只加列 / 建表 / 回填，保证旧代码仍可启动；代码切换完成后由 V7 删除旧列。迁移不删除任何业务数据。

**V6 · `app_user` 扩展**

- [ ] 新增列（示意）：
  ```sql
  ALTER TABLE app_user ADD COLUMN email VARCHAR(255);
  ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(255);
  ALTER TABLE app_user ADD COLUMN password_updated_at TIMESTAMPTZ;
  ALTER TABLE app_user ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'active';
  ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
  ALTER TABLE app_user ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
  ALTER TABLE app_user ADD COLUMN last_login_at TIMESTAMPTZ;
  ```
- [ ] `status ∈ active | disabled | invited`（CHECK）；现有用户全部回填 `active`。
- [ ] `app_user.token_hash` 改为可空（PostgreSQL `DROP NOT NULL`；MySQL `MODIFY token_hash VARCHAR(128) NULL`）：C2 代码停止写该列后仍能创建新用户；V7 再删列。
- [ ] 迁移前检查 `app_user.token_hash` 是否有重复：理论上不应出现，若有则**中止迁移并要求人工处理**（避免 `user_token` 唯一键静默丢凭据）。
- [ ] 邮箱唯一、忽略大小写：
  - PostgreSQL：`CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user (lower(email)) WHERE email IS NOT NULL;`
  - MySQL：函数索引 `CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user ((LOWER(email)));`
  - Service 层写入前统一 `trim().lowercase()`；邮箱为空时允许 NULL。
- [ ] `mfa_enabled` 只预留列，C 阶段不实现 MFA 校验；文档标注。

**V6 · `user_token`（用户 API 令牌）**

- [ ] 新建表（示意）：
  ```sql
  user_token(id, user_id, name, token_prefix, token_hash, purpose,
             expires_at, used_at, last_used_at, revoked_at, created_at)
  -- UNIQUE (token_hash)
  -- INDEX (user_id, created_at)
  -- FK user_id → app_user(id) ON DELETE CASCADE
  -- purpose ∈ legacy | api | invite | password_reset
  -- CHECK (purpose = 'legacy' OR expires_at IS NOT NULL)
  ```
- [ ] 回填现有令牌：
  ```sql
  INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose, created_at)
  SELECT id, 'legacy', '', token_hash, 'legacy', created_at FROM app_user;
  ```
- [ ] `legacy` 行不设过期（ADR §10 要求旧 token 继续有效）；`api` / `invite` / `password_reset` 行必须有过期时间。
- [ ] `invite` / `password_reset` 使用后写 `used_at`，不可复用。

**V6 · `user_session`（Web 会话）**

- [ ] 新建表（示意）：
  ```sql
  user_session(id, user_id, token_hash, csrf_token, ip, user_agent,
               created_at, last_seen_at, expires_at, revoked_at)
  -- UNIQUE (token_hash)
  -- INDEX (user_id, expires_at)
  -- FK user_id → app_user(id) ON DELETE CASCADE
  ```
- [ ] `token_hash` 为会话 ID 的 SHA-256；Cookie 只放明文会话 ID。
- [ ] `csrf_token` 存明文（单独泄露不足以冒用；仍需会话 Cookie）。

**V6 · `service_account`（项目级服务账号）**

- [ ] 新建表（示意）：
  ```sql
  service_account(id, org_id, project_id, name, token_prefix, token_hash,
                  role, status, expires_at, last_used_at, revoked_at, created_at)
  -- UNIQUE (project_id, name)（忽略大小写）
  -- UNIQUE (token_hash)
  -- INDEX (org_id), INDEX (project_id)
  -- role ∈ viewer | editor
  -- status ∈ active | revoked
  -- FK org_id → org(id) ON DELETE RESTRICT
  -- FK project_id → project(id) ON DELETE RESTRICT
  ```
- [ ] `project_id NOT NULL`，`org_id` 冗余；Service 创建时校验 `project.orgId == org_id`。
- [ ] 项目删除时由 `ProjectService.delete` 显式清理服务账号（保持现有 FK RESTRICT 纪律）。

**V6 · `user_identity`（OIDC 身份绑定）**

- [ ] 新建表（示意）：
  ```sql
  user_identity(id, user_id, provider, subject, email, created_at)
  -- UNIQUE (provider, subject)
  -- INDEX (user_id), INDEX (email)
  -- FK user_id → app_user(id) ON DELETE CASCADE
  ```
- [ ] `provider` 为 issuer 的稳定标识（如 `oidc:<issuer>` 或 registration id）；`subject` 为 `sub`。

**V6 · `audit_event`（管理动作审计）**

- [ ] 新建表（示意）：
  ```sql
  audit_event(id, org_id, actor_user_id, actor_name, actor_type,
              action, target_type, target_id, detail, outcome,
              ip, user_agent, created_at)
  -- INDEX (org_id, created_at)
  -- INDEX (org_id, action, created_at)
  -- INDEX (actor_user_id, created_at)
  -- actor_type ∈ user | service_account | anonymous
  -- outcome ∈ success | failure
  ```
- [ ] `org_id` / `actor_user_id` **不建外键**：审计需在用户 / 组织删除后保留；`actor_name` 存快照。
- [ ] `detail` 只存可审计摘要，禁止密码、令牌、Cookie、spec 正文。

**V7 · 收紧与删列（代码切换后）**

- [ ] `DROP COLUMN app_user.token_hash`（已回填 `user_token`）。
- [ ] `DROP COLUMN app_user.is_platform_admin`（已由 `org_membership.org_role` 取代）。
- [ ] 校验：`user_token` 中 `legacy` 行数量与迁移前 `app_user` 非空 token 数一致；
- [ ] 校验：`app_user.status` 无 NULL；邮箱无重复（忽略大小写）。
- [ ] 迁移后启动旧代码会失败，属预期；V7 必须与 C2–C4 代码同一发布窗口。

**兼容与纪律**

- [ ] 分 vendor 维护，不写跨方言 SQL；MySQL 使用 `DATETIME(6)`、`CHECK`、函数索引。
- [ ] 不删除任何业务数据；V7 只删已迁移的凭据 / 兼容列。
- [ ] `ManagementDbMigrationTest` 增加 V5→V6→V7 分段用例与空库 V1→V7。
- [ ] 迁移后校验：现有 admin 用户在 `org_membership` 中为 `org_admin`（V3 已回填，C1 再断言一次）。

**验收**

- [ ] PostgreSQL 18.6：V5 有用户 / token / 团队数据 → V6，旧 token 回填为 `legacy`，用户 `status=active`。
- [ ] MySQL 8.4：同上。
- [ ] V6 → V7：旧列删除；用迁移前的旧 token 仍可通过 `user_token` 认证。
- [ ] 空库 V1 → V7 一次跑通。
- [ ] 双库全量 `:server:test` 与 `:server:ktlintCheck` 通过（V6 不破坏旧写入路径；V7 随代码切换后验证）。

**状态**：待启动。

---

### C2 · 身份领域层与统一认证入口（L）

**Repository / Entity**

- [ ] `UserEntity` 扩展：`email` / `passwordHash` / `passwordUpdatedAt` / `status` / `mustChangePassword` / `mfaEnabled` / `lastLoginAt`；移除 `tokenHash` / `isPlatformAdmin`（V7 后）。
- [ ] `UserRepository`：`findByEmail`（忽略大小写）、`findAllOrdered` 改按 `id` 排序、保留 `findByUsername`。
- [ ] 新增 `UserSessionEntity` / `UserSessionRepository`：
  - `findByTokenHash`、`findAllByUserId`、`deleteAllByUserId`、`deleteByExpiresAtBefore`。
- [ ] 新增 `UserTokenEntity` / `UserTokenRepository`：
  - `findByTokenHash`、`findAllByUserIdOrderByCreatedAtDesc`、`findByIdAndUserId`、`deleteAllByUserId`。
- [ ] 新增 `ServiceAccountEntity` / `ServiceAccountRepository`：
  - `findByTokenHash`、`findAllByProjectIdOrderByNameAsc`、`findByIdAndProjectId`、`deleteAllByProjectId`。
- [ ] 新增 `UserIdentityEntity` / `UserIdentityRepository`：
  - `findByProviderAndSubject`、`findAllByUserId`。
- [ ] `UserRecord`（`kernel:spec-index`）同步字段：`email` / `status` / `hasPassword`；移除 `tokenHash` / `isPlatformAdmin`，测试中的构造 / 工厂同步改造。

**认证原语（建议新包 `io.spechub.server.auth`）**

- [ ] `AuthPrincipal`：统一的人 / 机器身份。
  ```kotlin
  sealed interface AuthPrincipal {
      val name: String
      val orgId: Long
  }
  data class UserPrincipal(val user: UserRecord) : AuthPrincipal
  data class ServicePrincipal(val account: ServiceAccountRecord) : AuthPrincipal
  ```
  - request 属性：`REQ_PRINCIPAL`；`REQ_USER` 仅对 `UserPrincipal` 填充；`REQ_USERNAME` 保留兼容。
- [ ] `PasswordHasher`：Argon2id `encode` / `matches` / `needsUpgrade`；登录成功时按当前参数升级旧哈希。
- [ ] `TokenCodec`：
  - `generate(prefix, bytes = 32)` → `GeneratedToken(secret, hash, displayPrefix)`；
  - 只存 SHA-256；列表 / 日志只显示前缀；
  - 新令牌必须带前缀；`legacy` / `admin-token` 可能无前缀，按长度与字符集做基本校验后统一走 hash 查找，不能因缺前缀拒绝旧凭据。
- [ ] `SessionService`：
  - `create(user, request)`：新随机会话 ID、CSRF token、绝对 / 空闲过期时间；
  - `resolve(rawSessionId)`：校验哈希、过期、吊销、用户状态；
  - `touch(session)`：`last_seen_at` 最多每 5 分钟写一次；
  - `rotate(session)`、`revoke(session)`、`revokeAll(userId)`；
  - `cleanupExpired()`：每日清理。
- [ ] `UserTokenService`：
  - `issue(userId, name, purpose, expiresAt)`、`resolve(raw)`、`list(userId)`、`revoke(id, userId)`、`rotate`；
  - `last_used_at` 最多每 5 分钟写一次；
  - `resolve` 校验 `revoked_at` / `expires_at` / 用户 `status`；
  - V6 已把全部旧 `app_user.token_hash` 回填为 `user_token(purpose=legacy)`，因此新代码只查 `user_token`，不做双读。
- [ ] `ServiceAccountService`（C5 落地，C2 先定接口）：
  - `resolve(raw)`、`create`、`revoke`、`rotate`、`list(projectId)`。

**`TokenAuthFilter` 改造**

- [ ] 解析顺序：
  1. `Authorization: Bearer` → `user_token`（`shu_` / legacy / admin-token）或 `service_account`（`shs_`）；
  2. 否则读 `spechub_session` Cookie → `user_session`；
  3. 都没有 → 匿名。
- [ ] 命中后统一挂 `REQ_PRINCIPAL`、`REQ_ORG`、`REQ_USERNAME`；服务账号的 org 固定为账号所属 org。
- [ ] 每次请求检查用户 `status` / 令牌过期 / 会话吊销：`active` / `invited`（尚未设置密码）放行，`disabled` 立即 401。
- [ ] 无效 Bearer 仍 401；无效 / 过期会话 Cookie 按匿名处理（受保护端点自然 401），并清除 Cookie。
- [ ] Bearer 与 Cookie 同时存在时 Bearer 优先；文档标注。
- [ ] CSRF：会话认证的非安全方法校验 `X-CSRF-Token`，不匹配 403；Bearer 认证豁免；`/api/auth/login`、`/api/auth/password/setup`、OIDC 回调豁免。
- [ ] 白名单：保留 `/actuator`、`/api/auth/`、`/mock/`（既有例外，补注释）；C6 新增 OIDC 路径时只加 `/oauth2/`、`/login/oauth2/` 并注明理由；禁止继续加其它前缀。
- [ ] dev（`authEnabled=false`）：无凭据全放行；带 Bearer 仍解析（admin 兼容）。

**`AuthService` 改造**

- [ ] 新增 `currentPrincipal` / `currentServiceAccount`；`currentUser` 仅返回人类用户；`currentActor` 返回 `username` 或 `service:<name>`。
- [ ] `authEnabled` 收紧为 `!adminToken.isNullOrBlank()`（空串等同未配置，避免 dev 空环境变量误开鉴权）；prod 校验保持不变。
- [ ] `orgRole` / `isOrgAdmin` 去掉 `isPlatformAdmin` 特判，统一走 `org_membership`；bootstrap admin 已是 `org_admin`。
- [ ] `effectiveRole`：
  - `ServicePrincipal`：仅当资源属于 `account.projectId` 时返回账号角色，否则 `null`；
  - `UserPrincipal`：沿用项目 membership + `org_admin` 恒 owner。
- [ ] `roleForRepo` 改用 `currentPrincipal`：服务账号仅在其 `projectId` 返回角色，否则 `null`；写端点（spec / import / promote）因此对跨项目服务账号 403。
- [ ] `canReadProject`：服务账号仅可读所属项目（viewer / editor 均可读 draft + release）；跨项目视为不可见。
- [ ] `visibleProjects` / `visibleProjectIds` / `visibleRepoIds` / `memberProjectIds` / `memberRepoIds` 改用 `currentPrincipal`：服务账号只返回所属项目；跨项目集合必须为空，禁止退化为全量。
- [ ] `requireAuthenticated` 接受人 / 服务账号；新增 `requireHuman` 供 OIDC / 密码 / 团队管理 / 评论 / 订阅等仅人类操作使用。
- [ ] `requireOrgAdmin` 拒绝服务账号；`requireTeam*` 仅人类。
- [ ] `requireProjectVisible` / `requireRepoVisible` 对服务账号跨项目统一 404。
- [ ] 评审：服务账号可读所属项目评审、可提交写回评审（actor=`service:<name>`），但**不能审批 / 打回**（`ReviewController` 使用 `requireHuman`）。
- [ ] `userForToken` 改名为 `principalForToken`，并保留旧方法薄封装供测试过渡。

**调用点修正（`isPlatformAdmin` 清零）**

- [ ] `SpecWriteService.requireOtherReviewer`：平台 admin 自批改为 `org_admin` 自批（或直接禁止自批，按 C0 决策）。
- [ ] `ReviewController`：提交评审使用 principal actor（允许服务账号）；审批 / 打回使用 `requireHuman` 拒绝服务账号，并传 `isOrgAdmin`（替代 `isPlatformAdmin`）用于自批判定。
- [ ] `CommentController`：全部评论端点使用 `requireHuman`；删除评论的 `isPlatformAdmin` 改为 `isOrgAdmin`。
- [ ] `SubscriptionController`：订阅 / 取消 / 通知查询使用 `requireHuman`；服务账号不可订阅。
- [ ] `TeamController`：团队与成员端点使用 `requireHuman`；服务账号不可管理或查看团队目录。
- [ ] `ReleaseController.globalChangelog`：服务账号按项目成员处理，`org_admin` 判定走新方法。
- [ ] `ApiQueryController` 的 `currentUser()?.username` 改为 `currentActor(request)`，支持服务账号。
- [ ] `ApplicationInit`：bootstrap admin 只确保 `org_membership.org_role='org_admin'`；启动时刷新 `user_token` 中 admin 的 `legacy` 行（admin-token 变化时旧值失效）；**不覆盖 `status` / `password_hash`**，被禁用的 admin 重启后仍保持禁用。
- [ ] 全仓 grep `isPlatformAdmin` / `token_hash` / `platformAdmin` 清零（V7 门禁）。

**纪律**

- [ ] 认证解析只在 `TokenAuthFilter` / `AuthService` 一处；Controller 不得自行读 Cookie / Bearer。
- [ ] 密码、令牌、Cookie 不写日志；认证事件只记 `username` / `principal` / IP / 时间。
- [ ] 会话 / 令牌查询走索引；`last_seen` / `last_used` 写节流。
- [ ] 多表吊销（禁用用户、删除用户）用 `@Transactional`。

**测试**

- [ ] `AuthPrincipalTest`：人 / 服务账号解析、禁用 401、过期 401、跨项目不可见。
- [ ] `SessionServiceTest`：创建 / 过期 / 吊销 / 轮换 / 清理 / CSRF。
- [ ] `UserTokenServiceTest`：签发 / 过期 / 吊销 / 旧 legacy 行解析 / 节流。
- [ ] 现有 `AuthApiTest` / `AuthVisibilityTest` / `ReadIsolationTest` 适配新 `UserRecord` 与新认证入口。
- [ ] 双库全量 `:server:test` 与 `:server:ktlintCheck` 通过。

**状态**：待启动。

---

### C3 · 密码登录与会话端点（M）

**登录**

- [ ] `POST /api/auth/login` 请求体兼容两种形态：
  ```json
  { "username": "alice", "password": "..." }
  { "username": "alice", "token": "shu_..." }
  ```
  - `username` 也可传邮箱；忽略大小写；`password` 与 `token` 互斥，同时传返回 400。
  - 密码路径：查用户 → Argon2id 校验 → 检查 `status ∈ active|invited` → 创建会话。
  - token 路径：解析用户令牌（含 legacy / admin-token）→ 检查 `status ∈ active|invited` → 创建会话；`invited` 仅表示尚未设置密码，不阻断已发放的令牌。
  - 成功后 `Set-Cookie: spechub_session=...; HttpOnly; SameSite=Lax; Path=/; Max-Age=604800[; Secure]`，响应返回 `authMethod` / `csrfToken` / `mustChangePassword`。
  - 失败统一 401「用户名或密码错误」；禁用账号同样 401（不泄露状态）。
  - 限流命中返回 429，带 `Retry-After`；新增 `ApiErrorCode.TOO_MANY_REQUESTS(429)`。
  - `authEnabled=false`（dev 未配 admin-token）时保持现状：login 返回 `AUTH_NOT_ENABLED(409)`，无需登录。
- [ ] `POST /api/auth/logout`：吊销当前会话、清 Cookie、204；未登录也返回 204（幂等）。
- [ ] 登录成功写审计 `auth.login.success`；失败写 `auth.login.failure`；限流写 `auth.login.locked`。

**当前用户**

- [ ] `GET /api/auth/me` 扩展：
  ```json
  {
    "authenticated": true,
    "authEnabled": true,
    "authMethod": "session",
    "username": "alice",
    "displayName": "Alice",
    "email": "alice@example.com",
    "status": "active",
    "csrfToken": "...",
    "mustChangePassword": false,
    "hasPassword": true,
    "orgAdmin": true,
    "oidcEnabled": false,
    "oidcLoginUrl": null,
    "orgs": [], "currentOrgId": 1, "currentOrgSlug": "default",
    "projectRoles": {}, "teams": [], "canManage": true
  }
  ```
  - `platformAdmin` 字段在 C 内删除；`orgAdmin` 是唯一组织管理标识，前端 C8 同步改造。
  - `csrfToken` 仅会话认证返回；Bearer 认证返回 `null`。
- [ ] `GET /api/auth/status` 增加 `authMethod` / `oidcEnabled` / `oidcLoginUrl`，供登录框探测。

**密码管理**

- [ ] `POST /api/auth/password`：
  - 请求 `{currentPassword?, newPassword}`；
  - 已有密码时必须校验 `currentPassword`；`mustChangePassword=true` 或 `hasPassword=false` 时允许不带；
  - 更新 `password_hash` / `password_updated_at`，清 `must_change_password`；
  - 吊销该用户**其它**会话（保留当前会话），可选吊销全部 API 令牌（C0 决策：保留令牌，仅吊销会话）；
  - 写审计 `auth.password.change`。
- [ ] `POST /api/auth/password/setup`：
  - 请求 `{token, newPassword}`；
  - 令牌 purpose 必须为 `invite` / `password_reset`，未过期、未使用、用户未禁用；
  - 设置密码、`status=active`、清 `must_change_password`、写 `used_at`；
  - 写审计 `auth.password.setup`；返回 200（不自动登录，前端引导登录）。
- [ ] 密码策略校验：长度 10–128；不得等于用户名 / 邮箱；空白字符串拒绝；错误返回 400 + `fieldErrors`。

**会话与 CSRF**

- [ ] 会话 Cookie 固定名 `spechub_session`；配置 `spechub.session.cookie-secure`（prod 默认 true，dev 默认 false）、`spechub.session.absolute-days=7`、`spechub.session.idle-hours=24`。prod 使用 HTTP 部署时必须显式关闭 `cookie-secure` 或配置 TLS，文档标注。
- [ ] CSRF token：登录与 `/api/auth/me` 返回；前端后续非安全请求带 `X-CSRF-Token`；不匹配返回 403 + `ApiErrorCode.AUTH_CSRF`。
- [ ] 登录时若已有旧会话，先吊销旧会话再创建新会话（防会话固定）。

**限流**

- [ ] `LoginRateLimiter`（内存）：
  - key `identifier:<lower(username|email)>` 与 `ip:<remoteAddr>`；
  - 失败窗口 5 分钟 / 5 次；锁 15 分钟；成功清零；
  - 定时清理过期窗口；
  - 单实例语义写入文档；多实例共享限流留 D1。

**测试**

- [ ] `PasswordLoginApiTest`：密码登录成功（Cookie 属性）、错误密码 401、未知用户 401、禁用 401、邮箱登录、限流 429、成功后 `/me` 带 CSRF。
- [ ] `SessionApiTest`：Cookie 认证访问受保护端点、登出后 401、过期 / 吊销 401、CSRF 缺失 / 错误 403、Bearer 豁免 CSRF。
- [ ] `PasswordChangeApiTest`：改密校验、改密吊销其它会话、邀请 / 重置令牌设置密码、令牌单次使用、密码策略。
- [ ] `AuthApiTest` 旧 token 登录仍可用且现在创建会话；`ReadIsolationTest` / `WebApiTest` 适配 Cookie / Bearer 双通道。
- [ ] 双库全量测试与 ktlint 通过。

**状态**：待启动。

---

### C4 · 账号生命周期与令牌管理（M）

**用户管理（org_admin）**

- [ ] `POST /api/auth/users` 扩展：
  - 请求增加 `email?` / `password?`；
  - 无 `password` → `status=invited`、`mustChangePassword=true`，生成一次性 `invite` 令牌（7 天）并在响应 `setupToken` 返回一次；仍返回一条 `api` 令牌 `token` 兼容旧客户端（`invited` 状态不阻断已发放令牌）；
  - 有 `password` → `status=active`、`must_change_password=true`；
  - 邮箱唯一冲突 409；写入审计 `user.create`。
- [ ] `PATCH /api/auth/users/{id}`：改 `displayName` / `email`；邮箱归一化与唯一校验；审计 `user.update`。
- [ ] `UserView` 扩展 `email` / `status` / `mustChangePassword`，移除 `platformAdmin`；列表与创建响应同步（前端 C8 适配）。
- [ ] `POST /api/auth/users/{id}/disable`：
  - 禁止禁用自己；禁止禁用最后一名可登录的 `org_admin`（`app_user.status ∈ active|invited`、`org_membership.status='active'`、`org_role=org_admin`）；
  - 事务内：`status=disabled`、吊销全部 `user_session`、吊销全部 `user_token`；
  - 审计 `user.disable`；changelog `user.disable`（产品流保留）。
- [ ] `POST /api/auth/users/{id}/enable`：`status=active`；不恢复已吊销会话 / 令牌；审计 `user.enable`。
- [ ] `POST /api/auth/users/{id}/password/reset`：
  - 吊销该用户全部会话与令牌；
  - 设 `must_change_password=true`；
  - 生成 24h 一次性 `password_reset` 令牌，响应 `setupToken` 只显示一次；
  - 审计 `user.password.reset`。
- [ ] `DELETE /api/auth/users/{id}` 扩展：
  - 禁止删除自己；禁止删除最后一名可登录的 `org_admin`；
  - 事务内删除 `user_session` / `user_token` / `user_identity` / `team_member` / `org_membership` / `membership`，再删用户；
  - 审计 `user.delete`；`audit_event` 保留。
- [ ] `AuthController` 用户管理端点移除 `isPlatformAdmin` 特判（改角色 / 删除），统一改为「最后一名可登录 `org_admin`」保护；bootstrap admin 不再特殊。
- [ ] `POST /api/auth/users/{id}/role`：改组织角色；禁止把最后一名可登录的 `org_admin` 降为 `org_member`；审计 `user.role`。

**用户令牌管理**

- [ ] `GET /api/auth/tokens`：当前用户令牌列表（`id/name/prefix/purpose/expiresAt/lastUsedAt/createdAt/status`），不含 hash / 明文。
- [ ] `POST /api/auth/tokens`：`{name, expiresInDays?}`；默认 90 天，最大 365 天；返回 `token` 明文一次；审计 `token.create`。
- [ ] `DELETE /api/auth/tokens/{id}`：只能吊销自己的令牌；审计 `token.revoke`。
- [ ] `GET /api/auth/users/{id}/tokens` / `DELETE /api/auth/users/{id}/tokens/{tokenId}`：org_admin 运维使用；审计 `token.admin.revoke`。
- [ ] `POST /api/auth/users/{id}/token` 兼容端点：吊销旧 `legacy` / `api` 令牌并签发新 `api` 令牌，响应仍为 `{user, token}`。
- [ ] 令牌解析只接受未吊销、未过期、用户 active 的记录；`last_used_at` 写节流 5 分钟。
- [ ] 过期 / 吊销清理任务：每日删除 `expires_at < now - 7d` 或 `revoked_at < now - 7d` 的会话 / 令牌（审计记录不删）。

**V7 收紧**

- [ ] 代码切换完成后执行 `V7__identity_tighten.sql`，删除 `app_user.token_hash` / `app_user.is_platform_admin`。
- [ ] grep 门禁：`is_platform_admin`、`isPlatformAdmin`、`tokenHash`（`app_user` 语境）、`platformAdmin` 仅允许出现在历史迁移 / 文档中。
- [ ] `ManagementDbMigrationTest` 覆盖 V6→V7 后旧 token 仍可认证、用户列表正常。

**保护规则**

- [ ] 最后一名可登录的 `org_admin`（`app_user.status ∈ active|invited` + `org_membership.status='active'`）不可禁用 / 降级 / 删除（409，提示先指定新的 org_admin）。
- [ ] 禁用 / 重置 / 删除不可作用于当前操作者自身（删除已有保护；禁用 / 重置补齐）。
- [ ] 所有生命周期动作写 `audit_event`；用户可见的产品流仍写 `changelog`。

**测试**

- [ ] `UserLifecycleApiTest`：禁用后旧会话 / 旧令牌立即 401；启用不恢复；重置令牌单次；最后 org_admin 保护；邮箱冲突；删除清理关系。
- [ ] `UserTokenApiTest`：创建 / 列表 / 吊销 / 过期 / 他人令牌 404 / admin 吊销；旧 token 在 V7 后仍有效。
- [ ] `AuthApiTest` 改造：`is_platform_admin` 移除后的 admin 语义、`/me` 字段。
- [ ] 双库全量测试与 ktlint 通过。

**状态**：待启动。

---

### C5 · 项目级服务账号（M）

**领域层**

- [ ] `ServiceAccountRecord`：`id/orgId/projectId/name/prefix/role/status/expiresAt/lastUsedAt/revokedAt/createdAt`。
- [ ] `ServiceAccountService`：
  - `create(orgId, projectId, name, role, expiresAt, actor)`：校验项目属于组织；名称项目内忽略大小写唯一；角色仅 `viewer|editor`；过期 1–365 天；
  - `list(projectId)`：不含 hash；
  - `revoke(id, projectId, actor)`：置 `status=revoked` + `revoked_at`，立即生效；
  - `rotate(id, projectId, actor)`：事务内吊销旧密钥并签发新密钥，返回明文一次；
  - `resolve(raw)`：校验前缀、hash、状态、过期、项目未删除。
- [ ] `ProjectService.delete`：删除项目前显式 `deleteAllByProjectId` 清理服务账号（或返回 409 要求先吊销，C0 拍板；建议清理并审计）。

**API**

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/api/service-accounts?projectId=` | 项目 `admin+` / `org_admin` | 列表；跨项目 404 |
| `POST` | `/api/service-accounts` | 项目 `admin+` / `org_admin` | 创建；明文只返回一次 |
| `DELETE` | `/api/service-accounts/{id}` | 项目 `admin+` / `org_admin` | 吊销；跨项目 404 |
| `POST` | `/api/service-accounts/{id}/rotate` | 项目 `admin+` / `org_admin` | 轮换；旧密钥立即失效 |

- [ ] 创建请求 `{projectId, name, role, expiresInDays?}`；响应 `{account, token}`。
- [ ] 列表视图不返回 `tokenHash` / 明文；显示 `prefix` 便于识别。
- [ ] 服务账号与项目成员可同名，但不共享成员表；审计 actor 用 `service:<name>` 区分。

**认证与授权**

- [ ] `TokenAuthFilter` 识别 `shs_` 前缀 → `ServicePrincipal`。
- [ ] `AuthService.effectiveRole`：服务账号仅在其 `projectId` 返回自身角色；其它项目 `null`，读 404 / 写 403。
- [ ] 服务账号不可访问：`/api/auth/users`、`/api/teams`、`/api/projects` 管理动作（POST/PATCH/DELETE/transfer/archive）、`/api/audit`、`/api/service-accounts`（管理自身也不行）；评论 / 订阅 / 评审审批等人类协作端点使用 `requireHuman` 拒绝。
- [ ] 服务账号可访问：所属项目的 repos / apis / docs / export / mock / spec 读写（按角色）、导入、评审状态读取与写回提交；`GET /api/projects` 只返回所属项目；actor 记 `service:<name>`。
- [ ] 服务账号对 `/api/apis` 等列表的可见集合只包含所属项目；空集合返回空，禁止退化全量。
- [ ] `/mock/{repoId}/**`：携带有效服务账号且项目匹配时可用；跨项目 / 无效令牌 404 / 401（D1 再做 public/mock key 收敛）。

**MCP / CLI**

- [ ] 文档更新：`SPECHUB_TOKEN` 可使用服务账号令牌（MCP `apps/mcp/src/config.ts`；CLI `~/.spechub.json` / `SPECHUB_TOKEN`）。
- [ ] 明确服务账号角色：MCP 写工具需 `editor`；只读工具 `viewer` 即可。
- [ ] 令牌过期 / 吊销后的错误提示写入 README 与 CLI README；无需改客户端代码。

**测试**

- [ ] `ServiceAccountApiTest`：创建 / 列表 / 吊销 / 轮换；viewer 可读不可写；editor 可写；跨项目 404；吊销后立即 401；过期 401；不能登录 Web；不能访问 org_admin 端点。
- [ ] `ReadIsolationTest` 扩展：服务账号在项目 A，访问项目 B 的列表 / 详情 / 导出 / mock 全部 404。
- [ ] `ProjectApiTest`：项目删除时服务账号清理（或 409 行为与 C0 一致）。
- [ ] 双库全量测试与 ktlint 通过。

**状态**：待启动。

---

### C6 · OIDC 登录（L）

**依赖与配置**

- [ ] 引入 `spring-boot-starter-oauth2-client`（仅用于 OIDC 链；不引入表单登录 / 默认全量安全链）。
- [ ] 依赖审计：OIDC 相关库若传递 Jackson 2，仅允许运行期由库内部使用；业务代码不得 import，`verifyNoJackson2Usage` 与依赖树核对（沿用 CLAUDE.md 的 Jackson 2 边界）。
- [ ] `SpecHubProperties` 增加 `OidcProperties`：
  ```yaml
  spechub:
    oidc:
      enabled: false
      registration-id: spechub
      issuer-uri: ${SPECHUB_OIDC_ISSUER_URI:}
      client-id: ${SPECHUB_OIDC_CLIENT_ID:}
      client-secret: ${SPECHUB_OIDC_CLIENT_SECRET:}
      scopes: openid,email,profile
      redirect-uri: ${SPECHUB_EXTERNAL_URL}/login/oauth2/code/spechub
      auto-provision: false
      default-org-slug: default
      email-claim: email
      require-verified-email: true
  ```
- [ ] 校验：`enabled=true` 时 `issuer-uri` / `client-id` / `client-secret` 必填，缺失启动失败；`client-secret` 只经环境变量注入。
- [ ] `GET /api/auth/status` / `/api/auth/me` 返回 `oidcEnabled` / `oidcLoginUrl`（`/oauth2/authorization/spechub`）。
- [ ] prod 文档：OIDC 回调地址需在 IdP 注册；`external-url` 必须为浏览器可访问地址。dev 使用 Vite 代理时，`external-url` / `redirect-uri` 应指向前端开发地址（如 `http://localhost:5173`），保证回调与 Cookie 同源。

**安全链**

- [ ] `OidcSecurityConfig` 结构：
  - 用 `ClientRegistration.withRegistrationId(...)` 从 `OidcProperties` 构造 `ClientRegistrationRepository`（不依赖 `spring.security.oauth2.client.*` 前缀，避免两套配置）；`issuer-uri` 启动时发现端点，若客户 IdP 不允许启动外联，允许显式配置 authorization / token / jwks URI；
  - `@Order(1)` `SecurityFilterChain`，`securityMatcher("/oauth2/**", "/login/oauth2/**")`；
  - `oauth2Login` + 自定义 success / failure handler；
  - `csrf` 对该链关闭（OIDC 用 state/nonce/PKCE）；`sessionCreationPolicy = IF_REQUIRED`；
  - 显式兜底链 `@Order(2)` `securityMatcher("/**")`：`permitAll` + `csrf.disable()` + `requestCache.disable()` + `sessionCreationPolicy(STATELESS)`，避免 Boot 默认安全链拦截现有端点或额外创建 HttpSession。
- [ ] `TokenAuthFilter` 对 `/oauth2/`、`/login/oauth2/` 放行（`shouldNotFilter`，注释说明仅用于 OIDC 握手；认证结果仍写 `user_session`）。
- [ ] 不在 OIDC 链里做业务鉴权；登录成功后统一走 `SessionService.create`。
- [ ] C 阶段 OIDC 仍要求配置 `admin-token` 以启用鉴权（`authEnabled` 语义不变）；D1 平台化后再由组织配置决定。

**登录与绑定**

- [ ] `OidcLoginService.login(claims, request)`：
  1. 校验 `issuer` / `subject` 非空；`email_verified` 为 false 且 `require-verified-email=true` → 拒绝；
  2. 按 `user_identity(provider, subject)` 查用户 → 检查 `status=active`；
  3. 未绑定 → 按 `lower(email)` 查用户 → 绑定 `user_identity`；
  4. 仍未找到 → `auto-provision=false` 拒绝（重定向登录页错误）；`true` 时创建用户：用户名取邮箱 local part（冲突追加序号）、默认 `org_member`、`status=active`、`password_hash=NULL`，写审计 `auth.oidc.provision`；
  5. 创建会话、写审计 `auth.oidc.login` / `auth.oidc.link` / `auth.oidc.provision`。
- [ ] 成功 handler：`SessionService.create` → `Set-Cookie` → 重定向 `/`（只允许站内相对路径）；失败 handler 重定向 `/?loginError=oidc`，不把 IdP 错误详情放 URL。
- [ ] `user_identity` 记录 `provider = "oidc:" + issuer`、`subject`、`email` 快照；重复绑定 409 / 忽略。
- [ ] 已有密码用户绑定 OIDC 后两种登录方式并存；OIDC-only 用户 `password_hash=NULL`，密码登录统一 401。
- [ ] 不做 RP-initiated logout、不刷新 IdP token、不存 IdP access/refresh token。

**测试**

- [ ] `OidcPropertiesTest`：启用时必填、prod 校验、默认值。
- [ ] `OidcLoginServiceTest`：已绑定身份、邮箱绑定、未验证邮箱拒绝、auto-provision 开 / 关、禁用用户拒绝、重复身份、默认 org 归属。
- [ ] `OidcSecurityConfigTest`：OIDC 路径不被 `TokenAuthFilter` 拒绝；现有 API 仍按原认证逻辑。
- [ ] 手动 e2e（C9.1）：Keycloak / 测试 IdP 浏览器登录 → 会话 → `/api/auth/me` → 登出；无 IdP 时登录页只显示密码 / 令牌。
- [ ] 双库全量测试与 ktlint 通过。

**状态**：待启动。

---

### C7 · 管理动作审计（M）

**领域层**

- [ ] `AuditEventEntity` / `AuditEventRepository`：
  - `save`、`findByOrgIdAndCreatedAtBetween...`、`findByOrgIdAndActorName...`、`findByOrgIdAndAction...`；
  - 复杂组合查询用 `JdbcTemplate`，分页 `limit` / `offset`。
- [ ] `AuditService`：
  - `record(orgId, principal, action, targetType, targetId, detail, outcome, request)`；
  - `actorType`：`user` / `service_account` / `anonymous`；
  - `actorName`：用户名或 `service:<name>`；
  - `detail` 截断（如 1024 字符），禁止密码 / 令牌 / Cookie / spec 正文；
  - 写失败：管理动作同事务失败；登录类尽力而为 + ERROR 日志（C0 确认）。
- [ ] 查询 `list(orgId, filters, limit, offset)`：默认最近 30 天，`limit` 默认 50 / 最大 200。

**写点接入**

- [ ] 认证：`auth.login.success` / `auth.login.failure` / `auth.login.locked` / `auth.logout` / `auth.password.change` / `auth.password.setup` / `auth.oidc.*`。
- [ ] 用户：`user.create` / `user.update` / `user.role` / `user.disable` / `user.enable` / `user.password.reset` / `user.delete`。
- [ ] 令牌：`token.create` / `token.revoke` / `token.admin.revoke` / `token.rotate`。
- [ ] 服务账号：`service_account.create` / `service_account.revoke` / `service_account.rotate`。
- [ ] 组织级管理动作（与现有 changelog 双写，审计用）：
  - 项目：`project.create` / `update` / `archive` / `transfer` / `delete`；
  - 项目成员：`project.member.add` / `role` / `delete`；
  - 团队：`team.create` / `update` / `delete` / `team.member.*`；
  - Git：`git_connection.create` / `disable`；`repo.binding.create` / `verify` / `activate` / `retire` / `delete`。
- [ ] 内容写操作（spec / comment / review / subscription / mock）继续只写 `changelog`，不写 `audit_event`，避免审计噪音。
- [ ] `actor_user_id` / `org_id` 从当前 principal / 请求上下文获取；服务账号 actor 为 `service_account`。
- [ ] 登录失败时若用户名存在 → 用该用户 org；否则用默认 org（C 单组织）；D1 多组织再收敛。

**API**

- [ ] `GET /api/audit?from=&to=&actor=&action=&targetType=&outcome=&limit=&offset=`：
  - 仅 `org_admin`；服务账号 403；项目 admin 403；
  - 固定当前 org，禁止跨 org；
  - 按 `created_at DESC, id DESC` 排序；
  - 返回 `AuditEventView(id, actorName, actorType, action, targetType, targetId, detail, outcome, ip, createdAt)`；
  - `from` / `to` 使用 ISO Instant；参数非法 400。
- [ ] 审计查询本身不写审计（避免自增循环）；仅 INFO 日志。

**保留与清理**

- [ ] `spechub.audit-retention-days=365`；每日清理 `created_at < now - retention` 的记录。
- [ ] 备份：`audit_event` 随管理库备份；运维手册补说明。

**测试**

- [ ] `AuditApiTest`：登录成功 / 失败、禁用、令牌、服务账号、项目 / 团队动作写入；org_admin 可查；项目 admin / 服务账号 403；actor / action / outcome / 时间过滤；跨 org 不可见；`detail` 不含敏感值。
- [ ] `AuditRetentionTest`：过期记录清理，未过期保留。
- [ ] 双库全量测试与 ktlint 通过。

**状态**：待启动。

---

### C8 · 前端账号与安全改造（M）

**`apps/web/src/lib/api.ts`**

- [ ] 去掉 Web 默认路径的 `localStorage` 长期令牌：
  - 删除 / 停用 `getAdminToken` / `setAdminToken` 的默认使用；
  - 启动时清除历史遗留的 `spechub.admin-token` localStorage 键（用户需用密码 / OIDC / 令牌重新登录）；
  - `j()` 设置 `credentials: "same-origin"`（`VITE_API_BASE` 跨域时必须 `include` + 后端 CORS，文档标注推荐 Vite 代理）；
  - 会话登录后把 `csrfToken` 放内存变量，非 GET/HEAD 请求带 `X-CSRF-Token`；
  - 401 时清空当前用户并触发登录框；
  - 保留「兼容令牌登录」路径，但不在 Web 持久化。
- [ ] 类型扩展：`AuthStatus` / `Me` 增加 `authMethod` / `csrfToken` / `email` / `status` / `mustChangePassword` / `hasPassword` / `oidcEnabled` / `oidcLoginUrl`；移除 `platformAdmin`，统一用 `orgAdmin`。
- [ ] 新增 API：`logout` / `changePassword` / `setupPassword` / 令牌 CRUD / 服务账号 CRUD / `listAudit`。

**`LoginDialog.tsx`**

- [ ] 默认密码登录：用户名或邮箱 + 密码；
- [ ] `oidcEnabled` 时显示「使用 SSO 登录」按钮，跳转 `oidcLoginUrl`（相对路径，保持同源）；
- [ ] Vite 代理增加 `/oauth2`、`/login/oauth2`（OIDC 回调必须经前端同源代理，否则 Cookie / 回调落到错误端口）；
- [ ] 「使用令牌登录」折叠为高级选项（兼容 admin-token / 用户令牌）；
- [ ] 错误统一展示，不区分用户不存在 / 密码错误；429 显示「尝试过于频繁」；
- [ ] 登录成功后不再写 localStorage。

**`App.tsx`**

- [ ] 启动探测 `/api/auth/status` + `/api/auth/me`；会话有效则不弹登录框；
- [ ] 账号菜单：
  - `账号与安全…`（所有人）；
  - `服务账号…`（当前项目 admin+ / org_admin）；
  - `审计日志…`（org_admin）；
  - `团队管理…`（保留）；
  - `退出登录` 调用 `POST /api/auth/logout` 后刷新；
- [ ] 移除 `platformAdmin` 判断，统一 `orgAdmin`；
- [ ] `mustChangePassword=true` 时强制打开改密对话框；
- [ ] dev 免登录（`authEnabled=false`）不弹登录、不显示安全入口。

**新增对话框**

- [ ] `SecurityDialog.tsx`：
  - 资料：用户名 / 邮箱 / 状态 / 组织角色；
  - 修改密码（当前密码 + 新密码 + 确认）；
  - 我的令牌：列表（前缀 / 名称 / 过期 / 最近使用）、创建（名称 + 有效期）、吊销；新令牌只显示一次并提供复制提示；
  - OIDC 绑定状态（只读；不提供解绑）。
- [ ] `ServiceAccountDialog.tsx`：
  - 项目选择（默认当前项目）；
  - 列表（名称 / 角色 / 前缀 / 过期 / 最近使用 / 状态）；
  - 创建（名称 / viewer|editor / 有效期 1–365 天）；密钥只显示一次；
  - 吊销 / 轮换（二次确认）。
- [ ] `AuditDialog.tsx`：
  - 过滤：actor / action / targetType / outcome / 时间范围；
  - 表格：时间 / 操作者 / 动作 / 目标 / 结果 / IP / 摘要；
  - 分页（limit 50，最多 200）；
  - 项目 admin / 非 org_admin 不显示入口。

**文档与构建**

- [ ] `apps/web` `tsc -b && vite build` 通过；
- [ ] 文案统一「账号与安全」「服务账号」「审计日志」；
- [ ] 浏览器手测清单随 C9 落文档。

**状态**：待启动。

---

### C9 · 测试矩阵与双库验证（L）

**迁移测试（`ManagementDbMigrationTest`）**

- [ ] V5（含用户 / 旧 token / 团队 / 订阅数据）→ V6：`app_user` 新列、`user_token` legacy 回填、`user_session` / `service_account` / `user_identity` / `audit_event` 建表、旧数据可读。
- [ ] V6 → V7：`token_hash` / `is_platform_admin` 删除；旧 token 通过 `user_token` 仍可认证；`org_membership` admin 关系不变。
- [ ] 空库 V1 → V7 一次跑通。
- [ ] 邮箱唯一（忽略大小写）、服务账号名称项目内唯一、单主 / FK 约束不回归。

**新增集成测试**

| 测试类 | 覆盖 |
|---|---|
| `PasswordLoginApiTest` | 密码 / 邮箱登录、错误凭据、禁用、限流、Cookie 属性 |
| `SessionApiTest` | 会话认证、登出、过期、吊销、CSRF、Bearer 豁免 |
| `PasswordChangeApiTest` | 改密、邀请 / 重置令牌、单次使用、密码策略、其它会话吊销 |
| `UserLifecycleApiTest` | 禁用 / 启用 / 删除 / 最后 org_admin 保护 / 邮箱冲突 |
| `UserTokenApiTest` | 创建 / 列表 / 吊销 / 过期 / admin 吊销 / V7 后旧 token |
| `ServiceAccountApiTest` | CRUD / 轮换 / 跨项目 / viewer-editor / 吊销即时 / 不可登录 Web |
| `AuditApiTest` | 事件写入、org_admin 查询、过滤、跨 org、敏感值 |
| `AuditRetentionTest` | 过期记录清理、未过期保留 |
| `OidcLoginServiceTest` | 身份绑定 / 邮箱绑定 / auto-provision / 未验证邮箱 / 禁用 |
| `AuthPrincipalTest` | 人 / 服务账号 principal、跨项目不可见 |

**改造现有测试**

- [ ] `AuthApiTest`：旧 token 登录创建会话；`is_platform_admin` 移除；`/me` 字段；admin 语义改为 `org_admin`。
- [ ] `AuthVisibilityTest` / `ReadIsolationTest`：Cookie / Bearer 双通道；服务账号隔离。
- [ ] `ProjectApiTest` / `ProjectMembershipApiTest` / `TeamApiTest` / `RepoBindingApiTest`：审核动作审计断言（如涉及）；项目删除服务账号清理。
- [ ] `WebApiTest` / `CommentApiTest` / `ReviewApiTest` / `SubscriptionApiTest` / `ImportExportApiTest`：dev 免登录回归；`currentUser` / `isPlatformAdmin` 变更适配。
- [ ] `ValidationContractTest`：新请求体校验（登录、密码、用户、服务账号、审计参数）。

**门禁命令**

- [ ] `./gradlew check --rerun-tasks`（PostgreSQL）通过；
- [ ] `./gradlew :server:test -Dspechub.test.db=mysql --rerun-tasks` 通过；
- [ ] `./gradlew :server:ktlintCheck` 通过；
- [ ] `cd apps/web && npm run build` 通过；
- [ ] dev 免登录回归（Doc / Comment / Web / Subscription / ImportExport）通过；
- [ ] grep 门禁：`is_platform_admin` / `isPlatformAdmin` / `platformAdmin` / `app_user.token_hash` 无业务代码引用。

### C9.1 手动 e2e 清单（浏览器）

> 自动化覆盖 API 链路；以下清单用于 UI 联调 / 验收，待人工执行。

1. 启动启用鉴权的后端（配置 `SPECHUB_ADMIN_TOKEN`），打开 Web：登录框出现，浏览器 localStorage 无长期 token。
2. `admin` 用 admin-token 登录：账号菜单显示 `org_admin`；`/api/auth/me` 返回 `authMethod=session` 与 CSRF token。
3. 账号与安全 → 修改密码；登出后旧密码失效、新密码可登录。
4. 创建用户 A（邮箱 + 初始密码），A 首次登录被要求改密；改密后进入系统。
5. 创建用户 B（邀请），复制 `setupToken` 设置密码；令牌第二次使用失败。
6. 禁用 A：A 当前浏览器下一次请求 401；A 的 API 令牌 401；重新启用后旧会话 / 旧令牌仍不可用。
7. 创建用户令牌：列表只显示前缀；吊销后立即 401。
8. 当前项目 admin 创建服务账号（viewer / editor）：用 `SPECHUB_TOKEN=shs_...` 调 MCP 只读 / 写工具；viewer 写入 403。
9. 服务账号访问其它项目 API：列表 / 详情 / 导出 / mock 均 404。
10. 配置 OIDC（测试 IdP）：登录页出现 SSO 按钮；登录后绑定 `user_identity`；再次登录直接命中绑定；禁用用户后 SSO 登录被拒。
11. 审计日志：org_admin 能看到登录、禁用、令牌、服务账号、项目 / 团队动作；项目 admin 入口不可见；过滤与分页可用。
12. dev 免登录模式：不弹登录、账号菜单显示「免登录」、原有编辑 / 评论 / 订阅行为不回归。

**状态**：待启动。

---

### C10 · 文档与收尾（S）

- [ ] 开发计划新增 P2.7（阶段 C）并勾选。
- [ ] 本文档状态更新、任务勾选。
- [ ] README 补账号体系说明：密码 / 会话 / OIDC、令牌类型与前缀、服务账号、CLI/MCP 使用。
- [ ] ADR §13 开放问题回填：
  - 2（服务账号是否提前到 A）→ C 落地，项目级；
  - 5（SSO 优先级）→ OIDC 优先，SAML / SCIM 延后；
  - 12（平台运营 IdP）→ D1，不在 C；
  - 新增 / 更新 C 阶段迁移说明（V6 / V7、旧 token 兼容）。
- [ ] `SpecHub-管理数据持久化选型.md` 补 Phase 6：新表、会话 / 令牌 / 审计数据归属与备份范围。
- [ ] `SpecHub-运维手册.md` 补：
  - 会话 / 令牌清理任务；
  - 禁用用户 / 重置密码 / 最后 org_admin 保护；
  - OIDC 配置与回调地址；
  - 审计保留与查询；
  - 备份新增表。
- [ ] `SpecHub-UI方案.md` 补登录、账号与安全、服务账号、审计页。
- [ ] `.env.example` / compose 补 OIDC 环境变量与会话 Cookie 配置说明。
- [ ] `SpecHub-后端编码规范.md` 认证章节更新：统一 principal、CSRF、令牌不落日志、V6/V7 迁移纪律。

**状态**：待启动。

---

## 4. 关键路径与并行

**关键路径**：

```text
C0 → C1 → C2 → C3 → C4 → C5/C6 → C7 → C8 → C9 → C10
```

**两人并行建议**：

- 一人：C1（迁移）→ C2（领域与认证入口）→ C3（会话登录）→ C4（生命周期 / V7）。
- 一人：C3 完成后接 C5（服务账号）→ C6（OIDC）→ C7（审计）。
- C8 等 C3–C7 的 API 契约稳定后开始；C9 贯穿，迁移测试可在 C1 完成后先行。
- C6 依赖外部 IdP 时，可先用单元测试 + 本地 Keycloak 手动验证，不阻塞 C7 / C8。

**一人串行建议**：C0 → C1 → C2 → C3 → C4 → C5 → C6 → C7 → C8 → C9 → C10。

---

## 5. 安全门禁

- **门禁 1（迁移）**：V6 回填完成前不改认证写路径；V7 删除旧列前，grep 门禁必须清零，且旧 token 兼容测试全绿。
- **门禁 2（会话）**：Cookie 必须 `HttpOnly` + `SameSite=Lax`（prod `Secure`）；会话认证的非安全请求必须校验 CSRF；会话 ID 登录时轮换。
- **门禁 3（禁用）**：禁用用户必须在同一事务吊销全部会话与令牌；下一次请求立即 401；测试覆盖会话与令牌两条路径。
- **门禁 4（服务账号）**：服务账号只能访问所属项目；跨项目读 404 / 写 403；不可访问 org_admin / 团队 / 审计端点；吊销立即生效。
- **门禁 5（密码）**：Argon2id；不记录明文；登录失败统一 401；限流生效；重置令牌一次性。
- **门禁 6（OIDC）**：只绑定已验证邮箱；`auto-provision` 默认关闭；state / nonce / PKCE 由库处理；不存 IdP token。
- **门禁 7（审计）**：`audit_event` 不含密码 / 令牌 / Cookie / spec 正文；仅 `org_admin` 可查；跨 org 不可见。
- **门禁 8（dev）**：`authEnabled=false` 下原有行为不回归；启用鉴权后默认拒绝。
- **门禁 9（日志）**：认证类日志只记 `username` / `principal` / IP / 时间，不记凭据。

---

## 6. 前置假设（C0 待确认）

> 下表为建议默认值；C0 拍板后回填结论与影响。

| 项 | 建议默认 | 影响 |
|---|---|---|
| 认证架构 | 渐进式，保留 `TokenAuthFilter`；OIDC 独立链 | C2 / C6 |
| 会话存储 | DB `user_session` + HttpOnly Cookie | C1 / C2 / C3 |
| 会话时效 | 绝对 7 天 / 空闲 24 小时 | C3 |
| CSRF | 会话同步令牌 + `X-CSRF-Token` | C3 / C8 |
| 密码哈希 | Argon2id + `spring-security-crypto` + BouncyCastle | C2 / C3 |
| 密码策略 | 10–128，禁止等于用户名 / 邮箱 | C3 |
| 令牌前缀 | `shu_` / `shs_` / `shsess_` | C1 / C2 |
| 旧令牌 | V6 回填、继续有效；V7 删列 | C1 / C4 |
| `is_platform_admin` | C 内移除，改用 `org_membership.org_role` | C2 / C4 / C8 |
| 服务账号 | 项目级、viewer/editor、默认 90 天 | C5 |
| OIDC | 单 IdP、PKCE、邮箱验证、auto-provision 默认关 | C6 |
| 邮箱 | 存储 + 唯一，不发信 | C1 / C4 |
| 禁用语义 | 全局 `app_user.status`，事务内吊销 | C4 |
| 审计存储 | 新表 `audit_event`，保留 365 天 | C7 |
| 登录限流 | 内存 5 次 / 5 分钟，锁 15 分钟 | C3 |
| 认证开关 | 继续以 `admin-token` 是否存在为准 | C2 / C3 / C6 |
| 最后 org_admin 保护 | 不可禁用 / 降级 / 删除 | C4 |
| V6/V7 | 两阶段迁移，V7 与代码同窗口 | C1 / C4 |

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 认证入口重构遗漏端点 | 鉴权绕过 / 旧客户端失效 | 保留 `TokenAuthFilter` 单入口；`AuthPrincipal` 薄抽象；全量测试 + grep 门禁；先兼容后删除 |
| Cookie + CSRF 配置错误 | CSRF / 会话固定 | `HttpOnly` + `SameSite=Lax` + 同步令牌；登录轮换会话；安全测试覆盖 |
| 禁用后旧凭据仍可用 | 安全事件 | 禁用事务内吊销会话与令牌 + 每请求状态检查 + 专项测试 |
| 旧 token 迁移失败 | CLI / MCP 全部失效 | V6 回填 + V7 前双读兼容；迁移测试覆盖旧 token 认证；V7 与代码同窗口 |
| `is_platform_admin` 移除改变自批 / 管理语义 | 评审 / 评论行为回归 | 统一映射为 `org_admin`；`SpecWriteService` / `CommentController` 专项测试 |
| 服务账号跨项目越权 | 租户内横向移动 | principal 只带 `projectId`；跨项目 404/403；`ReadIsolationTest` 扩展 |
| 会话表 / 审计表增长 | 管理库膨胀、查询变慢 | 每日清理；`(org_id, created_at)` / `(user_id, expires_at)` 索引；保留期配置 |
| `last_seen` / `last_used` 每请求写 | 写放大 | 5 分钟节流；只在命中时更新 |
| OIDC 与现有 Filter 冲突 | 登录回调 401 / 默认安全链拦截 | 独立 `securityMatcher` + 显式兜底链 + OIDC 路径白名单注释 + 集成测试 |
| OIDC 自动建号滥用 | 垃圾账号 | 默认关闭；仅已验证邮箱；D2 反滥用统一处理 |
| 邮箱重置缺失 | 用户忘记密码无法自助 | C 由管理员重置并一次性发令牌；D2 接入邮件 |
| 最后一名 org_admin 被禁用 | 组织不可管理 | 服务层 409 保护 + 测试；平台恢复流程 D1 |
| 内存限流多实例失效 | 暴力破解 | C 单实例语义写入文档；D1 改共享存储 |
| MySQL 函数索引 / 时间精度差异 | 迁移失败 / 唯一约束失效 | V6/V7 分 vendor；双库迁移测试；`DATETIME(6)` + UTC |
| 前端跨域 Cookie | 登录态丢失 | 推荐 Vite 代理同源；`VITE_API_BASE` 跨域需 CORS credentials，文档标注 |

---

## 8. 交付物清单

- 管理库 `V6__identity_and_audit.sql`、`V7__identity_tighten.sql`（PG/MySQL）与迁移矩阵测试。
- `UserSession` / `UserToken` / `ServiceAccount` / `UserIdentity` / `AuditEvent` 领域层。
- `AuthPrincipal`、`PasswordHasher`、`TokenCodec`、`SessionService`、`UserTokenService`、`ServiceAccountService`、`AuditService`。
- `TokenAuthFilter` 双通道认证（Bearer + 会话）、CSRF 校验、状态检查。
- `AuthController` 扩展：密码登录 / 登出 / 改密 / 重置、用户生命周期、令牌管理。
- `ServiceAccountController` 与跨项目隔离。
- OIDC 登录链、`user_identity` 绑定、配置与测试。
- `GET /api/audit` 与关键写点审计接入。
- 前端：`LoginDialog` 改造、`SecurityDialog`、`ServiceAccountDialog`、`AuditDialog`、`api.ts` 会话 / CSRF 改造。
- 测试：新增 10 个测试类（见 C9）+ `SessionServiceTest` / `UserTokenServiceTest` 单测 + `ManagementDbMigrationTest` 扩展 + 现有鉴权 / 隔离测试改造。
- 文档：开发计划 P2.7、README、ADR §13 回填、持久化选型 Phase 6、运维手册、UI 方案、编码规范认证章节。
