# SpecHub · 阶段 C 任务拆解（账号与审计）

> **日期**：2026-11-20　**状态**：C0 / C1 / C2 / C3 / C4 / C5 / C6 / C7 已完成；C8–C10 待启动
> **依据**：[SpecHub-ADR-001-管理模型与多租户](SpecHub-ADR-001-管理模型与多租户.md) §4.1 / §4.2 / §7.1 / §8.1 / §9 / §10 / §15
> **范围**：Spring Security Web 认证底座、Spring Session 分布式会话、密码 / OIDC 登录、账号禁用与邀请、可过期可吊销令牌、项目级服务账号、管理动作审计查询。
> **不覆盖**：多组织切换 / 组织生命周期 / 平台运营控制面 / break-glass（阶段 D1）、自助注册 / 邮箱验证 / 域名认领（阶段 D2）、SAML / SCIM、MFA / WebAuthn、组织级服务账号、`project_group` / `api_group`。

---

## 1. 目标与完成定义

**目标**：把当前「用户名 + 长期 token」的临时身份体系升级为可运维的账号体系：人用密码或 OIDC 登录并持有 Spring Security / Spring Session 管理的 HttpOnly 会话，机器用可过期、可吊销的项目级服务账号，所有管理动作可审计。

**阶段 C 完成定义（DoD）**：

- [ ] Web 登录走 **Spring Security + Spring Session Data Redis** 的 HttpOnly 会话，浏览器不再把长期令牌放 localStorage；密码登录可用，配置 OIDC 后可用 `oauth2Login` 登录；
- [ ] Spring Security 两条链：**Web Session 链**（密码 / OIDC / CSRF / 安全头）与 **Bearer API 链**（opaque 用户令牌 / 服务账号，无 CSRF、无会话）；**所有环境都必须真实登录，不设 dev 放行链**；
- [ ] `app_user` 具备 `email` / `password_hash` / `status`，密码使用 **Argon2id**（`Argon2PasswordEncoder`）存储；
- [ ] 账号可禁用 / 启用 / 邀请 / 重置密码；**禁用后该用户所有会话与令牌在下一次请求立即失效**；
- [ ] 用户令牌带前缀、可设置过期时间、可吊销；旧 `app_user.token_hash` 迁移为 `user_token` 后**旧令牌继续有效**（ADR §10）；
- [ ] 项目级服务账号 `viewer` / `editor` 可用，**不可登录 Web、不可跨项目访问**，吊销立即生效；
- [ ] 登录成功 / 失败、用户管理、令牌、服务账号、项目 / 团队 / binding 等管理动作写入 `audit_event`；`GET /api/audit` 仅 `org_admin` 可查；
- [ ] Spring Security 的默认安全头、会话固定防护、CSRF、401/403 错误契约与现有 `ApiErrorBody` / `ApiErrorCode` 对齐；
- [ ] `app_user.is_platform_admin` 与 `app_user.token_hash` 在阶段 C 内移除（V7），代码中无引用；
- [ ] 管理库迁移在 PostgreSQL 18.6 与 MySQL 8.4 上通过（V5→V6→V7、空库 V1→V7）；**会话不落管理库，C 阶段直接使用 Spring Session Data Redis**；
- [ ] **本地开发与生产使用同一套认证方案**：都通过真实 admin-token / 密码 / OIDC 换取 Spring Session，或使用 Bearer 服务账号；没有 `authEnabled=false`、dev 请求头、固定默认密码等旁路；
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
| C1 | 管理库 V6 / V7 迁移 | C0 | L | `V6__identity_and_audit.sql`、`V7__identity_tighten.sql`（PG/MySQL；会话走 Redis，不建管理库会话表） |
| C2 | Spring Security 认证底座与领域适配 | C1 | L | `SecurityConfig` 双链（Bearer API / Web Session）、Spring Session Data Redis、`AuthenticationManager`、opaque Bearer provider、`AuthService` / `AuthzService` 适配 |

> **C0 状态**：✅ 2026-11-19 已确认；**C1 状态**：✅ 2026-11-19 完成（V6 / V7 均已生效；V7 由 C4 同窗口切到 `target=7` 并删除旧列）；**C2 状态**：✅ 2026-11-19 完成（双链 / Session Redis / CSRF / 令牌 / 域授权；OIDC `oauth2Login` 留 C6）；**C3 状态**：✅ 2026-11-19 完成（密码登录 / 改密 / 邀请重置 / Redis 限流 / 会话属性；审计已随 C7 接入）；**C4 状态**：✅ 2026-11-20 完成（生命周期 / 会话管理 / 令牌管理 / V7；审计已随 C7 接入）；**C5 状态**：✅ 2026-11-20 完成（项目级服务账号 / 跨项目隔离 / MCP / CLI；审计已随 C7 接入）；**C6 状态**：✅ 2026-11-20 完成（OIDC oauth2Login / PKCE / 身份绑定 / auto-provision）；**C7 状态**：✅ 2026-11-20 完成（audit_event / AuditService / GET /api/audit / 保留清理；认证与管理动作写点接入）。

### 2.2 C‑2 登录与账号生命周期

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| C3 | 密码登录与会话端点 | C2 | M | JSON 登录 filter、`LogoutFilter`、`/me`、密码管理、CSRF、会话固定防护、登录限流 |
| C4 | 账号生命周期与令牌管理 | C2 / C3 | M | 禁用 / 启用 / 邀请 / 重置、会话列表与吊销、用户令牌 CRUD、V7 收紧 |

### 2.3 C‑3 机器身份与 SSO

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| C5 | 项目级服务账号 | C2 | M | `ServiceAccountAuthenticationProvider` / `ServiceAccountController`、跨项目隔离、MCP/CLI 文档 |
| C6 | OIDC 登录 | C3 | L | `oauth2Login`、`OidcUserService`、`user_identity` 绑定、自动建号开关、配置与测试 |

### 2.4 C‑4 审计、前端与门禁

| ID | 任务 | 依赖 | 规模 | 产物 |
|---|---|---|---|---|
| C7 | 管理动作审计 | C3 / C4 / C5 | M | `audit_event`、`AuditService`、`GET /api/audit`、关键写点接入、保留策略 |
| C8 | 前端账号与安全改造 | C3–C7 | M | 登录框、账号与安全、令牌、服务账号、审计页、去掉 localStorage 长期令牌 |
| C9 | 测试矩阵与双库验证 | C1–C8 | L | 迁移 / 会话 / 密码 / 禁用 / 服务账号 / OIDC / 审计测试、手动 e2e |
| C10 | 文档与收尾 | C9 | S | 开发计划、README、ADR、持久化选型、运维手册、UI 方案 |

> **C‑1 门禁**：C1 回填完成、C2 的 Web / Bearer 双链与真实登录测试全绿之前，不改 Web 登录形态。
> **C‑2 门禁**：禁用用户会话 / 令牌立即失效的集成测试全绿之前，不把「禁用账号」开放到 UI。
> **C‑3 门禁**：服务账号跨项目隔离、Bearer 链 CSRF 豁免测试全绿之前，不把 MCP / CLI 文档切到服务账号。
> **C‑4 门禁**：V7 删除旧列之前，代码中 `is_platform_admin` / `app_user.token_hash` 引用必须清零（grep 门禁）；CSRF / 安全头 / 401-403 契约测试必须全绿；所有环境真实登录回归必须全绿。

### 2.5 C 阶段 API 变更总表

| 方法 | 路径 | 说明 | 任务 |
|---|---|---|---|
| `POST` | `/api/auth/login` | 密码 / 旧 token 登录；由 Spring Security 登录 filter 创建 Spring Session 会话 | C3 |
| `POST` | `/api/auth/logout` | 由 Spring Security `LogoutFilter` 吊销会话、清 Cookie | C3 |
| `GET` | `/api/auth/me` | 增加 `authMethod` / `csrfToken` / `email` / `status` / `mustChangePassword` / `oidcEnabled` 等 | C3 |
| `POST` | `/api/auth/password` | 修改自己的密码；吊销其它会话 | C3 |
| `POST` | `/api/auth/password/setup` | 用一次性邀请 / 重置令牌设置密码 | C3 |
| `GET` / `DELETE` | `/api/auth/sessions`、`/api/auth/sessions/{id}` | 查看 / 吊销自己的登录会话（Spring Session） | C4 |
| `PATCH` | `/api/auth/users/{id}` | 改 `displayName` / `email`（org_admin） | C4 |
| `POST` | `/api/auth/users/{id}/disable` | 禁用账号；DB 事务吊销令牌，提交后删除 Redis 会话 | C4 |
| `POST` | `/api/auth/users/{id}/enable` | 启用账号；已吊销令牌不恢复 | C4 |
| `POST` | `/api/auth/users/{id}/password/reset` | 吊销会话 / 令牌并返回一次性重置令牌（只显示一次） | C4 |
| `GET` / `DELETE` | `/api/auth/users/{id}/sessions`、`.../sessions/{sessionId}` | org_admin 查看 / 吊销指定用户会话 | C4 |
| `GET` / `POST` | `/api/auth/tokens` | 列出 / 创建自己的 API 令牌 | C4 |
| `DELETE` | `/api/auth/tokens/{id}` | 吊销自己的令牌 | C4 |
| `GET` | `/api/auth/users/{id}/tokens` | org_admin 查看指定用户令牌（不含明文） | C4 |
| `DELETE` | `/api/auth/users/{id}/tokens/{tokenId}` | org_admin 吊销指定用户令牌 | C4 |
| `GET` / `POST` | `/api/service-accounts` | 项目级服务账号列表 / 创建（明文只返回一次） | C5 |
| `DELETE` | `/api/service-accounts/{id}` | 吊销服务账号 | C5 |
| `POST` | `/api/service-accounts/{id}/rotate` | 轮换密钥（吊销旧密钥、返回新密钥一次） | C5 |
| `GET` | `/api/audit` | 管理动作审计查询（org_admin） | C7 |
| `GET` | `/oauth2/authorization/spechub` | OIDC 授权入口（`oauth2Login` 提供） | C6 |
| `GET` | `/login/oauth2/code/spechub` | OIDC 回调（`oauth2Login` 提供） | C6 |

兼容保留：`POST /api/auth/users/{id}/token` 继续可用，语义改为「吊销旧令牌并签发一条新的用户 API 令牌」，响应仍返回 `token` 一次。

---

## 3. 任务详情

### C0 · 前置对齐与决策（S）

ADR 未写死、但会影响实现细节的项，在 C0 一次拍板并记录。下表已于 **2026-11-19 确认**，按此执行：

| 决策项 | 建议默认 | 理由 / 影响 |
|---|---|---|
| 认证架构 | **Spring Security 作为 Web 认证底座**：两条主链（Bearer API / Web Session），OIDC 用 `oauth2Login`；自定义 opaque Bearer `AuthenticationProvider`；域授权仍集中在 `AuthService` | SaaS 目标需要标准会话、OIDC、CSRF、安全头与 CVE 跟进；避免自研认证面继续扩大 |
| 会话存储 | **Spring Session Data Redis 在 dev 与 prod 都使用**；Redis 是 C 阶段必需组件，不做 JDBC 过渡；不建自研 `user_session` 表，也不建 `SPRING_SESSION` 表 | 一开始就按 SaaS 终态；本地与生产同一种会话行为；Redis 不可用时 fail-closed |
| Cookie | `HttpOnly; SameSite=Lax; Path=/`；prod 加 `Secure`；dev 可配 `secure=false`（仅为 HTTP/TLS 差异，SameSite / CSRF 与 prod 一致）；prod 可用 `__Host-` 前缀 | 防 XSS 窃取；Vite 代理同源下 Lax 可用 |
| 会话时效 | 空闲 24 小时（`spring.session.timeout`）+ 绝对 7 天（自定义 `AbsoluteSessionTimeoutFilter`）；登录轮换会话 ID | 兼顾安全与日常使用；Spring Security 负责会话固定防护 |
| CSRF | Spring Security `CsrfFilter` + `HttpSessionCsrfTokenRepository`；`/api/auth/me` 下发 `X-CSRF-Token`；Bearer API 链关闭 CSRF；新增 `AUTH_CSRF(403)` | 标准实现；CLI / MCP 不受影响 |
| 密码 | **Argon2id**（`Argon2PasswordEncoder` + `bcprov-jdk18on`）；长度 10–128，禁止等于用户名 / 邮箱 | 与 Spring Security 生态一致；满足 ADR §7.1 |
| 改密行为 | 删除其它 Web 会话，保留当前会话；API 令牌不自动吊销，由用户 / 管理员手动吊销 | 避免改密导致 CI / MCP 全部中断；D1 可加强制策略 |
| 登录标识 | `username` 或 `email` 二选一（忽略大小写）；失败统一 401「用户名或密码错误」 | 不暴露账号是否存在 |
| 登录链路 | `/api/auth/login` 用 JSON 登录 filter 包装 `AuthenticationManager`，复用 Spring Security 的 SecurityContext 持久化与 `SessionAuthenticationStrategy`；不启用默认 form login 页面 | 保留现有 JSON 契约，同时拿到会话固定防护 |
| 认证链 | 两条链：`BearerRequestMatcher` API 链（STATELESS、无 CSRF）+ Web 链（Session / OIDC / CSRF）；**不设 dev 放行链** | 避免 Bearer 与 Session 混用导致 CSRF / 会话策略冲突；dev 与 prod 行为一致 |
| 域授权 | 自定义 `AuthzService` / `AuthorizationManager` 调用 `AuthService`；**不把 org/project 角色放进 authorities** | 角色变更即时生效；域规则集中、可测试 |
| 会话吊销 | `FindByIndexNameSessionRepository` 按 principal 删除会话；`SessionRegistry` 列设备；禁用 / 改密主动删除 | 即时失效；支持设备列表与远程登出 |
| 错误契约 | 自定义 `AuthenticationEntryPoint` / `AccessDeniedHandler` 返回 `ApiErrorBody` / `ApiErrorCode` | 保持现有 401/403 JSON 契约 |
| 安全头 | 使用 Spring Security 默认 headers（HSTS / Cache-Control / X-Content-Type-Options 等） | 减少手写安全头遗漏 |
| 令牌格式 | 用户令牌 `shu_`、服务账号 `shs_`；只存 SHA-256；列表只显示前缀 | 便于 secret 扫描与运维识别 |
| 旧令牌迁移 | V6 把 `app_user.token_hash` 逐行复制为 `user_token(purpose=legacy)`；旧令牌继续有效；V7 删列 | 满足 ADR §10「旧 token 继续有效」；两阶段迁移可回滚 |
| `is_platform_admin` | C 内移除；平台 admin 由 `org_membership.org_role='org_admin'` 表达；`/api/auth/me` 删除 `platformAdmin` 字段 | 与 ADR §10 一致；前端改用 `orgAdmin` |
| 服务账号 | **仅项目级**（`project_id NOT NULL`）；角色仅 `viewer` / `editor`；默认过期 90 天、最长 365 天；支持轮换；不可登录 Web；自定义 opaque `AuthenticationProvider` | ADR §4.2 / §7.1；不走 JWT resource server |
| OIDC | `oauth2Login` + `OidcUserService`；单 IdP；Authorization Code + PKCE；已验证邮箱绑定；`auto-provision` 默认关闭；不做组映射 | 标准协议实现；多 IdP / SAML / SCIM 留后续 |
| 邮箱 | C 只存储与唯一校验，不发信；密码重置令牌由管理员一次性查看；邮件自助重置留 D2 | 避免在 D2 邮件基础设施前引入半成品 |
| 禁用语义 | `app_user.status=disabled` 为全局禁用；先完成 DB 事务（status + 吊销令牌），再按 principal 删除 Redis 会话；Redis 删除失败由每请求状态校验兜底为 401；启用不恢复旧凭据；`org_membership.status` 留 D1 | 满足阶段 C 验收；Redis 与会话删除不依赖跨存储事务 |
| 审计存储 | **新增 `audit_event` 表**；`changelog` 继续作为产品变更流；认证 / 用户 / 令牌 / 服务账号 / 组织级管理动作写审计，内容写操作仍只写 changelog；可监听 Spring Security 登录事件补充认证审计 | 不把安全审计混入产品变更流；与 ADR §7.1 衔接 |
| 审计保留 | 默认 365 天，每日清理；可配置 | 控制表增长；付费档保留期 D1 再定 |
| 登录限流 | **Redis** 固定窗口：同一 `identifier` 5 次失败 / 5 分钟，锁 15 分钟；同 IP 20 次 / 5 分钟；新增 `TOO_MANY_REQUESTS(429)`；dev/prod 同一实现 | Redis 已是 C 阶段必需组件；一开始就支持多实例，不做内存限流过渡 |
| 认证开关 | **所有环境始终启用鉴权**（`authEnabled` 恒 true）；`spechub.require-admin-token=true` 在所有 profile 生效；未配置 admin-token 启动失败；OIDC 是附加登录方式 | 本地与生产同一方案；dev 通过 `.env` 的 `SPECHUB_ADMIN_TOKEN` 完成首次 bootstrap |
| dev bootstrap | dev 的 `.env` 提供 dev 专用 `SPECHUB_ADMIN_TOKEN`；开发者用 `admin` + token 真实登录换 Spring Session，再设置密码 / 绑定 OIDC；之后用密码 / OIDC | 与生产 bootstrap 完全一致；admin-token 只做 bootstrap / break-glass，不是免登录 |
| 会话存储一致性 | dev 与 prod 都使用 Spring Session Data Redis，同一 namespace / TTL / Cookie 配置，只换 Redis 连接串 | 不引入“本地 JDBC、线上 Redis”的过渡分叉；D1 增租户维度时仍沿用同一实现 |
| `admin-token` 登录 | 继续可用；成功登录同样创建 Spring Security 会话，Web 不再保存 token | 兼容旧部署，同时满足「Web 不落 localStorage」 |
| 测试 / 兼容 | `spring-security-test` + 真实 HTTP Cookie / Bearer；保留 `/api/auth/login` 接口形态；CLI / MCP 继续 Bearer | 降低框架配置回归风险；客户端不中断 |
| 迁移节奏 | V6 加列 / 建表（身份 / 令牌 / 服务账号 / 审计）→ 分片切 Spring Security（Web Session → OIDC → Bearer provider → 域授权）→ V7 删旧列；Redis 会话不参与管理库迁移 | 每片可独立验收，避免 big-bang |
| Jackson 2 | OIDC / Security 传递的 Jackson 2 只允许运行期库内部使用；`verifyNoJackson2Usage` + 依赖树核对 | 遵守 CLAUDE.md 边界 |

- [x] 输出决策记录（写入本文档 §6 与 ADR §13 开放问题）。
- [x] 确认 V6 / V7 版本号、Spring Session Data Redis 会话方案与两阶段迁移门禁。
- [x] 确认 Spring Security 双链设计（Bearer API / Web Session）与全环境真实登录 bootstrap。
- [x] 确认 C 阶段直接使用 Spring Session Data Redis 与 Redis 登录限流，不做 JDBC / 内存过渡。
- [x] 确认「最后一名 `org_admin` 不可禁用 / 降级 / 删除」保护（与团队最后一个 lead 保护同风格）。

> **C0 确认结论（2026-11-19）**
>
> - Spring Security 双链（Web Session / Bearer API）+ Spring Session Data Redis；不设 dev 放行链，所有环境真实登录。
> - Redis 会话与 Redis 登录限流从 C 阶段直接使用，不做 JDBC / 内存过渡。
> - Argon2id；旧 token 回填 `user_token` 后继续有效；V7 删 `token_hash` / `is_platform_admin`。
> - 项目级服务账号（viewer / editor）、OIDC `oauth2Login`、独立 `audit_event` 审计表。
> - `admin-token` 仅用于 bootstrap / break-glass；最后一名可登录 `org_admin` 保护。

**状态**：✅ 2026-11-19 已确认（C0 完成；C1 起待启动）。

---

### C1 · 管理库 V6 / V7 迁移（L）

迁移文件：

- `modules/server/src/main/resources/db/migration/postgresql/V6__identity_and_audit.sql`
- `modules/server/src/main/resources/db/migration/mysql/V6__identity_and_audit.sql`
- `modules/server/src/main/resources/db/migration/postgresql/V7__identity_tighten.sql`
- `modules/server/src/main/resources/db/migration/mysql/V7__identity_tighten.sql`

> 与 A / B 阶段纪律一致：V6 只加列 / 建表 / 回填，保证旧代码仍可启动；代码切换完成后由 V7 删除旧列。迁移不删除任何业务数据。

**V6 · `app_user` 扩展**

- [x] 新增列（示意）：
  ```sql
  ALTER TABLE app_user ADD COLUMN email VARCHAR(255);
  ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(255);
  ALTER TABLE app_user ADD COLUMN password_updated_at TIMESTAMPTZ;
  ALTER TABLE app_user ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'active';
  ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
  ALTER TABLE app_user ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
  ALTER TABLE app_user ADD COLUMN last_login_at TIMESTAMPTZ;
  ```
- [x] `status ∈ active | disabled | invited`（CHECK）；现有用户全部回填 `active`。
- [x] `app_user.token_hash` 改为可空（PostgreSQL `DROP NOT NULL`；MySQL `MODIFY token_hash VARCHAR(128) NULL`）：C2 代码停止写该列后仍能创建新用户；V7 再删列。
- [x] 迁移前检查 `app_user.token_hash` 是否有重复：理论上不应出现，若有则**中止迁移并要求人工处理**（避免 `user_token` 唯一键静默丢凭据）。PG 用 `DO` + `RAISE`；MySQL 用临时表 + `CHECK` 约束（`chk_v6_no_duplicate_token_hash`）。
- [x] 邮箱唯一、忽略大小写：
  - PostgreSQL：`CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user (lower(email)) WHERE email IS NOT NULL;`
  - MySQL：函数索引 `CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user ((LOWER(email)));`
  - Service 层写入前统一 `trim().lowercase()`；邮箱为空时允许 NULL。
- [x] `mfa_enabled` 只预留列，C 阶段不实现 MFA 校验；文档标注。

**V6 · `user_token`（用户 API 令牌）**

- [x] 新建表（示意）：
  ```sql
  user_token(id, user_id, name, token_prefix, token_hash, purpose,
             expires_at, used_at, last_used_at, revoked_at, created_at)
  -- UNIQUE (token_hash)
  -- INDEX (user_id, created_at)
  -- FK user_id → app_user(id) ON DELETE CASCADE
  -- purpose ∈ legacy | api | invite | password_reset
  -- CHECK (purpose = 'legacy' OR expires_at IS NOT NULL)
  ```
- [x] 回填现有令牌：
  ```sql
  INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose, created_at)
  SELECT id, 'legacy', '', token_hash, 'legacy', created_at FROM app_user;
  ```
- [x] `legacy` 行不设过期（ADR §10 要求旧 token 继续有效）；`api` / `invite` / `password_reset` 行必须有过期时间（DB CHECK 强制）。
- [x] `invite` / `password_reset` 使用后写 `used_at`，不可复用（C3 `/api/auth/password/setup` 已实现）。

**会话存储 · Spring Session Data Redis（V6 不建会话表）**

> **C1 边界**：本任务只保证迁移不建 `SPRING_SESSION` / `user_session` 表，并由迁移测试断言；Spring Session / Redis 依赖与配置在 C2 引入。以下条目随 C2 勾选。

- [ ] 引入 `org.springframework.session:spring-session-data-redis`（以及 Boot 的 Redis 连接支持）；**不引入 `spring-session-jdbc`**，不在管理库建 `SPRING_SESSION` / `user_session` 表。
- [ ] 配置 `spring.session.store-type=redis`、`spring.session.redis.namespace=spechub:session`、`spring.session.timeout=24h`；绝对 7 天由 C2 的 `AbsoluteSessionTimeoutFilter` 负责。
- [ ] dev 与 prod 都使用 Spring Session Data Redis：同一 namespace / TTL / Cookie 配置，只换 Redis 连接串；dev compose 也起 Redis，不存在 JDBC 过渡。
- [ ] 会话列表 / 吊销通过 `FindByIndexNameSessionRepository` 与 `SessionRegistry` 完成；`PRINCIPAL_NAME_INDEX_NAME` 由登录策略写入 Redis 索引。
- [ ] 清理：Redis TTL + Spring Session Redis 的过期清理（`spring.session.redis.cleanup-cron`）；不写管理库清理任务。
- [ ] Redis 不可用时鉴权 fail-closed（返回 503 / 无法建立会话），不做降级到匿名或本地内存会话。
- [ ] 会话数据不纳入管理库备份；Redis 丢失时用户重新登录，不丢管理数据。

**V6 · `service_account`（项目级服务账号）**

- [x] 新建表（示意）：
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
- [x] `project_id NOT NULL`，`org_id` 冗余（DB 约束已落库）；Service 创建时校验 `project.orgId == org_id`（C5 实现）。
- [ ] 项目删除时由 `ProjectService.delete` 显式清理服务账号（保持现有 FK RESTRICT 纪律；C5 实现）。

**V6 · `user_identity`（OIDC 身份绑定）**

- [x] 新建表（示意）：
  ```sql
  user_identity(id, user_id, provider, subject, email, created_at)
  -- UNIQUE (provider, subject)
  -- INDEX (user_id), INDEX (email)
  -- FK user_id → app_user(id) ON DELETE CASCADE
  ```
- [x] `provider` 为 issuer 的稳定标识（如 `oidc:<issuer>` 或 registration id）；`subject` 为 `sub`（MySQL 用 `utf8mb4_bin` 保证大小写敏感）。

**V6 · `audit_event`（管理动作审计）**

- [x] 新建表（示意）：
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
- [x] `org_id` / `actor_user_id` **不建外键**：审计需在用户 / 组织删除后保留；`actor_name` 存快照。
- [ ] `detail` 只存可审计摘要，禁止密码、令牌、Cookie、spec 正文（C7 服务层约束）。

**V7 · 收紧与删列（代码切换后）**

- [x] `DROP COLUMN app_user.token_hash`（已回填 `user_token`）。
- [x] `DROP COLUMN app_user.is_platform_admin`（已由 `org_membership.org_role` 取代）。
- [x] 校验：每个 `app_user.token_hash` 均已落 `user_token`（C4 对轮换后缺失的旧值补记为 revoked legacy；补记后仍缺才中止）；
- [x] 校验：`app_user.status` 无 NULL；邮箱无重复（忽略大小写，由 V6 唯一索引兜底）。
- [x] 迁移后启动旧代码会失败，属预期；V7 必须与 C2–C4 代码同一发布窗口。

> **C1 运行期策略（已完成）**：V6 随 C1 生效；C4 已把 `spring.flyway.target` 切到 7，V7 与代码同一发布窗口执行；`ManagementDbMigrationTest` 已覆盖 V6→V7 与空库 V1→V7。

**兼容与纪律**

- [x] 分 vendor 维护，不写跨方言 SQL；MySQL 使用 `DATETIME(6)`、`CHECK`、函数索引。
- [x] 不删除任何业务数据；V7 只删已迁移的凭据 / 兼容列。
- [x] `ManagementDbMigrationTest` 增加 V5→V6→V7 分段用例与空库 V1→V7；**不涉及会话表**（会话在 Redis，由 C9 的 Redis 集成测试覆盖）。
- [x] 迁移后校验：现有 admin 用户在 `org_membership` 中为 `org_admin`（V3 已回填，C1 再断言一次）。

**验收**

- [x] PostgreSQL 18.6：V5 有用户 / token / 团队数据 → V6，旧 token 回填为 `legacy`，用户 `status=active`。
- [x] MySQL 8.4：同上。
- [x] V6 → V7：旧列删除；用迁移前的旧 token 仍可通过 `user_token` 认证（C1 验证 legacy 行保留且可按 hash 定位；Bearer 认证链路在 C2 接入）。
- [x] 空库 V1 → V7 一次跑通。
- [x] 双库全量 `:server:test` 与 `:server:ktlintCheck` 通过（V6 不破坏旧写入路径；V7 已在 C4 运行期放开并验证）。

**状态**：✅ 2026-11-19 完成。V6 运行期生效；V7 文件与测试就绪，运行期由 `spring.flyway.target=6` 暂缓，C4 与代码切换同窗口改为 7。

---

### C2 · Spring Security 认证底座与领域适配（L）

**依赖**

- [x] 引入 `spring-boot-starter-security`、`spring-boot-starter-data-redis`、**`spring-boot-starter-session-data-redis`**（Boot 4 必须用 starter；裸 `spring-session-data-redis` 不触发自动配置）、`spring-boot-starter-oauth2-client`、`org.bouncycastle:bcprov-jdk18on`（Argon2id，版本 1.87）；测试用 `spring-security-test`；版本由 Boot 4 BOM 管理（Spring Security 7）。
- [x] 依赖审计：OIDC / Security 传递的 Jackson 2 只允许运行期库内部使用；`verifyNoJackson2Usage` + 依赖树核对。
- [x] 不引入默认 form login 页面；`/api/auth/login` 由自定义 JSON 登录 filter 接管。

**Repository / Entity**

- [x] `UserEntity` 扩展：`email` / `passwordHash` / `passwordUpdatedAt` / `status` / `mustChangePassword` / `mfaEnabled` / `lastLoginAt`；移除 `tokenHash` / `isPlatformAdmin`（V7 后）。
- [x] `UserRepository`：`findByEmail`（忽略大小写）、`findAllOrdered` 改按 `id` 排序、保留 `findByUsername`。
- [x] 新增 `UserTokenEntity` / `UserTokenRepository`：
  - `findByTokenHash`、`findAllByUserIdOrderByCreatedAtDesc`、`findByIdAndUserId`、`deleteAllByUserId`。
- [x] 新增 `ServiceAccountEntity` / `ServiceAccountRepository`：
  - `findByTokenHash`、`findAllByProjectIdOrderByNameAsc`、`findByIdAndProjectId`、`deleteAllByProjectId`。
- [x] 新增 `UserIdentityEntity` / `UserIdentityRepository`：
  - `findByProviderAndSubject`、`findAllByUserId`。
- [x] `UserTokenService`：`issue` / `resolve` / `list` / `revoke` / `rotate`；`last_used_at` 5 分钟节流；V6 已把旧 `app_user.token_hash` 回填为 `user_token(purpose=legacy)`，只查 `user_token`，不做双读。
- [x] `UserRecord`（`kernel:spec-index`）同步字段：`email` / `status` / `hasPassword`；移除 `tokenHash` / `isPlatformAdmin`，测试中的构造 / 工厂同步改造。
- [x] **不新增 `UserSessionEntity`**：Web 会话由 Spring Session 的 `FindByIndexNameSessionRepository` / `SessionRegistry` 管理。

**Spring Security 双链**

- [x] `SecurityConfig` 定义两条 `SecurityFilterChain`（按 `@Order`）：
  1. **Bearer API 链**：`securityMatcher(BearerRequestMatcher)`（存在 `Authorization: Bearer`）+ `STATELESS` + `csrf.disable()` + `requestCache.disable()`；挂 `BearerAuthenticationFilter`；默认拒绝；自定义 entry point；
  2. **Web 链**：其余请求；Spring Session 会话；`csrf` 开启（`HttpSessionCsrfTokenRepository`）；JSON 密码登录 filter；`oauth2Login`；默认拒绝；自定义 entry point / denied handler。
- [x] **不注册 dev 放行链**；`authEnabled` 恒 true，所有 profile 都必须真实登录。删除 `AuthService` / Controller 里所有 `if (!authEnabled)` 早退分支。
- [x] `BearerRequestMatcher` 只匹配带 Bearer 头的请求；`/api/auth/login` 等无 Bearer 请求走 Web 链。
- [x] Bearer API 链放行清单：`/api/auth/status`、`/actuator/**`、`/mock/**`；其余 `/api/**` 需认证；默认拒绝。`/mock/**` 的 Bearer 请求仍会先解析服务账号，供 MockController 判定项目归属。
- [x] Web 链放行清单：`/api/auth/status`、`/api/auth/login`、`/api/auth/logout`、`/api/auth/password/setup`、`/oauth2/**`、`/login/oauth2/**`、`/actuator/**`、`/mock/**`、`/error`、`openapiPublic=true` 时的文档端点；其余默认拒绝。
- [x] 自定义 `ApiAuthenticationEntryPoint` / `ApiAccessDeniedHandler` 返回 `ApiErrorBody` / `ApiErrorCode`；不改变现有 401/403 语义。
- [x] `headers` 使用 Spring Security 默认值（HSTS / `X-Content-Type-Options` / `X-Frame-Options` / `Cache-Control`）；按需补 CSP。
- [x] 显式声明 `SecurityFilterChain` bean，避免 Boot 默认安全链意外拦截；禁止宽泛 `permitAll` 前缀。

**认证组件**

- [x] `AppUserDetailsService`：按 username / email 查 `app_user`；`UserDetails` 暴露 id / username / status；`isEnabled` 对应 `status ∈ active|invited`。
- [x] `PasswordEncoder`：`Argon2PasswordEncoder`；登录成功时按当前参数升级旧哈希（`UserDetailsPasswordService` 或自定义）。
- [x] `AuthenticationManager`：`DaoAuthenticationProvider` + `AppUserDetailsService`；失败统一 `BadCredentialsException`。
- [x] `JsonUsernamePasswordAuthenticationFilter`（继承 `AbstractAuthenticationProcessingFilter`）：
  - 读取 JSON body，支持 `username|email + password` 或 `username + token`；
  - `password` 路径调用 `AuthenticationManager`；`token` 路径调用自定义 `TokenAuthenticationProvider`；
  - 成功后复用 Spring Security 的 `SecurityContextRepository` 与 `SessionAuthenticationStrategy`（会话固定防护）；
  - 成功 / 失败 handler 返回现有 JSON 契约；不启用默认 form login。
- [x] `BearerAuthenticationFilter` + `TokenAuthenticationProvider`：
  - 解析 `Authorization: Bearer`；先按 SHA-256 查 `user_token`（`shu_` / legacy / admin-token），未命中再查 `service_account`（`shs_`）；前缀只用于展示 / 基本校验，不作为唯一分流依据；
  - 校验 `revoked_at` / `expires_at` / 用户 `status`；
  - 写 `SecurityContext`；`last_used_at` 写节流（5 分钟）；
  - 无效 / 过期令牌由 entry point 返回 401。
- [x] `AppUserPrincipal` / `ServicePrincipal`：实现 `Serializable`，只带 id / username / displayName / orgId / projectId / role 等最小字段；**不把 `UserRecord`、`UserEntity` 或 `DefaultOidcUser` 直接写入 Spring Session**。
- [x] `Authentication.getName()` 统一返回 `username`（OIDC principal 也覆盖为本地 username），保证 Spring Session 的 `PRINCIPAL_NAME` 索引可用于按用户列 / 删会话。
- [ ] `OidcUserService`（C6 落地）：调用 `OidcLoginService` 解析 / 绑定 / 自动建号，返回带 `appUserId`、且 `getName()` 为本地 username 的可序列化 principal。

**会话与 CSRF**

- [x] Spring Session Data Redis 配置（Boot 4 属性）：`spring.session.data.redis.repository-type=indexed`、`spring.session.data.redis.namespace=spechub:session`、`spring.session.timeout=24h`；Redis 连接通过 `SPECHUB_REDIS_URL`（`spring.data.redis.url`）注入，不建管理库会话表。
- [x] `CookieSerializer` bean：Cookie 名 `spechub_session`、`HttpOnly`、`SameSite=Lax`、`Secure` 由配置、`Path=/`；prod 可用 `__Host-` 前缀。
- [x] `SessionAuthenticationStrategy`：使用 Spring Security 默认 `changeSessionId`（会话固定防护）；登录成功必须调用。
- [x] `AbsoluteSessionTimeoutFilter`：读取 Spring Session `creationTime`，超过 7 天删除会话、清 Cookie、返回 401。
- [x] `SessionRegistry`：`SpringSessionBackedSessionRegistry`（或直接 `FindByIndexNameSessionRepository.findByPrincipalName`）；用于设备列表与禁用 / 改密时批量吊销。
- [x] 登录成功后确保 Spring Session 写入 `PRINCIPAL_NAME_INDEX_NAME`（`RegisterSessionAuthenticationStrategy` 或显式 `setAttribute`）；否则 `findByPrincipalName` 查不到会话，禁用 / 设备列表会漏。
- [x] CSRF：`CsrfFilter` + `HttpSessionCsrfTokenRepository` + header `X-CSRF-Token`；`/api/auth/me` 从 request attribute 读取 `CsrfToken` 返回；登录 / logout / setup / OIDC 回调 / actuator / mock 豁免；Bearer API 链整体关闭。
- [x] CSRF token handler 统一：`/me` 下发的 token 与 `CsrfFilter` 校验使用同一个 `CsrfTokenRequestHandler`；若采用 `SpaCsrfTokenRequestHandler` 的 masked token，前端必须原样回传，禁止混用明文 / 掩码两套 token。
- [x] 会话清理：Redis TTL + Spring Session Redis `spring.session.data.redis.cleanup-cron`；不写管理库清理任务，也不保留 JDBC 实现。

**`AuthService` / `AuthzService` 适配**

- [x] `AuthService` 从 `SecurityContextHolder` 读取 `Authentication`；`currentUser` / `currentActor` / `currentServiceAccount` 不再依赖 request attribute（request 参数保留兼容）。
- [x] 每次请求校验当前用户 `status`（Session / Bearer 均适用）：`disabled` 立即 401；作为「禁用即时生效」的纵深防御（会话批量删除在 C4 的禁用 / 改密流程补齐）。
- [x] `currentUser` 支持 `AppUserPrincipal` / `ServicePrincipal`：ServicePrincipal 返回 null（由 `requireHuman` 拒绝）；OIDC principal 的 `appUserId` 回查在 C6 接入。
- [x] `currentOrg` 从 principal 解析：服务账号用 `account.orgId`；人类用户用 `org_membership` 的第一个 active org；不再依赖 `REQ_ORG` request attribute。
- [x] 删除 `authEnabled=false` 语义：恒为 true；`SpecHubProperties.requireAdminToken` 默认改为 true（dev profile 不再覆盖为 false），空白 / 缺失 admin-token 启动失败；API 响应中的 `authEnabled` 保留为恒 true 的兼容字段。
- [x] `orgRole` / `isOrgAdmin` 去掉 `isPlatformAdmin` 特判，统一走 `org_membership`。
- [x] `effectiveRole` / `roleForRepo`：`ServicePrincipal` 仅在其 `projectId` 返回角色；`AppUserPrincipal` 沿用项目 membership + `org_admin` 恒 owner。
- [x] `canReadProject` / `visibleProjects` / `visibleProjectIds` / `visibleRepoIds` / `memberProjectIds` / `memberRepoIds` 改用 `SecurityContext` principal；服务账号跨项目集合为空，禁止退化全量。
- [x] `requireAuthenticated` 接受人 / 服务账号；新增 `requireHuman` 供 OIDC / 密码 / 团队管理 / 评论 / 订阅等仅人类操作使用。
- [x] `requireOrgAdmin` 拒绝服务账号；`requireTeam*` 仅人类。
- [x] `requireProjectVisible` / `requireRepoVisible` 对服务账号跨项目统一 404。
- [x] 新增 `AuthzService` bean：`isOrgAdmin`、`hasProjectRole(projectId, role)`、`canReadProject(projectId, stage)`；内部委托 `AuthService`，供 `AuthorizationManager` / `@PreAuthorize` 使用。
- [x] 如使用 `@PreAuthorize`，显式加 `@EnableMethodSecurity`；否则只用 URL `AuthorizationManager`，避免两套入口并存。
- [x] URL 级 `authorizeHttpRequests` 只做粗粒度默认拒绝；org / project / team 规则仍由 `AuthService` / `AuthzService` 集中判定。
- [x] 评审：服务账号可读所属项目评审、可提交写回评审（actor=`service:<name>`），但**不能审批 / 打回**（`ReviewController` 使用 `requireHuman`）。

**调用点修正（`isPlatformAdmin` 清零）**

- [x] `SpecWriteService.requireOtherReviewer`：平台 admin 自批改为 `org_admin` 自批（按 C0 决策）。
- [x] `ReviewController`：提交评审使用 principal actor；审批 / 打回使用 `requireHuman` + `isOrgAdmin`。
- [x] `CommentController`：全部评论端点使用 `requireHuman`；删除评论的 `isPlatformAdmin` 改为 `isOrgAdmin`。
- [x] `SubscriptionController`：订阅 / 取消 / 通知查询使用 `requireHuman`；服务账号不可订阅。
- [x] `TeamController`：团队与成员端点使用 `requireHuman`；服务账号不可管理或查看团队目录。
- [x] `ReleaseController.globalChangelog` / `ApiQueryController`：从 `SecurityContext` 取 actor，支持服务账号。
- [x] `ApplicationInit`：bootstrap admin 只确保 `org_membership.org_role='org_admin'`；启动时刷新 `user_token` 中 admin 的 `legacy` 行（admin-token 变化时旧值失效）；**不覆盖 `status` / `password_hash`**，被禁用的 admin 重启后仍保持禁用。
- [x] 全仓 grep `isPlatformAdmin` / `platformAdmin` / `app_user.token_hash` 业务代码引用清零（`user_token` / `service_account` 自身的 `token_hash` 字段除外；V7 门禁）。

**纪律**

- [x] 认证只由 Spring Security filter chain 完成；Controller 不自行读 Cookie / Bearer。
- [x] 域授权只通过 `AuthService` / `AuthzService`；禁止把 org / project 角色写进 `Authentication.authorities`，避免角色变更后旧会话权限不更新。
- [x] Principal 必须可序列化且最小化；Spring Session 默认 Java 序列化，禁止把实体 / `OidcUser` 直接写入会话。
- [x] 自定义 entry point / denied handler 保持 `ApiErrorBody` / `ApiErrorCode` 契约。
- [x] 密码、令牌、Cookie 不写日志；认证事件只记 username / principal / IP / 时间。
- [ ] 禁用 / 删除用户时：先完成 DB 事务（status / 令牌 / 关系），再按 principal 删除 Redis 会话；**不以跨存储事务为前提**，删除失败由每请求状态校验兜底（C4 生命周期端点实现；C2 已具备每请求状态校验）。

**测试**

- [x] `SecurityConfigTest`：Bearer 链 vs Web 链选择、CSRF 对 Bearer 豁免、所有 profile 默认拒绝、entry point / denied handler 返回错误契约。
- [x] `AuthPrincipalTest`：人 / 服务账号 principal、禁用 401、过期 401、跨项目不可见。
- [x] `UserTokenServiceTest`：签发 / 过期 / 吊销 / legacy 行解析 / 节流。
- [x] `SessionRegistryTest` / `AbsoluteSessionTimeoutFilterTest`：按用户列会话、删除会话、绝对超时。
- [x] 现有 `AuthApiTest` / `AuthVisibilityTest` / `ReadIsolationTest` 适配 Spring Security 上下文与 `UserRecord`。
- [x] 双库全量 `:server:test` 与 `:server:ktlintCheck` 通过。

**状态**：✅ 2026-11-19 完成（C2 认证底座；OIDC `oauth2Login` 留 C6）。

---

### C3 · 密码登录与会话端点（M）

**登录（Spring Security JSON filter）**

- [x] `JsonUsernamePasswordAuthenticationFilter` 接管 `POST /api/auth/login`，请求体兼容两种形态：
  ```json
  { "username": "alice", "password": "..." }
  { "username": "alice", "token": "shu_..." }
  ```
  - `username` 也可传邮箱；忽略大小写；`password` 与 `token` 互斥，同时传返回 400。
  - `password` 路径：`AuthenticationManager.authenticate(UsernamePasswordAuthenticationToken(identifier, password))` → `DaoAuthenticationProvider` + `AppUserDetailsService` + `Argon2PasswordEncoder`；检查 `status ∈ active|invited`。
  - `token` 路径：自定义 `TokenAuthenticationProvider` 解析 `user_token`（含 legacy / admin-token）→ 检查 `status ∈ active|invited`；`invited` 仅表示尚未设置密码，不阻断已发放的令牌。
  - 成功：由 Spring Security 把 `SecurityContext` 写入 Spring Session（`SecurityContextRepository`），并调用 `SessionAuthenticationStrategy`（`changeSessionId`）完成会话固定防护；Cookie 由 Spring Session 的 `CookieSerializer` 写出。
  - 成功响应：`authMethod` / `csrfToken` / `mustChangePassword`；不返回明文令牌。
  - 失败统一 401「用户名或密码错误」；禁用账号同样 401（不泄露状态）。
  - 限流命中返回 429，带 `Retry-After`；新增 `ApiErrorCode.TOO_MANY_REQUESTS(429)`。
  - **不再有 `AUTH_NOT_ENABLED` / 免登录分支**：所有环境都必须登录；dev 用 `.env` 的 `SPECHUB_ADMIN_TOKEN` 首次登录，建立真实 Spring Session，再设置密码 / 绑定 OIDC。
- [x] 首次 bootstrap 流程：`admin` + admin-token → 真实会话 → `/api/auth/password` 设置密码或 OIDC 绑定；之后用密码 / OIDC；admin-token 只做 bootstrap / break-glass。
- [x] 登录成功写审计 `auth.login.success`、更新 `last_login_at`；失败写 `auth.login.failure`；限流写 `auth.login.locked`（C7 已接入）。
- [x] 登录成功把 IP / User-Agent / 登录时间写入 Spring Session 属性，供活动会话列表显示；不在会话中存密码 / 令牌。
- [x] 不在登录响应 / 日志中回显 token、密码、Cookie。

**登出**

- [x] 配置 Spring Security `LogoutFilter` 处理 `POST /api/auth/logout`：
  - `SecurityContextLogoutHandler` + `CookieClearingLogoutHandler`；
  - Spring Session 删除当前会话；
  - 成功 handler 返回 204 JSON；未登录也返回 204（幂等）；`/api/auth/logout` 豁免 CSRF，避免无会话时 403。
  - [x] 审计 `auth.logout`（C7 已接入）。

**当前用户**

- [x] `GET /api/auth/me` 从 `SecurityContext` 读取 `Authentication`，扩展：
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
  - `csrfToken` 从 Spring Security `CsrfToken` request attribute 返回；Bearer 认证返回 `null`。
- [x] `GET /api/auth/status` 增加 `authMethod` / `oidcEnabled` / `oidcLoginUrl`，供登录框探测。

**密码管理**

- [x] `POST /api/auth/password`：
  - 请求 `{currentPassword?, newPassword}`；
  - 已有密码时必须校验 `currentPassword`；`mustChangePassword=true` 或 `hasPassword=false` 时允许不带；
  - 更新 `password_hash` / `password_updated_at`，清 `must_change_password`；
  - 通过 `FindByIndexNameSessionRepository.findByPrincipalName(username)` 删除该用户**其它**会话（保留当前会话）；API 令牌暂不吊销（C0 决策）；
  - [x] 写审计 `auth.password.change`（C7 已接入）。
- [x] `POST /api/auth/password/setup`：
  - 请求 `{token, newPassword}`；Web 链放行（无会话）；
  - 令牌 purpose 必须为 `invite` / `password_reset`，未过期、未使用、用户未禁用；
  - 设置密码、`status=active`、清 `must_change_password`、写 `used_at`；
  - 返回 200（不自动登录，前端引导登录）；[x] 写审计 `auth.password.setup`（C7 已接入）。
- [x] 密码策略校验：长度 10–128；不得等于用户名 / 邮箱；空白字符串拒绝；错误返回 400 + `fieldErrors`。

**会话与 CSRF**

- [x] Spring Session Data Redis 配置：`spring.session.timeout=24h`（空闲）；`AbsoluteSessionTimeoutFilter` 校验绝对 7 天；Cookie 名 `spechub_session`，`HttpOnly` / `SameSite=Lax` / `Path=/`，prod `Secure`，dev 可关；prod 可用 `__Host-` 前缀。prod 使用 HTTP 部署时必须显式关闭 `cookie-secure` 或配置 TLS，文档标注。
- [x] 会话固定防护由 Spring Security `SessionAuthenticationStrategy` 完成，不手写会话轮换。
- [x] CSRF：`CsrfFilter` + `HttpSessionCsrfTokenRepository`；登录与 `/api/auth/me` 下发 `X-CSRF-Token`；非安全方法缺失 / 错误返回 403 + `ApiErrorCode.AUTH_CSRF`；Bearer API 链整体关闭 CSRF。
- [x] 会话列表 / 吊销端点由 C4 提供；本任务只保证 Spring Session 索引可用。

**限流**

- [x] `RedisLoginRateLimiter`（C 阶段直接使用 Redis，不做内存过渡）：
  - key `spechub:ratelimit:login:identifier:<lower(username|email)>` 与 `...:ip:<remoteAddr>`；
  - 失败窗口 5 分钟 / 5 次；锁 15 分钟；成功清零；
  - 使用 Redis `INCR` + `PEXPIRE`（或 Lua 原子脚本）；TTL 自动清理；
  - Redis 不可用时 fail-closed（登录返回 503），不回退到内存限流。

> **C3 实施说明（2026-11-19）**：Boot 4 的会话属性为 `spring.session.data.redis.*`；登录成功已写入 IP / User-Agent / 登录时间 / 认证方式到 Spring Session（C4 活动会话列表读取）；登录限流 Redis fail-closed；审计写点统一在 C7 接入，不阻塞 C3 功能验收。

**测试**

- [x] `PasswordLoginApiTest`：JSON 登录 filter、密码 / 邮箱登录、错误密码 401、未知用户 401、禁用 401、限流 429、成功后 `/me` 带 CSRF。
- [x] `SessionApiTest`：Spring Session Data Redis Cookie 认证、登录会话 ID 变化（固定防护）、登出后 401、空闲 / 绝对过期、CSRF 缺失 / 错误 403、Bearer 豁免 CSRF。
- [x] `RedisLoginRateLimiterTest`：窗口 / 锁定 / 清零 / Redis 不可用 fail-closed。
- [x] `PasswordChangeApiTest`：改密校验、改密删除其它会话、邀请 / 重置令牌设置密码、令牌单次使用、密码策略。
- [x] `AuthApiTest` 旧 token 登录仍可用且现在建立 Spring Security 会话；`ReadIsolationTest` / `WebApiTest` 适配 Session / Bearer 双链。
- [x] 双库全量测试、Redis 会话 / 限流集成测试与 ktlint 通过。

**状态**：✅ 2026-11-19 完成（审计已随 C7 接入）。

---

### C4 · 账号生命周期与令牌管理（M）

**用户管理（org_admin）**

- [x] `POST /api/auth/users` 扩展：
  - 请求增加 `email?` / `password?`；
  - 无 `password` → `status=invited`、`mustChangePassword=true`，生成一次性 `invite` 令牌（7 天）并在响应 `setupToken` 返回一次；仍返回一条 `api` 令牌 `token` 兼容旧客户端（`invited` 状态不阻断已发放令牌）；
  - 有 `password` → `status=active`、`must_change_password=true`；
  - 邮箱唯一冲突 409；写入审计 `user.create`。
- [x] `PATCH /api/auth/users/{id}`：改 `displayName` / `email`；邮箱归一化与唯一校验；审计 `user.update`。
- [x] `UserView` 扩展 `email` / `status` / `mustChangePassword`，移除 `platformAdmin`；列表与创建响应同步（前端 C8 适配）。
- [x] `POST /api/auth/users/{id}/disable`：
  - 禁止禁用自己；禁止禁用最后一名可登录的 `org_admin`（`app_user.status ∈ active|invited`、`org_membership.status='active'`、`org_role=org_admin`）；
  - DB 事务：`status=disabled`、吊销全部 `user_token`；
  - DB 事务提交后，按 principal 删除全部 Redis 会话（`FindByIndexNameSessionRepository.findByPrincipalName`）；删除失败不影响禁用生效，下一次请求由状态校验返回 401；
  - 审计 `user.disable`；changelog `user.disable`（产品流保留）。
- [x] `POST /api/auth/users/{id}/enable`：`status=active`；不恢复已删除会话 / 已吊销令牌；审计 `user.enable`。
- [x] `POST /api/auth/users/{id}/password/reset`：
  - DB 事务提交后删除该用户全部 Redis 会话并吊销全部令牌；会话删除失败由状态校验兜底；
  - 设 `must_change_password=true`；
  - 生成 24h 一次性 `password_reset` 令牌，响应 `setupToken` 只显示一次；
  - 审计 `user.password.reset`。
- [x] `DELETE /api/auth/users/{id}` 扩展：
  - 禁止删除自己；禁止删除最后一名可登录的 `org_admin`；
  - DB 事务内删除 `user_token` / `user_identity` / `team_member` / `org_membership` / `membership`，再删用户；提交后删除该用户全部 Redis 会话；
  - 审计 `user.delete`；`audit_event` 保留。
- [x] `AuthController` 用户管理端点移除 `isPlatformAdmin` 特判（改角色 / 删除），统一改为「最后一名可登录 `org_admin`」保护；bootstrap admin 不再特殊。
- [x] `POST /api/auth/users/{id}/role`：改组织角色；禁止把最后一名可登录的 `org_admin` 降为 `org_member`；审计 `user.role`。

**会话管理（Spring Session Redis）**

- [x] `GET /api/auth/sessions`：当前用户的活动会话（会话 id 脱敏、IP、User-Agent、创建时间、最近访问）；数据来自 `FindByIndexNameSessionRepository.findByPrincipalName` + 会话属性。
- [x] `DELETE /api/auth/sessions/{id}`：吊销自己的指定会话；不能删除其它用户会话（404）；审计 `session.revoke`。
- [x] `GET /api/auth/users/{id}/sessions` / `DELETE .../sessions/{sessionId}`：org_admin 运维使用；审计 `session.admin.revoke`。
- [x] 当前会话标记 `current=true`，前端不允许误删当前会话（或删除后立即登出）。
- [x] 会话视图不返回 Spring Session 的主键 / 属性序列化内容。
- [x] 禁用 / 改密 / 重置时删除会话的逻辑与上述端点共用 `SessionAdminService`，避免两套实现。

**用户令牌管理**

- [x] `GET /api/auth/tokens`：当前用户令牌列表（`id/name/prefix/purpose/expiresAt/lastUsedAt/createdAt/status`），不含 hash / 明文。
- [x] `POST /api/auth/tokens`：`{name, expiresInDays?}`；默认 90 天，最大 365 天；返回 `token` 明文一次；审计 `token.create`。
- [x] `DELETE /api/auth/tokens/{id}`：只能吊销自己的令牌；审计 `token.revoke`。
- [x] `GET /api/auth/users/{id}/tokens` / `DELETE /api/auth/users/{id}/tokens/{tokenId}`：org_admin 运维使用；审计 `token.admin.revoke`。
- [x] `POST /api/auth/users/{id}/token` 兼容端点：吊销旧 `legacy` / `api` 令牌并签发新 `api` 令牌，响应仍为 `{user, token}`。
- [x] 令牌解析只接受未吊销、未过期、用户 active 的记录；`last_used_at` 写节流 5 分钟。
- [x] 过期 / 吊销清理任务：每日删除过期 / 已吊销超过 7 天的 `user_token`（会话由 Spring Session 自带清理；审计记录不删）。

**V7 收紧**

- [x] 代码切换完成后，把 `application.yml` 的 `spring.flyway.target` 从 `6` 改为 `7`（或删除该行），执行 `V7__identity_tighten.sql`，删除 `app_user.token_hash` / `app_user.is_platform_admin`。
- [x] grep 门禁：`is_platform_admin`、`isPlatformAdmin`、`tokenHash`（`app_user` 语境）、`platformAdmin` 仅允许出现在历史迁移 / 文档中。
- [x] `ManagementDbMigrationTest` 覆盖 V6→V7 后旧 token 仍可认证、用户列表正常。

**保护规则**

- [x] 最后一名可登录的 `org_admin`（`app_user.status ∈ active|invited` + `org_membership.status='active'`）不可禁用 / 降级 / 删除（409，提示先指定新的 org_admin）。
- [x] 禁用 / 重置 / 删除不可作用于当前操作者自身（删除已有保护；禁用 / 重置补齐）。
- [x] 所有生命周期动作写 `audit_event`；用户可见的产品流仍写 `changelog`（C7 已接入）。

**测试**

- [x] `UserLifecycleApiTest`：禁用后 Spring Session 会话 / 旧令牌立即 401；启用不恢复；重置令牌单次；最后 org_admin 保护；邮箱冲突；删除清理关系。
- [x] `SessionAdminApiTest`：查看自己的会话、吊销指定会话、不能吊销他人、org_admin 运维吊销、当前会话标记。
- [x] `UserTokenApiTest`：创建 / 列表 / 吊销 / 过期 / 他人令牌 404 / admin 吊销；旧 token 在 V7 后仍有效。
- [x] `AuthApiTest` 改造：`is_platform_admin` 移除后的 admin 语义、`/me` 字段。
- [x] 双库全量测试与 ktlint 通过。

> **C4 实施说明（2026-11-20）**：
> - 审计写点（`user.*` / `session.*` / `token.*`）统一随 C7 `AuditService` 接入；C4 已写产品 changelog。
> - V7 对 C2/C3 轮换后的 `app_user.token_hash` 采用「补记 revoked legacy 再删列」策略（行数不再要求相等，缺失回填仍中止）；`ManagementDbMigrationTest` 已覆盖。
> - 「最后一名可登录 org_admin」保护在 `UserLifecycleService` 统一判定；自禁用 / 自重置 / 自删除返回 400。

**状态**：✅ 2026-11-20 完成（审计已随 C7 接入）。

---

### C5 · 项目级服务账号（M）

**领域层**

- [x] `ServiceAccountRecord`：`id/orgId/projectId/name/prefix/role/status/expiresAt/lastUsedAt/revokedAt/createdAt`。
- [x] `ServiceAccountService`：
  - `create(orgId, projectId, name, role, expiresAt, actor)`：校验项目属于组织；名称项目内忽略大小写唯一；角色仅 `viewer|editor`；过期 1–365 天；
  - `list(projectId)`：不含 hash；
  - `revoke(id, projectId, actor)`：置 `status=revoked` + `revoked_at`，立即生效；
  - `rotate(id, projectId, actor)`：事务内吊销旧密钥并签发新密钥，返回明文一次；
  - `resolve(raw)`：校验前缀、hash、状态、过期、项目未删除。
- [x] `ProjectService.delete`：删除项目前显式 `deleteAllByProjectId` 清理服务账号（或返回 409 要求先吊销，C0 拍板；建议清理并审计）。

**API**

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/api/service-accounts?projectId=` | 项目 `admin+` / `org_admin` | 列表；跨项目 404 |
| `POST` | `/api/service-accounts` | 项目 `admin+` / `org_admin` | 创建；明文只返回一次 |
| `DELETE` | `/api/service-accounts/{id}` | 项目 `admin+` / `org_admin` | 吊销；跨项目 404 |
| `POST` | `/api/service-accounts/{id}/rotate` | 项目 `admin+` / `org_admin` | 轮换；旧密钥立即失效 |

- [x] 创建请求 `{projectId, name, role, expiresInDays?}`；响应 `{account, token}`。
- [x] 列表视图不返回 `tokenHash` / 明文；显示 `prefix` 便于识别。
- [x] 服务账号与项目成员可同名，但不共享成员表；审计 actor 用 `service:<name>` 区分。

**认证与授权**

- [x] `ServiceAccountAuthenticationProvider`（或 `BearerAuthenticationFilter` 内的委托）：按 SHA-256 查 `service_account`（`shs_` 前缀仅用于展示 / 基本校验），校验状态 / 过期 / 吊销，返回带 `ServicePrincipal` 的 `Authentication`；接入 Bearer API 链。
- [x] `AuthService.effectiveRole`：服务账号仅在其 `projectId` 返回自身角色；其它项目 `null`，读 404 / 写 403。
- [x] 服务账号不可登录 Web：Web 链的 JSON 登录 filter 只接受用户令牌 / 密码，拒绝 `shs_`；`/api/auth/login` 对服务账号令牌返回 401。
- [x] 服务账号不可访问：`/api/auth/users`、`/api/teams`、`/api/projects` 管理动作（POST/PATCH/DELETE/transfer/archive）、`/api/audit`、`/api/service-accounts`（管理自身也不行）；评论 / 订阅 / 评审审批等人类协作端点使用 `requireHuman` 拒绝。
- [x] 服务账号可访问：所属项目的 repos / apis / docs / export / mock / spec 读写（按角色）、导入、评审状态读取与写回提交；`GET /api/projects` 只返回所属项目；actor 记 `service:<name>`。
- [x] 服务账号对 `/api/apis` 等列表的可见集合只包含所属项目；空集合返回空，禁止退化全量。
- [x] `/mock/{repoId}/**`：携带有效服务账号且项目匹配时可用；跨项目 / 无效令牌 404 / 401（D1 再做 public/mock key 收敛）。

**MCP / CLI**

- [x] 文档更新：`SPECHUB_TOKEN` 可使用服务账号令牌（MCP `apps/mcp/src/config.ts`；CLI `~/.spechub.json` / `SPECHUB_TOKEN`）。
- [x] 明确服务账号角色：MCP 写工具需 `editor`；只读工具 `viewer` 即可。
- [x] 令牌过期 / 吊销后的错误提示写入 README 与 CLI README；无需改客户端代码。

**测试**

- [x] `ServiceAccountApiTest`：创建 / 列表 / 吊销 / 轮换；viewer 可读不可写；editor 可写；跨项目 404；吊销后立即 401；过期 401；不能登录 Web；不能访问 org_admin 端点。
- [x] `ReadIsolationTest` 扩展：服务账号在项目 A，访问项目 B 的列表 / 详情 / 导出 / mock 全部 404。
- [x] `ProjectApiTest`：项目删除时服务账号清理（或 409 行为与 C0 一致）。
- [x] 双库全量测试与 ktlint 通过。

> **C5 实施说明（2026-11-20）**：服务账号 token 认证复用 C2 的 `TokenAuthenticationProvider`（先 user_token、后 service_account）；`service_account.*` 的 `audit_event` 写点已随 C7 接入。MCP / CLI 继续使用 `SPECHUB_TOKEN`，无需改客户端代码。

**状态**：✅ 2026-11-20 完成（审计已随 C7 接入）。

---

### C6 · OIDC 登录（L）

**依赖与配置**

- [x] `spring-boot-starter-oauth2-client`（C2 已引入）；OIDC 作为 **Web 链的 `oauth2Login`**，不是独立安全链，也不走自定义 filter。
- [x] 依赖审计：OIDC 相关库若传递 Jackson 2，仅允许运行期由库内部使用；业务代码不得 import，`verifyNoJackson2Usage` 与依赖树核对（沿用 CLAUDE.md 的 Jackson 2 边界）。
- [x] `SpecHubProperties` 增加 `OidcProperties`：
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
- [x] 校验：`enabled=true` 时 `issuer-uri` / `client-id` / `client-secret` 必填，缺失启动失败；`client-secret` 只经环境变量注入。
- [x] `enabled=false` 时不注册 `ClientRegistrationRepository`、不给 Web 链配置 `.oauth2Login`，登录页不显示 SSO；避免无 registration 启动失败。
- [x] `ClientRegistration.withRegistrationId(...)` 从 `OidcProperties` 构造 `ClientRegistrationRepository`（不依赖 `spring.security.oauth2.client.*` 前缀，避免两套配置）；`issuer-uri` 启动时发现端点，若客户 IdP 不允许启动外联，允许显式配置 authorization / token / jwks URI。
- [x] `GET /api/auth/status` / `/api/auth/me` 返回 `oidcEnabled` / `oidcLoginUrl`（`/oauth2/authorization/spechub`）。
- [x] prod 文档：OIDC 回调地址需在 IdP 注册；`external-url` 必须为浏览器可访问地址。dev 使用 Vite 代理时，`external-url` / `redirect-uri` 应指向前端开发地址（如 `http://localhost:5173`），保证回调与 Cookie 同源。

**Web 链集成**

- [x] 在 C2 的 Web `SecurityFilterChain` 上配置 `.oauth2Login { ... }`：
  - 使用上面的 `ClientRegistrationRepository`；
  - `OAuth2AuthorizedClientRepository` 使用 no-op 实现（不把 IdP access / refresh token 存进 Spring Session）；
  - 自定义 `OidcUserService` 调用 `OidcLoginService`，返回**可序列化**的自定义 `OidcUser` principal（带 `appUserId`）；
  - success handler 重定向 `/`（只允许站内相对路径）；failure handler 重定向 `/?loginError=oidc`，不把 IdP 错误详情放 URL；
  - 放行 `/oauth2/**`、`/login/oauth2/**`；CSRF 对 OIDC 回调豁免（state / nonce / PKCE 由 Spring Security 处理）；
  - 登录成功后 Spring Security 自动把 `SecurityContext` 写入 Spring Session；不手工创建会话。
- [x] 不在 OIDC 链里做业务鉴权；登录后统一由 `AuthService` / `AuthzService` 判定。
- [x] 所有环境始终启用鉴权；`admin-token` 仅用于 bootstrap / break-glass，OIDC 是附加登录方式，不再作为“启用鉴权”的开关。

**登录与绑定**

- [x] `OidcLoginService.resolve(claims)`：
  1. 校验 `issuer` / `subject` 非空；`email_verified` 为 false 且 `require-verified-email=true` → 拒绝；
  2. 按 `user_identity(provider, subject)` 查用户 → 检查 `status ∈ active|invited`；
  3. 未绑定 → 按 `lower(email)` 查用户 → 绑定 `user_identity`；
  4. 仍未找到 → `auto-provision=false` 拒绝（重定向登录页错误）；`true` 时创建用户：用户名取邮箱 local part（冲突追加序号）、默认 `org_member`、`status=active`、`password_hash=NULL`，写审计 `auth.oidc.provision`；
  5. 返回 `appUserId` / `AppUserPrincipal`；**不手工创建会话**，由 `oauth2Login` + Spring Session 完成；
  6. 写审计 `auth.oidc.login` / `auth.oidc.link` / `auth.oidc.provision`。
- [x] `OidcUserService` 返回自定义 `OidcUser` 实现（`Serializable`，携带 `appUserId` / `username` / `displayName`），不把 `DefaultOidcUser` 或实体直接写进 Spring Session。
- [x] `user_identity` 记录 `provider = "oidc:" + issuer`、`subject`、`email` 快照；重复绑定 409 / 忽略。
- [x] 已有密码用户绑定 OIDC 后两种登录方式并存；OIDC-only 用户 `password_hash=NULL`，密码登录统一 401。
- [x] 不做 RP-initiated logout、不刷新 IdP token、不存 IdP access / refresh token。

**测试**

- [x] `OidcPropertiesTest`：启用时必填、prod 校验、默认值。
- [x] `OidcLoginServiceTest`：已绑定身份、邮箱绑定、未验证邮箱拒绝、auto-provision 开 / 关、禁用用户拒绝、重复身份、默认 org 归属。
- [x] `OidcWebChainTest`：`oauth2Login` 只在 Web 链生效；Bearer API 链不受影响；success / failure 重定向；CSRF 对 OIDC 回调豁免。
- [x] 手动 e2e（C9.1）：Keycloak / 测试 IdP 浏览器登录 → Spring Session 会话 → `/api/auth/me` → 登出；无 IdP 时登录页只显示密码 / 令牌。
- [x] 双库全量测试与 ktlint 通过。

> **C6 实施说明（2026-11-20）**：
> - `OidcProperties` 校验在 `SpecHubPropertiesValidationTest` 覆盖（含 enabled 必填 / 默认值 / auto-provision 默认关）。
> - OIDC 只在 Web 链启用；Bearer 链、密码 / 令牌登录不受影响；PKCE 由 `ClientSettings.requireProofKey(true)` 强制。
> - 身份绑定 / 邮箱绑定 / auto-provision / 未验证邮箱 / 禁用用户已在 `OidcLoginServiceTest` + `OidcAutoProvisionTest` 覆盖；principal 可序列化且经 `AuthService` 作为人类用户识别。
> - `auth.oidc.*` 审计写点已随 C7 接入；不存 IdP access / refresh token。

**状态**：✅ 2026-11-20 完成（审计已随 C7 接入）。

---

### C7 · 管理动作审计（M）

**领域层**

- [x] `AuditEventEntity` / `AuditEventRepository`：
  - `save`、`findByOrgIdAndCreatedAtBetween...`、`findByOrgIdAndActorName...`、`findByOrgIdAndAction...`；
  - 复杂组合查询用 `JdbcTemplate`，分页 `limit` / `offset`。
- [x] `AuditService`：
  - `record(orgId, principal, action, targetType, targetId, detail, outcome, request)`；
  - `actorType`：`user` / `service_account` / `anonymous`；
  - `actorName`：用户名或 `service:<name>`；
  - `detail` 截断（如 1024 字符），禁止密码 / 令牌 / Cookie / spec 正文；
  - 写失败：管理动作同事务失败；登录类尽力而为 + ERROR 日志（C0 确认）。
- [x] 查询 `list(orgId, filters, limit, offset)`：默认最近 30 天，`limit` 默认 50 / 最大 200。

**写点接入**

- [x] 认证：`auth.login.success` / `auth.login.failure` / `auth.login.locked` / `auth.logout` / `auth.password.change` / `auth.password.setup` / `auth.oidc.*` / `session.revoke`。
- [x] 认证事件来源：优先在 JSON 登录 filter 的 success / failure handler 与 `LogoutFilter` 中写审计（可拿到 IP / UA / 失败原因）；可选监听 Spring Security `AuthenticationSuccessEvent` / `AuthenticationFailureBadCredentialsEvent` 作为补充，避免重复写同一条。
- [x] 会话事件：禁用 / 改密 / 重置 / 管理员吊销时写 `session.admin.revoke`；Spring Session 的 `SessionDestroyedEvent` 可用于补充审计，但不能替代业务动作审计。
- [x] 用户：`user.create` / `user.update` / `user.role` / `user.disable` / `user.enable` / `user.password.reset` / `user.delete`。
- [x] 令牌：`token.create` / `token.revoke` / `token.admin.revoke` / `token.rotate`。
- [x] 服务账号：`service_account.create` / `service_account.revoke` / `service_account.rotate`。
- [x] 组织级管理动作（与现有 changelog 双写，审计用）：
  - 项目：`project.create` / `update` / `archive` / `transfer` / `delete`；
  - 项目成员：`project.member.add` / `role` / `delete`；
  - 团队：`team.create` / `update` / `delete` / `team.member.*`；
  - Git：`git_connection.create` / `disable`；`repo.binding.create` / `verify` / `activate` / `retire` / `delete`。
- [x] 内容写操作（spec / comment / review / subscription / mock）继续只写 `changelog`，不写 `audit_event`，避免审计噪音。
- [x] `actor_user_id` / `org_id` 从当前 principal / 请求上下文获取；服务账号 actor 为 `service_account`。
- [x] 登录失败时若用户名存在 → 用该用户 org；否则用默认 org（C 单组织）；D1 多组织再收敛。

**API**

- [x] `GET /api/audit?from=&to=&actor=&action=&targetType=&outcome=&limit=&offset=`：
  - 仅 `org_admin`；服务账号 403；项目 admin 403；
  - 固定当前 org，禁止跨 org；
  - 按 `created_at DESC, id DESC` 排序；
  - 返回 `AuditEventView(id, actorName, actorType, action, targetType, targetId, detail, outcome, ip, createdAt)`；
  - `from` / `to` 使用 ISO Instant；参数非法 400。
- [x] 审计查询本身不写审计（避免自增循环）；仅 INFO 日志。

**保留与清理**

- [x] `spechub.audit-retention-days=365`；每日清理 `created_at < now - retention` 的记录。
- [x] 备份：`audit_event` 随管理库备份；运维手册补说明。

**测试**

- [x] `AuditApiTest`：登录成功 / 失败、禁用、令牌、服务账号、项目 / 团队动作写入；org_admin 可查；项目 admin / 服务账号 403；actor / action / outcome / 时间过滤；跨 org 不可见；`detail` 不含敏感值。
- [x] `AuditRetentionTest`：过期记录清理，未过期保留。
- [x] 双库全量测试与 ktlint 通过。

> **C7 实施说明（2026-11-20）**：
> - 写点已覆盖：`auth.login.*` / `auth.logout` / 密码、会话、用户生命周期、用户令牌、服务账号、OIDC、项目 / 成员 / 团队 / binding / Git 连接。
> - 用户 / 令牌 / 服务账号 / Git 连接在服务事务内写审计（失败回滚）；项目 / 团队 / 成员 / binding 在控制器动作后写入（与 changelog 同点），审计失败不改变已完成动作。
> - 认证 / 限流事件用独立事务尽力而为；`detail` 只存摘要并截断 1024；查询仅 org_admin、固定 org。
> - `audit_event` 随管理库备份；运维手册保留策略细节由 C10 补齐。
> - `auth.oidc.*` 审计已随 C7 接入（C6 遗留项清零）。

**状态**：✅ 2026-11-20 完成。

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
- [ ] 新增 API：`logout` / `changePassword` / `setupPassword` / 会话列表与吊销 / 令牌 CRUD / 服务账号 CRUD / `listAudit`。

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
- [ ] 所有环境启动即探测 `/api/auth/status` + `/api/auth/me`；未认证一律显示登录框，**不再有 dev 免登录 / `authEnabled=false` 分支**；dev 可通过 `VITE_DEV_ADMIN_TOKEN` 预填 token（仅 dev 构建、不进生产包），但仍调用 `/api/auth/login` 拿真实 Spring Session。

**新增对话框**

- [ ] `SecurityDialog.tsx`：
  - 资料：用户名 / 邮箱 / 状态 / 组织角色；
  - 修改密码（当前密码 + 新密码 + 确认）；
  - 活动会话：列表（IP / User-Agent / 创建时间 / 最近访问 / 当前会话标记）、吊销其它会话；数据来自 Spring Session；
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

- [ ] V5（含用户 / 旧 token / 团队 / 订阅数据）→ V6：`app_user` 新列、`user_token` legacy 回填、`service_account` / `user_identity` / `audit_event` 建表、旧数据可读；**不建会话表**。
- [ ] 会话 / 限流使用 Redis：新增共享 Redis Testcontainer；每个测试类使用独立 namespace（或测试前后清理），避免会话 / 限流键串扰；不把会话写入管理库，不做 JDBC 迁移。
- [ ] V6 → V7：`token_hash` / `is_platform_admin` 删除；旧 token 通过 `user_token` 仍可认证；`org_membership` admin 关系不变。
- [ ] 空库 V1 → V7 一次跑通。
- [ ] 邮箱唯一（忽略大小写）、服务账号名称项目内唯一、单主 / FK 约束不回归；`PRINCIPAL_NAME` 索引由 `RedisSessionTest` 覆盖。

**新增集成测试**

| 测试类 | 覆盖 |
|---|---|
| `SecurityConfigTest` | Bearer 链 / Web 链选择、CSRF 豁免、所有 profile 默认拒绝、401/403 错误契约、安全头 |
| `PasswordLoginApiTest` | JSON 登录 filter、密码 / 邮箱登录、错误凭据、禁用、限流、Cookie 属性、会话固定防护 |
| `SessionApiTest` | Spring Session Data Redis 认证、登出、空闲 / 绝对过期、CSRF、Bearer 豁免 |
| `RedisSessionTest` | Redis 会话 TTL / 清理、`PRINCIPAL_NAME` 索引、Redis 不可用 fail-closed |
| `RedisLoginRateLimiterTest` | Redis 窗口 / 锁定 / 清零 / Redis 不可用 fail-closed |
| `SessionAdminApiTest` | 自己的会话列表 / 吊销、不能吊销他人、org_admin 运维吊销、当前会话标记 |
| `PasswordChangeApiTest` | 改密、邀请 / 重置令牌、单次使用、密码策略、其它会话删除 |
| `UserLifecycleApiTest` | 禁用 / 启用 / 删除 / 最后 org_admin 保护 / 邮箱冲突 |
| `UserTokenApiTest` | 创建 / 列表 / 吊销 / 过期 / admin 吊销 / V7 后旧 token |
| `ServiceAccountApiTest` | CRUD / 轮换 / 跨项目 / viewer-editor / 吊销即时 / 不可登录 Web |
| `AuditApiTest` | 事件写入、org_admin 查询、过滤、跨 org、敏感值 |
| `AuditRetentionTest` | 过期记录清理、未过期保留 |
| `OidcLoginServiceTest` | 身份绑定 / 邮箱绑定 / auto-provision / 未验证邮箱 / 禁用 |
| `OidcWebChainTest` | `oauth2Login` 只挂 Web 链、Bearer 链不受影响、success/failure 重定向 |
| `AuthPrincipalTest` | 人 / 服务账号 principal、跨项目不可见 |

**改造现有测试**

- [ ] 测试基类（`TestDbIsolation` / `TestManagementDb`）统一注入测试 `spechub.admin-token`；所有 `@SpringBootTest` 走真实登录 / Bearer；禁止 anonymous 模式。
- [ ] `SpecHubPropertiesValidationTest`：所有 profile 要求 admin-token；删除 dev 允许缺失 / 免鉴权的断言。
- [ ] `AuthApiTest`：旧 token 登录建立 Spring Security 会话；`is_platform_admin` 移除；`/me` 字段；admin 语义改为 `org_admin`。
- [ ] `AuthVisibilityTest` / `ReadIsolationTest`：Session / Bearer 双链；服务账号隔离；CSRF 对 Bearer 豁免。
- [ ] `ProjectApiTest` / `ProjectMembershipApiTest` / `TeamApiTest` / `RepoBindingApiTest`：审核动作审计断言（如涉及）；项目删除服务账号清理；Session 登录的 POST 带 CSRF（`spring-security-test` 或真实 token）。
- [ ] `WebApiTest` / `CommentApiTest` / `ReviewApiTest` / `SubscriptionApiTest` / `ImportExportApiTest`：改为真实 auth 模式（测试 admin token / 登录夹具）；`currentUser` / `isPlatformAdmin` 变更适配；Spring Security 错误契约不回归。
- [ ] `ValidationContractTest`：新请求体校验（登录、密码、用户、服务账号、审计参数）。

**门禁命令**

- [ ] `./gradlew check --rerun-tasks`（PostgreSQL + Redis Testcontainer）通过；
- [ ] `./gradlew :server:test -Dspechub.test.db=mysql --rerun-tasks`（MySQL + Redis Testcontainer）通过；
- [ ] `./gradlew :server:ktlintCheck` 通过；
- [ ] `cd apps/web && npm run build` 通过；
- [ ] 全环境真实登录回归（Doc / Comment / Web / Subscription / ImportExport）通过；测试统一配置 admin-token / 登录夹具，不存在 anonymous 模式；
- [ ] grep 门禁：`is_platform_admin` / `isPlatformAdmin` / `platformAdmin` / `app_user.token_hash` 无业务代码引用。

### C9.1 手动 e2e 清单（浏览器）

> 自动化覆盖 API 链路；以下清单用于 UI 联调 / 验收，待人工执行。

1. 启动 backend + Redis（配置 `SPECHUB_ADMIN_TOKEN` 与 `SPECHUB_REDIS_URL`），打开 Web：登录框出现，浏览器 localStorage 无长期 token。
2. `admin` 用 admin-token 登录：账号菜单显示 `org_admin`；浏览器持有 `spechub_session` Cookie（HttpOnly），`/api/auth/me` 返回 `authMethod=session` 与 CSRF token；响应带 Spring Security 安全头。
3. 账号与安全 → 修改密码；登出后旧密码失效、新密码可登录。
4. 创建用户 A（邮箱 + 初始密码），A 首次登录被要求改密；改密后进入系统。
5. 创建用户 B（邀请），复制 `setupToken` 设置密码；令牌第二次使用失败。
6. 禁用 A：A 当前 Spring Session 会话下一次请求 401；A 的 API 令牌 401；重新启用后旧会话 / 旧令牌仍不可用。
7. 账号与安全 → 活动会话：能看到其它登录设备，吊销后该会话下一次请求 401；不能吊销他人会话。
8. 创建用户令牌：列表只显示前缀；吊销后立即 401。
9. 当前项目 admin 创建服务账号（viewer / editor）：用 `SPECHUB_TOKEN=shs_...` 调 MCP 只读 / 写工具；viewer 写入 403；Bearer 请求不带 Cookie / CSRF 也能工作。
10. 服务账号访问其它项目 API：列表 / 详情 / 导出 / mock 均 404。
11. 配置 OIDC（测试 IdP）：登录页出现 SSO 按钮；登录后绑定 `user_identity`；再次登录直接命中绑定；禁用用户后 SSO 登录被拒；浏览器只持有本地 `spechub_session`，无 IdP token。
12. 审计日志：org_admin 能看到登录、禁用、会话吊销、令牌、服务账号、项目 / 团队动作；项目 admin 入口不可见；过滤与分页可用。
13. 本地开发与生产一致：不配置 admin-token 启动失败；配置 `.env` 的 dev admin-token 后必须登录；没有「免登录」入口，浏览器只持有真实 `spechub_session`。
14. 停掉 Redis：登录、会话校验、登录限流 fail-closed（503 / 无法建立会话），不降级为匿名或内存会话；恢复 Redis 后可重新登录。

**状态**：待启动。

---

### C10 · 文档与收尾（S）

- [ ] 开发计划新增 P2.7（阶段 C）并勾选。
- [ ] 本文档状态更新、任务勾选。
- [ ] README 补账号体系说明：Spring Security + Spring Session、密码 / OIDC、CSRF、令牌类型与前缀、服务账号、CLI/MCP 使用。
- [ ] ADR §13 开放问题回填：
  - 2（服务账号是否提前到 A）→ C 落地，项目级；
  - 5（SSO 优先级）→ OIDC 优先，SAML / SCIM 延后；
  - 12（平台运营 IdP）→ D1，不在 C；
  - 新增 / 更新 C 阶段迁移说明（V6 / V7、Spring Session schema、旧 token 兼容）。
- [ ] `SpecHub-管理数据持久化选型.md` 补 Phase 6：新表、Redis 会话数据（不纳入管理库备份）、令牌 / 审计数据归属与备份范围；说明 C 阶段直接使用 Redis，不做 JDBC 过渡。
- [ ] `SpecHub-运维手册.md` 补：
  - Redis 会话 / HA（Sentinel/Cluster）/ TTL / cleanup-cron / fail-closed；
  - 会话列表与吊销、禁用用户 / 重置密码 / 最后 org_admin 保护；
  - OIDC 配置与回调地址、CSRF / Cookie / 安全头说明；
  - 审计保留与查询；
  - 备份管理库新增表（会话不在备份范围）。
- [ ] `SpecHub-UI方案.md` 补登录、账号与安全（含活动会话）、服务账号、审计页。
- [ ] `.env.example` / compose 补 `SPECHUB_ADMIN_TOKEN`（dev 值）、`SPECHUB_REDIS_URL`、Redis 服务、OIDC 环境变量、Cookie 配置说明；dev 与 prod 使用同一套登录流程与会话存储。
- [ ] `application-dev.yml`：`require-admin-token: true`、Spring Session Data Redis、`SPECHUB_REDIS_URL`；删除 `authEnabled=false` / 免登录相关配置与注释；README 补首次 bootstrap 流程（admin + token → 设置密码 / 绑定 OIDC）。
- [ ] `SpecHub-后端编码规范.md` 认证章节更新：Spring Security 双链、`AuthService` / `AuthzService`、CSRF、可序列化 principal、令牌不落日志、V6/V7 迁移纪律、所有环境真实登录。

**状态**：待启动。

---

## 4. 关键路径与并行

**关键路径**：

```text
C0 → C1 → C2 → C3 → C4 → C5/C6 → C7 → C8 → C9 → C10
```

**Spring Security 分片顺序**（禁止 big-bang，每片独立测试 / 合并）：

1. 引入 Spring Security + Spring Session，**Web 链**先接管密码登录、会话、CSRF、安全头；Bearer 暂按现有逻辑兼容（可先委托旧 filter 解析）。
2. OIDC 切到 Web 链的 `oauth2Login`（C6），删除独立 OIDC 旁路。
3. Bearer 切到 `BearerAuthenticationFilter` / `TokenAuthenticationProvider`，服务账号接入 API 链（C5），删除 `TokenAuthFilter`。
4. 域授权收敛到 `AuthService` / `AuthzService`，清理 `isPlatformAdmin` / `token_hash`（C2 / C4）。

> **C2 实施结果（2026-11-19）**：分片 1 / 3 / 4 已落地（Web Session + `BearerAuthenticationFilter` / `TokenAuthenticationProvider` + 域授权收敛；旧 `TokenAuthFilter` 已删除）；分片 2（OIDC `oauth2Login`）留 C6。

**两人并行建议**：

- 一人：C1（迁移）→ C2（Spring Security 底座）→ C3（会话登录）→ C4（生命周期 / V7）。
- 一人：C3 完成后接 C5（Bearer provider）→ C6（OIDC）→ C7（审计）。
- C8 等 C3–C7 的 API 契约稳定后开始；C9 贯穿，迁移测试可在 C1 完成后先行。
- C6 依赖外部 IdP 时，可先用单元测试 + 本地 Keycloak 手动验证，不阻塞 C7 / C8。

**一人串行建议**：C0 → C1 → C2 → C3 → C4 → C5 → C6 → C7 → C8 → C9 → C10。

---

## 5. 安全门禁

- **门禁 1（迁移）**：V6 回填完成前不改认证写路径；V7 删除旧列前，grep 门禁必须清零，且旧 token 兼容测试全绿。
- **门禁 2（会话）**：Spring Session Data Redis 在 dev / prod 都使用；Cookie 必须 `HttpOnly` + `SameSite=Lax`（prod `Secure`）；CSRF 必须由 Spring Security `CsrfFilter` 覆盖 Web 链；登录必须走 `SessionAuthenticationStrategy`（会话固定防护）；Bearer API 链无会话、无 CSRF；Redis 不可用 fail-closed。
- **门禁 3（禁用）**：禁用用户必须在 DB 事务吊销全部令牌，并在提交后删除全部 Redis 会话；Redis 删除失败由每请求状态校验兜底，下一次请求仍必须 401；测试覆盖会话与令牌两条路径。
- **门禁 4（服务账号）**：服务账号只能访问所属项目；跨项目读 404 / 写 403；不可访问 org_admin / 团队 / 审计端点；吊销立即生效；Bearer 链不创建会话。
- **门禁 5（密码）**：Argon2id；不记录明文；登录失败统一 401；限流生效；重置令牌一次性。
- **门禁 6（OIDC）**：只绑定已验证邮箱；`auto-provision` 默认关闭；state / nonce / PKCE 由 `oauth2Login` 处理；不存 IdP token。
- **门禁 7（审计）**：`audit_event` 不含密码 / 令牌 / Cookie / spec 正文；仅 `org_admin` 可查；跨 org 不可见。
- **门禁 8（全环境鉴权）**：所有 profile `authEnabled=true`、`require-admin-token=true`；未配置 admin-token 启动失败；dev 也必须真实登录，不存在免登录分支；测试同样使用真实登录 / Bearer。
- **门禁 9（日志）**：认证类日志只记 `username` / `principal` / IP / 时间，不记凭据。
- **门禁 10（链与契约）**：`SecurityConfigTest` 必须覆盖 Bearer / Web 双链选择、默认拒绝、自定义 `AuthenticationEntryPoint` / `AccessDeniedHandler` 返回 `ApiErrorBody`；任何 `permitAll` 都必须有测试与注释。

---

## 6. 前置假设（C0 已确认）

> 2026-11-19 已按建议默认值确认；下表为确认结论。

| 项 | 建议默认 | 影响 |
|---|---|---|
| 认证架构 | Spring Security 作为 Web 认证底座；Bearer API / Web Session 双链；自定义 opaque Bearer provider；所有环境真实登录 | C2 / C6 |
| 会话存储 | **Spring Session Data Redis 在 dev 与 prod 都使用**；Redis 是 C 阶段必需组件，不做 JDBC 过渡；不建 `user_session` / `SPRING_SESSION` 表 | C1 / C2 / C3 |
| 会话时效 | 空闲 24 小时 + 绝对 7 天；Spring Security 会话固定防护 | C3 |
| CSRF | Spring Security `CsrfFilter` + session token + `X-CSRF-Token`；Bearer 链关闭 | C3 / C8 |
| 密码哈希 | Argon2id（`Argon2PasswordEncoder` + BouncyCastle） | C2 / C3 |
| 密码策略 | 10–128，禁止等于用户名 / 邮箱 | C3 |
| 令牌前缀 | `shu_` / `shs_`（会话不再有自研前缀） | C1 / C2 |
| 旧令牌 | V6 回填、继续有效；V7 删列 | C1 / C4 |
| `is_platform_admin` | C 内移除，改用 `org_membership.org_role` | C2 / C4 / C8 |
| 服务账号 | 项目级、viewer/editor、默认 90 天、opaque provider | C5 |
| OIDC | 单 IdP、`oauth2Login`、PKCE、邮箱验证、auto-provision 默认关 | C6 |
| 邮箱 | 存储 + 唯一，不发信 | C1 / C4 |
| 禁用语义 | 全局 `app_user.status`；DB 事务吊销令牌，提交后删除 Redis 会话；删除失败由每请求状态校验兜底 | C4 |
| 审计存储 | 新表 `audit_event`，保留 365 天；可补 Spring Security 事件 | C7 |
| 登录限流 | **Redis** 5 次 / 5 分钟，锁 15 分钟；dev/prod 同一实现；Redis 不可用 fail-closed | C3 |
| 认证开关 | 所有环境 `authEnabled=true`、`require-admin-token=true`；未配置 admin-token 启动失败；dev 用 `.env` 的 dev 值 bootstrap | C2 / C3 / C6 |
| dev bootstrap | dev `.env` 提供 `SPECHUB_ADMIN_TOKEN`；`admin` + token 真实登录 → 设置密码 / OIDC；无免登录、无 dev 链 | C2 / C3 / C8 / C9 |
| 最后 org_admin 保护 | 不可禁用 / 降级 / 删除 | C4 |
| V6/V7 | 两阶段迁移；V6 只建身份 / 令牌 / 服务账号 / 审计表（会话在 Redis），V7 与代码同窗口 | C1 / C4 |

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Spring Security 链配置错误（`permitAll` / matcher / 默认安全链） | 鉴权绕过或端点被误拦 | 显式两条链 + 默认拒绝；`SecurityConfigTest` 覆盖链选择、CSRF、错误契约；每个 `permitAll` 有注释与测试 |
| Bearer 与 Web 链选择错误 | CSRF / 会话策略冲突，CLI 请求 401 | `BearerRequestMatcher` 只匹配 Bearer 头；真实 HTTP 测试双链；Bearer 链 `STATELESS` + `csrf.disable` |
| 现有测试依赖 dev 免登录 | 全量测试需改为真实登录，短期改造量大 | 统一测试 admin-token + 登录夹具；按测试类分批切换；禁止新增 anonymous 模式 |
| Redis 不可用 / 单点 | 登录、会话校验、限流全部不可用 | Redis HA（Sentinel/Cluster）、健康检查、fail-closed；不在 Redis 故障时降级到本地内存 / 匿名 |
| 会话删除与 DB 非同一事务 | 禁用 / 删除后 Redis 会话残留 | DB 先提交 status / 令牌吊销；Redis 删除失败由每请求状态校验兜底；专项测试覆盖“Redis 删除失败仍 401” |
| Spring Session Redis 序列化 / principal 变更 | 旧会话反序列化失败、500 | principal 最小化并 `Serializable`；不把实体 / `OidcUser` 写会话；部署变更时清理 namespace / 强制重新登录；集成测试覆盖 |
| 域授权散落或 authorities 过期 | 角色变更不生效 / 越权 | 不把 org/project 角色放进 authorities；`AuthService` / `AuthzService` 每请求查库；专项测试 |
| 禁用后旧凭据仍可用 | 安全事件 | DB 事务吊销令牌 + 删除 Redis 会话 + 每请求状态检查 + 专项测试 |
| 旧 token 迁移失败 | CLI / MCP 全部失效 | V6 回填 + 迁移测试覆盖旧 token 认证；V7 与代码同窗口；启动日志提示 |
| `is_platform_admin` 移除改变自批 / 管理语义 | 评审 / 评论行为回归 | 统一映射为 `org_admin`；`SpecWriteService` / `CommentController` 专项测试 |
| 服务账号跨项目越权 | 租户内横向移动 | `ServicePrincipal` 只带 `projectId`；跨项目 404/403；`ReadIsolationTest` 扩展 |
| Redis 内存 / 审计表增长 | Redis 内存压力、审计查询变慢 | 会话 TTL + cleanup-cron；`audit_event` 每日清理与索引；Redis maxmemory 策略与监控 |
| `last_used_at` 每请求写 | 写放大 | 5 分钟节流；只在命中时更新 |
| OIDC 回调 / token 处理错误 | 登录失败或 IdP token 进浏览器 | `oauth2Login` 只挂 Web 链；no-op `OAuth2AuthorizedClientRepository`；`OidcWebChainTest` + 手动 IdP 验证 |
| OIDC 自动建号滥用 | 垃圾账号 | 默认关闭；仅已验证邮箱；D2 反滥用统一处理 |
| 邮箱重置缺失 | 用户忘记密码无法自助 | C 由管理员重置并一次性发令牌；D2 接入邮件 |
| 最后一名 org_admin 被禁用 | 组织不可管理 | 服务层 409 保护 + 测试；平台恢复流程 D1 |
| Redis 限流不可用 | 登录暴力破解风险或登录全部失败 | 与 Redis 会话同一 HA；fail-closed；监控 Redis 连接与延迟 |
| MySQL 函数索引 / 时间精度差异 | 迁移失败 / 唯一约束失效 | V6/V7 分 vendor；双库迁移测试；`DATETIME(6)` + UTC |
| 前端跨域 Cookie | 登录态丢失 / CSRF 失效 | 推荐 Vite 代理同源；`VITE_API_BASE` 跨域需 CORS credentials + `SameSite=None; Secure`，文档标注 |
| Jackson 2 传递依赖 | 违反项目边界 | 只允许运行期库内部使用；`verifyNoJackson2Usage` + 依赖树核对 |
| 401/403 契约漂移 | 前端 / 测试中断 | 自定义 `AuthenticationEntryPoint` / `AccessDeniedHandler` 返回 `ApiErrorBody`；`SecurityConfigTest` 覆盖 |

---

## 8. 交付物清单

- 管理库 `V6__identity_and_audit.sql`、`V7__identity_tighten.sql`（PG/MySQL）与迁移矩阵测试；**不建会话表**。
- `UserToken` / `ServiceAccount` / `UserIdentity` / `AuditEvent` 领域层；不新增 `UserSession` 实体。
- Spring Security 双链（Bearer API / Web Session），**所有环境真实登录、无 dev 放行链**；`AppUserDetailsService`、`JsonUsernamePasswordAuthenticationFilter`、`BearerAuthenticationFilter` / `TokenAuthenticationProvider`、`OidcUserService`。
- **Spring Session Data Redis 在 dev / prod 从 C 阶段直接使用**（同一 namespace / TTL，只换连接串）；不引入 `spring-session-jdbc`，不做过渡。
- `RedisLoginRateLimiter`（C 阶段直接使用 Redis）。
- `AuthService` / `AuthzService` 适配 `SecurityContext`；`SessionAdminService`（Spring Session 列表 / 删除）。
- `UserTokenService`、`ServiceAccountService`、`AuditService`、`PasswordEncoder` / Argon2id。
- `AuthController` 扩展：密码登录 / 登出 / 改密 / 重置、用户生命周期、会话管理、令牌管理。
- `ServiceAccountController` 与跨项目隔离。
- OIDC `oauth2Login`、`user_identity` 绑定、配置与测试。
- `GET /api/audit` 与关键写点审计接入。
- 前端：`LoginDialog` 改造、`SecurityDialog`（含活动会话）、`ServiceAccountDialog`、`AuditDialog`、`api.ts` 会话 / CSRF 改造。
- 测试：`SecurityConfigTest` / `PasswordLoginApiTest` / `SessionApiTest` / `SessionAdminApiTest` / `ServiceAccountApiTest` / `OidcWebChainTest` 等 + `UserTokenServiceTest` 单测 + `ManagementDbMigrationTest` 扩展 + 现有鉴权 / 隔离测试改造。
- 文档：开发计划 P2.7、README、ADR §13 回填、持久化选型 Phase 6、运维手册、UI 方案、编码规范认证章节。
