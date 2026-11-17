# ADR-001 · 管理模型与多租户设计

> **日期**：2026-11-17　**状态**：已确认（Accepted）
> **范围**：管理域（组织 / 项目 / 团队 / 用户 / 角色 / 服务账号 / Git 接入）与多租户 SaaS 形态下的数据隔离。
> **不覆盖**：OpenAPI 解析、投影模型、mock 生成、导出格式；这些沿用现有设计。
> **关联**：[SpecHub-产品方案](SpecHub-产品方案.md) §5.3 / §6.3、[SpecHub-开发计划](SpecHub-开发计划.md) I4、[SpecHub-管理数据持久化最终选型结论](SpecHub-管理数据持久化选型.md)、[SpecHub-事务边界审计](SpecHub-事务边界审计.md)。

---

## 1. 背景

当前实现（P2 I4）只交付了**单项目 RBAC**：

- `project` 表实际只有写死的 `default`（`RepoController.DEFAULT_PROJECT`），仓库注册不接收项目参数；
- `membership` 表结构支持「用户 × 项目 × 角色」，但 API/UI 全部硬编码 `default`；
- 登录是「用户名 + 平台发放一次性 token」，没有密码、SSO、账号禁用、过期、服务账号；
- **读接口完全没有项目维度鉴权**：`GET /api/repos`、`/api/apis`、`/api/docs`、`/api/changelog`、`/api/reviews` 均返回全量数据；
- 没有团队/分组实体，订阅的 `team` 是自由文本；
- 没有项目管理（创建 / 改名 / 删除 / 归档 / 可见性）；
- 凭据 `UrlTokenStore` 是进程内全局 map，重启即丢，且无租户校验。

产品定位是 5–50 人研发团队，核心痛点包含「跨团队联调无主」「多仓汇入」「消费团队订阅」。单项目模型直接废掉这些叙事，因此本 ADR 一次性确定管理模型与多租户的边界，避免后续返工。

---

## 2. 决策摘要

| 决策项 | 结论 |
|---|---|
| 领域术语 | 业务隔离单元叫 **`org`（组织）**；`tenant` 只用于部署/计费语境，不建 `tenant` 表 |
| 分组 | 组织级团队建实体 **`team`**；项目内接口分组沿用 OpenAPI `tag`；**不建** `project_group` / `api_group` |
| 层级 | `org → project → repo`；`team` 挂在 `org` 下；`project.membership` 独立于 team |
| 角色 | 组织级 `org_admin / org_member`；项目级 `owner / admin / editor / viewer`；团队级 `lead / member`；另设服务账号 |
| 项目可见性 | `private` / `internal` / `public` 三档，默认 `private` |
| 读鉴权 | 所有列表、详情、搜索、导出按「租户 + 可见性 + stage」过滤；不可见资源统一返回 404 |
| 部署形态 | 单租户私有化为主形态；**数据模型从第一天带 `org_id`**，SaaS 不迁移 schema |
| Git 托管 | 分档默认：试用 / 小团队用平台托管仓，Business / Enterprise 以 BYO git 为主；内网 git 用出站 agent（企业版） |
| Git 接入模型 | `org → git_connection → repo` 三层 + **`repo_binding` 单主绑定**；凭据按 connection 加密存储，仓库用提供方稳定 ID 标识 |
| 真源绑定 | 每个逻辑仓库同一时刻只有一条 `active` binding；managed / external 可切换、不双写；镜像另建 `repo_mirror` |
| 仓库命名 | 对外 `<org-slug>/<project-slug>`，内部不可变 UUID；租户标识只做命名空间，不做仓库名 |
| 写入路径 | SaaS / 多租户默认 **PR-first**（开分支 + PR/MR），不直接 push 主干；直推为可选 |
| 可重建边界 | mirror cache、投影索引、导出产物可重建；**平台托管仓的真源、评审草稿、评论、订阅、mock 规则、changelog 必须按租户备份** |
| 投影索引 | **每租户一份 SQLite**（`index/<org_id>.db`），保持 FTS5 中文检索，天然隔离搜索 |
| 管理库 | 共享 schema + `org_id`；PostgreSQL 追加 RLS 纵深防御；MySQL 依赖应用层约束 |
| 认证 | Web 用 HttpOnly 会话 + OIDC 优先 / 密码兜底；CLI/MCP/CI 用可过期的项目级服务账号 |
| 平台运营 | 独立 `platform_operator` 控制面；默认只看组织元数据，读租户内容需 break-glass + 客户授权 + 审计；不做租户超级用户 |
| 组织开通 | 混合模式：Free/Trial 自助注册，Business/Enterprise 销售/平台开通；域名验证防重复组织 |
| 组织生命周期 | `pending_verification → active → suspended → archived → deleted`，状态变更审计 |
| break-glass | 标准访问：客户 `org_admin` 单人批准、24h 只读；紧急通道：平台双人审批、4h 只读 + 事后通知；永不提供写权限 |
| 自助注册开放 | D2 分 waitlist → 邀请 → 灰度 → 全量；按滥用率/验证率/支持负载/成本门槛放行，带 kill switch |
| Free / Trial | Trial 30 天免信用卡；Free 5 席位 / 3 项目 / 10 仓库 / 1 GB；超限停写保读，不删数据 |
| 同域名多组织 | 一个已验证域名一个顶层 org；同公司多部门用 team/project；独立法人需平台审批；域名加入默认 `approval` |

---

## 3. 术语与边界

### 3.1 `org` 与 `tenant`

| | `org`（组织） | `tenant`（租户） |
|---|---|---|
| 回答的问题 | 人/项目属于哪个业务组织 | 数据归谁付费、和谁隔离 |
| 单租户部署 | 有意义（一个公司一个 org） | 恒等于 1，无实体意义 |
| 多租户 SaaS | 可以是租户内子单元，也可以等于租户 | 根节点，必须强隔离 |
| 是否出现在 UI/API | 是 | 否 |

**决策**：领域表与 API 一律使用 `org` / `org_id`；`tenant` 只出现在部署与商务文档中（如「单租户部署」）。未来 SaaS 若需要独立计费单元，在其上增加 `tenant` 层，不反向重命名根节点。

### 3.2 「分组」的三种含义

| 含义 | 决策 | 理由 |
|---|---|---|
| 组织级团队（订阅/通知接收方） | ✅ `team` 实体 | 产品核心能力必需 |
| 多个项目归组（YApi 式） | ❌ 暂不做 | 现有 `项目 → 仓库 → 接口` 层级已够；项目数 > ~20 再补 |
| 项目内接口目录 | ❌ 不建实体，用 OpenAPI `tag` | 避免与 git 真源双写漂移；多 spec tag 不一致用摄取期归一化解决 |

### 3.3 层级

```
platform_operator（平台侧，跨 org，不属于任何组织）
──────────────────────────────────────────────
org（组织 / 未来租户内的业务单元）
 ├─ git_connection（Git 提供方 + 凭据）
 ├─ team（组织级团队）
 │    └─ team_member
 ├─ app_user（全局用户身份，可加入多个 org）
 ├─ org_membership（用户 × 组织）
 └─ project（项目空间）
      ├─ membership（用户 × 项目 × 角色）
      └─ repo（逻辑仓库，只表身份）
           ├─ repo_binding（真源绑定：active / candidate / retired）
           ├─ repo_mirror（可选镜像，非真源）
           ├─ specs / docs（git 真源）
           ├─ 投影索引（每租户 SQLite）
           └─ subscription（绑定 team）
```

### 3.4 平台运营 vs 组织管理员 vs 项目角色

| 角色 | 归属 | 管什么 | 默认可见内容 |
|---|---|---|---|
| `platform_operator` | SaaS 厂商 | 组织生命周期、套餐/配额、计费、滥用处置、支持 | 仅组织元数据 |
| `org_admin` | 客户组织 | 本组织用户、项目、Git 连接、SSO、审计 | 本组织 |
| `project owner/admin/editor/viewer` | 客户项目 | 项目成员、评审、发布 | 本项目 |

`platform_operator` 不进入 `membership`，不默认成为任何组织成员；需要读租户内容时走 break-glass（§7.5）。

---

## 4. 领域模型

### 4.1 表设计（示意，非最终 DDL）

```sql
-- 组织（status 见 §7.4；plan/quota 由平台运营维护）
org(id, name, slug, status, plan, trial_ends_at,
    quota_json, suspended_reason, created_at)

-- 组织域名认领（自助注册防重复组织）
org_domain(id, org_id, domain, verification_token, verified_at)

-- 平台运营（与租户用户分表；不进入 membership）
platform_operator(id, email, name, status, mfa_enabled, created_at)
platform_role(operator_id, role)  -- support | billing | admin | superadmin

-- 全局用户身份（阶段 A 沿用 username + token；email/password/mfa 阶段 C 引入）
app_user(id, username, email, display_name, password_hash, mfa_enabled,
         status, created_at)
-- UNIQUE (lower(username))
-- UNIQUE (lower(email))：阶段 C 起

-- 组织成员关系（一个用户可加入多个组织）
org_membership(id, user_id, org_id, org_role, status, created_at)
-- UNIQUE (user_id, org_id)
-- org_role ∈ org_admin | org_member

-- 邀请（组织成员）
invitation(id, org_id, email, role, token_hash, expires_at, created_at)

-- 平台支持访问（break-glass）与平台审计
support_access(id, org_id, operator_id, scope, reason,
               approved_by, expires_at, created_at)
platform_audit(id, operator_id, org_id, action, target, reason, created_at)

-- 项目空间
project(id, org_id, name, slug, description, visibility,
        owner_id, archived, created_at)
-- UNIQUE (org_id, slug)
-- slug：小写 [a-z0-9-]，长度 2–64，组织内唯一
-- visibility ∈ private | internal | public

-- 项目成员（现有表补 org/约束）
membership(id, user_id, project_id, role)
-- UNIQUE (user_id, project_id)

-- 组织级团队
team(id, org_id, name, description, created_at)
-- UNIQUE (org_id, name)
team_member(team_id, user_id, role)  -- lead | member
-- PK (team_id, user_id)

-- Git 接入
git_connection(id, org_id, provider, external_account_id,
               display_name, credentials_enc, webhook_secret_enc,
               status, created_at)
-- provider ∈ github | gitlab | gitea | ssh | local | managed

-- 逻辑仓库：只表身份，不表托管位置
repo(id, org_id, project_id, name, created_at)
-- UNIQUE (project_id, name)

-- 真源绑定：同一 repo 至多一条 active
repo_binding(id, repo_id, kind, connection_id,
             external_repo_id, remote_url, branch,
             status, created_at, retired_at)
-- kind ∈ managed | external
-- connection_id 阶段 A 可空（managed/local 不强制外部 connection）
-- status ∈ candidate | active | retired
-- 约束：UNIQUE (repo_id) WHERE status='active'
--       （PG 部分索引；MySQL 用可空标志位 + 唯一索引）

-- 可选镜像（D1 之后，非真源、只写不读）
repo_mirror(id, repo_id, connection_id, external_repo_id,
            direction, mode, status)

-- 服务账号（CI / MCP / CLI）
service_account(id, org_id, project_id, name, token_hash,
                role, status, created_at)

-- 订阅绑定团队
subscription(id, repo_id, method, path, team_id, notify_url, created_at)
```

**租户冗余规则**：`repo` 之后的租户数据表（`comment`、`subscription`、`review`、`mock_rule`、`changelog`、`notification`、`release`）**冗余 `org_id` 列**。理由：列表查询与 RLS 都直接按 `org_id` 过滤，避免每次 join 到 `project`；写入端由 Service 统一回填并在事务内校验一致性。

**唯一键规则**：逻辑仓库用 `(project_id, name)`；真源绑定用 `(connection_id, external_repo_id)`（提供方稳定 ID：GitHub `repository.id`、GitLab `project.id`、Gitea `repo.id`）；URL 只用于展示，禁止用 URL 做唯一键。

### 4.2 角色与职责

| 角色 | 范围 | 能力 |
|---|---|---|
| `org_admin` | 组织 | 组织设置、用户/服务账号、创建/归档项目、Git 连接与 binding 迁移、审核与审计；现有 `platform_admin` 映射为此角色 |
| `org_member` | 组织 | 读取组织内 `internal` 项目的 release；项目内能力由项目角色决定 |
| `project owner` | 项目 | 项目设置、可见性、成员、转移 owner、删除/归档项目、真源 binding 切换/回滚 |
| `project admin` | 项目 | 成员管理、评审审批、mock 规则、promote |
| `project editor` | 项目 | 写回 spec、注册仓库、提交评审、执行 promote |
| `project viewer` | 项目 | 读取 draft/release、评论、订阅 |
| `team lead` | 团队 | 团队成员、订阅与通知渠道 |
| `team member` | 团队 | 查看团队通知 |
| 服务账号 | 项目 | 按 `viewer` / `editor` 限权，供 CI/MCP/CLI 使用，不可登录 Web |
| `platform support` | 平台 | 组织元数据、工单、发起 break-glass 申请 |
| `platform billing` | 平台 | 套餐、配额、账单、欠费暂停/恢复 |
| `platform admin` | 平台 | 创建/暂停/归档/删除组织、功能开关、数据导出/删除（审批 + 审计） |
| `platform superadmin` | 平台 | 运营账号管理、紧急通道（双人审批）；不默认读租户内容 |

`owner` 是项目级最高角色，仅 `owner` 或 `org_admin` 可转移；当前代码中 owner 与 admin 能力无差异的问题在本 ADR 生效后修正。平台运营账号不属于任何组织，不参与项目角色判定。

### 4.3 可见性矩阵

| 项目可见性 | 项目成员 | 同组织非成员 | 匿名 |
|---|---|---|---|
| `private` | draft + release | 不可见 | 不可见 |
| `internal` | draft + release | **仅 release** | 不可见 |
| `public` | draft + release | release | release（仅门户/导出/只读接口） |

补充约束：

- `internal` / 匿名视图**不含** draft、评论、评审、mock 规则、变更通知。
- 不可见资源统一返回 **404**（不是 403），避免暴露资源是否存在。
- `/mock/{repoId}/**` 在 SaaS 下不再全局放行：仅 `public` 项目匿名可用，或携带项目级 mock key / 服务账号。
- dev 环境（`authEnabled=false`）保留免登录放行，但**SaaS 档禁止关闭鉴权**。

### 4.4 逻辑仓库与单主绑定（single-primary binding）

`repo` 只表达身份；内容实际存放的位置由 `repo_binding` 表达。平台所有功能（评论、订阅、评审、索引、mock）只认 `repo_id`，不感知托管方式。前一版讨论中称「双 binding」，这里统一为 **single-primary binding**：managed / external 两种类型 + 可切换，**不是两个同时生效**。

| 概念 | 作用 | 数量 |
|---|---|---|
| `repo` | 逻辑仓库身份（org / project / name） | 1 |
| `repo_binding` | 真源位置 + 凭据引用 + 分支 | 多条，但只有一条 `active` |
| mirror cache | 平台本地 clone，用于摄取/索引；可重建 | 每个 repo 一份，不是 binding |

状态机：

```text
candidate ──校验通过后 flip──▶ active ──retire──▶ retired
                                 ▲                  │
                                 └──────回滚─────────┘
```

规则：

- 同一 `repo` 同一时刻**至多一条 `active`**，由数据库唯一约束保证（PG 部分索引；MySQL 可空标志位 + 唯一索引）。
- `active` 是唯一可读写的真源；`candidate` 只用于迁移校验/回填，不对外服务；`retired` 不可写、不可读，只保留回滚窗口。
- 读 / 摄取从 `active` clone/fetch 到 mirror cache；写只写 `active`；Webhook 只接受 `active` binding 对应仓库的事件。
- `active` 不可用时，读可回退到上一次 mirror cache 并标记 stale，写直接失败；**不自动切换**到 `retired`，避免静默漂移。
- 每次写记录 `binding_id` + commit，迁移后仍可追溯。

迁移流程（managed → external 或反向）：

1. 创建 `candidate` binding；
2. clone 候选并与 `active` 比对：空 → 推送当前内容；一致 → 继续；不一致 → 展示 diff，要求显式选择以哪边为准；
3. 事务内 flip：旧 `active` → `retired`，`candidate` → `active`；
4. 全量重建索引；
5. 旧 binding 保留 N 天做回滚，之后删除。

**不做双主**：双写会引入历史分叉、冲突处理、重复 Webhook、双攻击面与审计失真。若客户要求「两边都有」，用单向 `repo_mirror`（primary → mirror，只写不读，失败不阻塞主流程），不要把 mirror 建成 binding。

---

## 5. Git 与多租户

### 5.1 托管形态

| 形态 | 说明 | 决策 |
|---|---|---|
| **A. BYO git** | 客户自己的 GitHub / GitLab / Gitea，平台通过 App/OAuth 接入 | ✅ Business / Enterprise 默认；核心生产通道 |
| **B. 平台托管 git** | 平台代管（Gitea/Forgejo 的 per-tenant organization） | ✅ 试用 / 小团队 / 无 git 团队默认；不作为所有租户的唯一默认 |
| **C. 内网 git + 出站 agent** | 客户内网 GitLab，SaaS 不可达，客户侧 agent 只出站同步 | ⏳ 企业版 |

**理由**：产品叙事是「契约在客户自己的 git 里」，主推形态是 spec 跟代码同仓、随 PR 提交。平台托管仓解决的是「没有 git 托管 / 不想先接账号」的零门槛起步，**不是**让所有租户把契约搬进平台——否则就退化成产品方案明确不主推的「独立文档仓」（形态 A）。两种托管共用 `repo_binding`，随时可迁移。

### 5.2 凭据与身份

- `git_connection` 是凭据的唯一归属：一个连接代表一个 Git 提供方账号/安装，底下多个 `repo_binding` 共享凭据；轮换一次全生效，撤销即全部失效。
- 凭据挂在 connection，binding 引用 connection，`repo` 不直接挂凭据。
- 优先 **GitHub App / GitLab OAuth**（可撤销、细粒度、短期 token）；PAT / SSH deploy key 只做兜底。
- 凭据与 `webhook_secret` 使用 **KMS 信封加密**（每租户 DEK + 主密钥）；自托管部署用配置文件/环境变量提供主密钥；内存中解密，不写日志。
- 仓库身份在 binding 上用 `(connection_id, external_repo_id)` 标识；`repo` 只持有逻辑名称，URL 只用于展示。
- 对外命名用 `<org-slug>/<project-slug>`，内部镜像路径用不可变 `org_uuid/repo_uuid`；租户标识只做命名空间，不做仓库名；改名走 slug redirect。
- **禁止**跨租户共享 connection；一个逻辑仓库只能归属一个项目、一个组织。

### 5.3 隔离清单

| 子系统 | 规则 |
|---|---|
| 镜像目录 | `mirrors/<org_id>/<repo_id>`；路径只用内部 ID 拼，禁止用 URL 或用户输入 |
| git 子进程 | 参数数组调用、`GIT_TERMINAL_PROMPT=0`、禁用 hooks、禁用共享 alternates、禁用 `file://`、分支名白名单 |
| Webhook 入站 | 每 connection 独立 secret；先验签，再按 `external_repo_id` 定位租户与仓库；不信任 payload 内的租户标识 |
| 摄取任务 | 每租户独立队列/并发上限/退避；出站做 SSRF 防护（禁止解析到内网地址，C 形态除外） |
| 投影索引 | 每租户一份 SQLite：`index/<org_id>.db`；搜索请求必须绑定 org，禁止跨库检索 |
| 产物/导出 | 对象存储前缀 `org/<org_id>/`；下载用短期签名 URL；公开门户用租户子域或不可枚举随机路径 |
| 审计 | 所有 git 操作、摄取、凭据使用携带 `org_id`、`actor`、`repo_id`；日志脱敏 |

### 5.4 写入路径：PR-first

多租户 SaaS 下，平台直接 push 客户主干风险高（分支保护、合规、最小权限）。默认流程：

1. 平台创建分支 `spechub/<user>/<timestamp>-<slug>`；
2. 提交作者为**租户级 bot**，提交信息带 `Co-authored-by: <真实用户>` 与 `SpecHub-Org / SpecHub-Review` trailer；
3. 开 PR/MR，链接写入 `review` 记录；
4. 客户 CI / 分支保护 / 人工评审照常生效；
5. 合并事件经 webhook 回流，平台重新摄取。

直推仅在客户明确授予分支保护豁免时启用。这样平台**永远不需要写主干权限**，接入权限清单更小。

### 5.5 生命周期与可重建边界

| 数据 | 可重建 | 备份 |
|---|---|---|
| mirror cache（任意 binding） | ✅ 从 `active` binding 重新 clone | 不备份 |
| **平台托管仓（managed 真源）** | ❌ 平台内唯一副本 | **必须快照备份 + 异地副本** |
| external 真源 | 由客户托管 | 平台不备份，仅保留 mirror cache |
| 投影索引 | ✅ 全量重扫 | 不备份 |
| 导出产物 | ✅ 重新生成 | 不备份 |
| Git 凭据 | ⚠️ 客户可重填 | 加密备份 |
| 评审草稿、评论、订阅、mock 规则、changelog、release 标记 | ❌ 只在管理库 | **按租户备份/导出** |

租户注销：撤销 connection → 删 mirror cache/索引/产物 → 删管理库租户数据 → 出导出包（含 managed 仓的 `git bundle`）。**external 真源的历史平台删不掉**，此边界必须写入 DPA/条款；managed 真源由平台删除，但必须先给出导出窗口。

### 5.6 平台托管 git（形态 B）

- 共享 Gitea + **每租户一个 organization namespace** + 每租户独立 service account token；
- 仓库路径与权限按 org 隔离；企业客户可申请独立 Gitea 实例（物理隔离）；
- 平台托管 git 同样走 `git_connection` 模型，只是 provider 为 `managed`（后端用 Gitea/Forgejo 的 organization namespace 承载）。

### 5.7 内网 git（形态 C）

- 客户侧部署出站 agent：只主动连接 SaaS，拉取任务、同步 mirror、回传索引事件；
- SaaS 不直接访问客户内网，避免 VPN/防火墙穿透；
- agent 使用项目级服务账号，凭据可随时吊销。

---

## 6. 读鉴权与数据隔离

### 6.1 统一判定链

所有请求按以下顺序判定：

1. 解析身份（会话 / API token / 服务账号）；
2. 解析 `org` 上下文（SaaS 下由 token/域名确定；单租户恒为默认 org）；
3. 解析目标资源所属 `project`；
4. 判定：`org_admin` → 通过；项目成员 → 按角色；非成员 → 按 `visibility` + `stage`；
5. 不可见 → 404；可见但权限不足 → 403。

### 6.2 必须改造的读端点

| 端点 | 现状 | 目标 |
|---|---|---|
| `GET /api/projects` | 全量 | 仅返回当前用户可见的项目 |
| `GET /api/repos` | 全量 | 按可见项目过滤 |
| `GET /api/apis` | 全量 | 按可见项目 + stage 过滤；搜索不得跨租户 |
| `GET /api/apis/{id}`、`/api/repos/{id}` | 无项目校验 | 按资源所属项目判定 |
| `GET /api/docs`、`/api/docs/{id}` | 全量 | 按可见项目过滤 |
| `GET /api/changelog` | 全量 | 按可见项目过滤 |
| `GET /api/reviews` | 按状态/仓库，无项目校验 | 按可见项目 + 角色过滤 |
| `GET /api/repos/{id}/export/*` | 无项目校验 | 按可见性与 stage 判定 |
| `/mock/{repoId}/**` | 全局放行 | public 项目匿名可用；否则需 mock key/服务账号 |

`GET /api/auth/users` 改为组织维度：`org_admin` 看全组织；项目 `admin+` 通过项目成员端点看本项目成员。

### 6.3 数据库层防线

- 管理库所有租户表带 `org_id`；应用层每个事务设置当前 org 上下文。
- **PostgreSQL** 追加 RLS（Row Level Security）作为纵深防御；**MySQL** 无 RLS，依赖应用层统一入口与查询约束。
- 投影索引每租户一库，从物理上杜绝跨租户搜索。
- 禁止提供「全局搜索/全局列表」类跨租户 API；如未来需要平台运维视图，走独立的、强审计的内部接口。

---

## 7. 账号体系与平台运营

### 7.1 租户账号

| 能力 | 现状 | 目标 |
|---|---|---|
| 登录 | 用户名 + 一次性 token | Web：HttpOnly 会话 + OIDC 优先 / 密码兜底 |
| 密码 | 无 | Argon2id；首次登录/邀请设置 |
| 账号状态 | 无 | `active / disabled / invited`；禁用立即生效 |
| token | 永不过期、无范围 | 带前缀、可过期、可吊销；Web 不再把长期 token 放 localStorage |
| SSO | 无 | OIDC 优先；SAML/SCIM 视客户需求 |
| 服务账号 | 无 | 项目级、`viewer/editor`、不可登录 Web；供 CI/MCP/CLI |
| 审计 | changelog 部分覆盖 | 管理动作统一带 `org_id/actor/action/target`；后续可独立 `audit_event` 表 |

自托管部署保留 `spechub.admin-token` bootstrap 默认 org 的 `org_admin`；**SaaS 档禁止关闭鉴权**，首个管理员通过 OIDC 或邀请创建。

### 7.2 平台运营控制面

平台运营账号与租户用户**分表、分入口、分会话**：

- 独立登录（平台 IdP / 邮箱 + 强制 MFA），可选 IP 白名单；
- 默认只返回组织元数据（名称、套餐、用量、状态、成员数），不返回 spec / 评论 / 评审内容；
- 不复用租户 token，不进入 `membership`，不作为任何组织的成员；
- 所有操作写 `platform_audit`（操作者、组织、动作、原因、时间）；
- 平台控制面与租户数据面可以同部署，但必须独立路由、独立权限判定、独立审计；规模上来后可拆独立服务。

权限分层：

| 角色 | 能力 |
|---|---|
| `platform support` | 查看组织元数据、工单、发起 break-glass 申请 |
| `platform billing` | 套餐、配额、账单、欠费暂停/恢复 |
| `platform admin` | 组织创建/暂停/归档/删除、功能开关、数据导出/删除（审批 + 审计） |
| `platform superadmin` | 运营账号管理、紧急通道（双人审批） |

### 7.3 组织开通：混合模式

| 档位 | 开通方式 | 说明 |
|---|---|---|
| Free / Trial | 自助注册 | 邮箱验证 + 域名认领 + 默认配额 |
| Team | 自助注册 + 自助付费 | 可转销售辅助 |
| Business / Enterprise | 销售 / 平台开通 | 合同、SSO、专属实例、DPA |

自助注册流程：

1. 邮箱/OIDC 注册 → 邮箱验证；
2. 创建组织（名称、slug）→ 接受条款/DPA；
3. 域名认领（DNS TXT 或邮箱域名验证）：同域名已有组织时，走「加入已有组织」申请，而不是重复创建；
4. 邀请成员（`invitation`）；
5. 创建首个项目 + 选择 Git 托管（`managed` 或连接外部）；
6. 进入 Free/Trial 配额与试用期。

反滥用：邮箱验证、一次性邮箱黑名单、注册限流/验证码、默认配额、付费档账单验证、高风险注册人工审核；平台可暂停/归档组织。

**同域名策略（已确认）**

| 场景 | 处理 |
|---|---|
| 同一公司多个部门 | 一个 org + 多个 team/project，不建多个 org |
| 子公司 / 独立法人 / 不同合规边界 | 企业版允许 sub-org 或第二个 org，平台审批 + 审计 |
| 代理商 / 咨询公司服务多个客户 | 按客户域名建多个 org；一个用户可加入多个 org |
| 同域名新注册 | 进入「加入已有组织」流程，不重复建 org |
| 域名认领 | DNS TXT 优先，邮箱域名兜底；争议走 support + 证明材料 |

组织的域名加入策略：`open`（自动加入）/ `approval`（申请审批，默认）/ `closed`（仅邀请）。

### 7.4 组织生命周期

```text
pending_verification → active → suspended → archived → deleted
                         ▲          │
                         └── resume ┘
```

- `pending_verification`：等待邮箱/域名验证，不能邀请成员或写数据；
- `active`：正常使用；
- `suspended`：保留数据，停写/停摄取（欠费、滥用、安全事件）；
- `archived`：只读，可导出；
- `deleted`：宽限期后删除；删除前必须 `archived`，且由 `org_admin` / owner 二次确认；保留导出包与审计记录；
- 状态变更需 `platform_operator`（或 `org_admin` 发起删除）并写审计。

### 7.5 break-glass 支持访问

平台运营默认**看不到租户内容**。需要排障时按两档处理：

| 场景 | 审批 | 时限 | 权限 |
|---|---|---|---|
| 标准支持访问 | 客户 `org_admin` **单人批准** | 24h，可续一次 | 只读、限定范围 |
| 紧急通道（客户不可达 / 安全事件） | 平台 **双人审批**（`platform admin` + `platform superadmin`） | 4h | 只读、限定范围 |

规则：

- 必须关联工单号、原因、范围（哪个组织、哪类资源）；
- 紧急通道事后 24h 内通知客户 `org_admin`；
- 到期自动失效，客户可提前撤销；
- 全程写 `platform_audit`，客户审计页可见；
- Enterprise 可合同约定「紧急通道也必须客户批准」；
- **永不提供写权限，不做 impersonation**；如必须复现，用只读会话 + 客户共享屏幕/临时凭据。

### 7.6 Free / Trial 配额与到期行为

| 资源 | Free | Trial（30 天） | Team（参考） |
|---|---|---|---|
| 席位 | 5 | 20 | 按席位付费 |
| 项目 | 3 | 10 | 20 |
| 仓库 | 10 | 50 | 100 |
| API 数 | 1,000 | 5,000 | 20,000 |
| Git 存储 | 1 GB | 10 GB | 50 GB |
| 导出产物 | 1 GB | 10 GB | 50 GB |
| Mock 请求 | 10k/日 | 100k/日 | 1M/日 |
| 评论/通知保留 | 90 天 | 1 年 | 1 年 |
| SSO / 审计导出 | ❌ | ❌ | Business 起 |

- Trial 30 天、无需信用卡，给接近 Team 的能力（见上表）；可申请延长一次 14 天；
- 80% 软提醒，100% **停写保读**，不删数据；
- Trial 到期 → 降级 Free（只读 30 天）→ 归档；
- 配额按组织统计，提供用量面板；
- 不把 API 数作为主要限制手段；控成本靠席位 / 仓库 / 存储 / 摄取频率。

---

## 8. API 变更

### 8.1 新增

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST/GET/PATCH/DELETE` | `/api/projects`、`/api/projects/{id}` | 项目 CRUD、归档、可见性 |
| `POST` | `/api/projects/{id}/transfer` | 转移 owner |
| `GET/POST/PATCH/DELETE` | `/api/projects/{id}/members` | 多项目成员管理 |
| `GET/POST/PATCH/DELETE` | `/api/teams`、`/api/teams/{id}/members` | 团队与成员 |
| `GET/POST/PATCH/DELETE` | `/api/git-connections`、`/api/git-connections/{id}` | Git 接入与凭据 |
| `POST` | `/api/git-connections/{id}/test` | 连通性/权限校验 |
| `GET/POST` | `/api/repos/{id}/bindings` | 列出绑定 / 创建 `candidate` binding |
| `POST` | `/api/repos/{id}/bindings/{bindingId}/verify` | 与当前 `active` 比对内容并返回 diff |
| `POST` | `/api/repos/{id}/bindings/{bindingId}/activate` | flip 为 `active`（project owner / org_admin） |
| `POST` | `/api/repos/{id}/bindings/{bindingId}/retire` | 退休当前 `active`（须先 flip 到新 active） |
| `DELETE` | `/api/repos/{id}/bindings/{bindingId}` | 删除已 `retired` 的绑定（回滚窗口结束后） |
| `GET/POST/DELETE` | `/api/service-accounts` | 服务账号 |
| `GET` | `/api/audit` | 管理动作审计（Phase C） |
| `GET/POST/PATCH` | `/api/platform/orgs`、`/api/platform/orgs/{id}` | 平台运营：组织列表/创建/暂停/恢复/归档/删除 |
| `PATCH` | `/api/platform/orgs/{id}/plan` | 套餐、配额、试用期 |
| `POST/GET` | `/api/platform/orgs/{id}/support-access` | 发起/查看 break-glass 授权 |
| `POST` | `/api/orgs/{orgId}/support-access/{id}/approve` | 客户 `org_admin` 批准（租户侧入口） |
| `GET` | `/api/platform/audit` | 平台操作审计 |
| `POST` | `/api/signup` | 自助注册：邮箱验证 + 创建组织 |
| `POST` | `/api/orgs/{id}/domains` | 域名认领与验证 |
| `POST` | `/api/invitations` | 邀请组织成员 |

`/api/platform/**` 使用独立运营身份与会话，不复用租户 token；租户侧只能访问自己的 `/api/orgs/{id}/...` 端点。

### 8.2 修改（不兼容）

| 端点 | 变更 |
|---|---|
| `POST /api/repos` | 必须携带 `projectId`；注册时创建逻辑仓库 + 首个 `active` binding（`managed` 或 `external`） |
| `POST /api/subscriptions` | `team`（自由文本）改为 `teamId` |
| `GET /api/auth/users` | 组织维度；项目管理成员走项目端点 |
| 全部列表/详情/导出 | 加租户 + 可见性过滤；不可见返回 404 |
| `/mock/{repoId}/**` | 不再全局放行（dev 除外） |

兼容策略：旧的 `team` 字符串在迁移期自动映射为同名 team；旧 admin-token 继续可用；`DEFAULT_PROJECT` 变为 bootstrap 创建的默认项目，不再硬编码在写路径。

---

## 9. 实施阶段

| 阶段 | 目标 | 关键交付 | 退出标准 |
|---|---|---|---|
| **A · 多项目骨架** | 把单项目 demo 变成多项目平台 | `org` + `org_membership` + `project` CRUD + `visibility` + 所有读端点过滤 + 多项目成员 API/UI + 仓库注册带 `projectId` + 表加 `org_id` + `git_connection` + **`repo_binding`（managed/local，`connection_id` 可空，单主约束）**；email/mfa/密码留阶段 C | 两个项目、用户只在 A：列表/详情/搜索/导出均看不到 B；注册仓库归属正确；每个 repo 恰好一个 active binding；dev 免登录仍可用 |
| **B · 团队** | 跨团队订阅落地 | `team` + 成员 + `subscription.team_id` 迁移 + 团队管理页 + 通知按团队投递 | 团队改名不影响订阅；通知只发给团队成员 |
| **C · 账号与审计** | 可运维的身份体系 | 密码/OIDC、账号禁用、token 过期、服务账号、管理动作审计查询 | 禁用用户所有 token 立即失效；服务账号无法跨项目访问 |
| **D1 · 平台控制面** | 可售卖、可运营 | 平台 operator 控制面（组织开通/暂停/配额/审计）、销售/邀请制开通、break-glass、RLS、每租户索引/存储、BYO git 全连接器 + **binding 迁移（candidate/verify/flip/回滚）**、PR-first、可选 `repo_mirror`、租户生命周期与导出 | 运营可开通/暂停组织；operator 默认看不到租户内容；break-glass 有授权与审计；两个 org 在 DB/搜索/存储/Webhook 全隔离 |
| **D2 · 自助注册** | 降低获客成本 | 邮箱验证、域名认领、反滥用、Free/Trial 配额、自助付费 | 自助注册可创建组织；同域名重复注册走加入已有组织；异常注册可限流/暂停 |

D1 先于 D2：反滥用能力（验证、配额、暂停）先就位，再开放自助注册。

阶段 A 的 `org` 只有一行（默认组织），不引入组织切换 UI；但所有表与服务签名从第一天携带 `org` 上下文，避免 D1/D2 返工。

### 9.1 D2 开放门槛

D1 GA 后至少观察一个完整计费周期，再开始 D2 灰度：

```text
waitlist（申请制） → 邀请 50 家 → 灰度 10% → 50% → 100%
```

每步观察 2 周；指标不达标就停在当前阶段。建议 D1 GA 后 4–8 周再开闸。

| 指标 | 阈值 |
|---|---|
| 新组织被暂停/滥用比例 | < 2% |
| 邮箱验证通过率 | > 80% |
| 每个新组织的支持工单 | < 0.5 |
| Free 单组织月基础设施成本 | 由财务定目标 |
| 注册到首个项目 P95 | < 10 分钟 |

必须提供 **kill switch**：关闭新注册不影响已有组织。

---

## 10. 迁移

1. 启动时创建默认 `org`（名称可配置，默认 `default`）：自托管部署下把现有 `platform_admin` 映射为默认 org 的 `org_admin`；**SaaS 不把租户用户映射为 `platform_operator`**，运营账号独立创建。
2. 现有 `app_user` 就地升级为全局身份 + 新增 `org_membership`：
   - 当前 `app_user` 已是全局（无 `org_id`，`username` 全局唯一），阶段 A 保留 `username` + `token_hash`；
   - `email` / `password_hash` / `mfa_enabled` 留到阶段 C 引入，不在 A 迁移；
   - 新增 `org_membership(user_id, org_id, org_role)`：所有现有用户加入默认 org，`is_platform_admin=1` → `org_admin`，其余 → `org_member`；
   - `app_user.is_platform_admin` 在阶段 A 保留以兼容旧 admin-token，阶段 C 迁移到 `org_membership.org_role` 后移除；
   - token hash 原样保留，迁移后旧 token 继续有效。
3. 现有 `default` 项目保留，补齐 `org_id`、`slug=default`、`visibility=private`、`owner_id`（admin 用户）。
4. 为现有 `repo` 创建首个 `active` binding：阶段 A 先给 `managed` / `local` binding，`connection_id` 允许为空；`external_repo_id` 暂用 URL 的确定性 hash；`repo` 只保留逻辑名称，托管位置（connection、URL、branch、external ID）迁入 `repo_binding`。
5. `subscription.team` 迁移为 `team` 行 + `team_id`；同名合并。
6. `review`、`comment`、`mock_rule`、`changelog`、`notification`、`release` 补 `org_id`。
7. 所有迁移按现有纪律分 vendor（PostgreSQL / MySQL）维护 Flyway；不破坏现有数据，不做重置。

---

## 11. 影响与权衡

### 11.1 正向

- 产品叙事（多仓、跨团队、订阅、发布快照）真正成立。
- 单租户与 SaaS 共用一套模型，SaaS 只是多几行 org，不需要 schema 迁移。
- 平台托管与 BYO 共用 `repo_binding`，客户可随时迁移，锁定风险可控。
- 混合开通覆盖自助获客与企业销售两条路径；平台运营与租户数据面分离，满足企业安全评审。
- git 始终是租户资产，平台可退出、可重建，降低客户信任门槛。
- 每租户 SQLite 天然隔离搜索，避免最易漏的跨租户泄露。

### 11.2 成本与风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 读接口过滤遗漏 | 跨项目/跨租户越权 | 统一鉴权入口 + 集成测试覆盖所有列表/详情/搜索/导出；PG 加 RLS |
| 凭据泄露 | 客户 git 被读写 | KMS 信封加密、最小权限 App/OAuth、轮换、审计 |
| 每租户 SQLite 运维复杂度 | 租户多时文件与连接数膨胀 | 连接缓存 + 空闲回收；设定租户数/数据量拐点，超限迁移到共享 PG |
| PR-first 增加复杂度 | 合并回流、冲突处理 | webhook 监听 merge；冲突时回到评审队列；直推作为可选 |
| `org_id` 冗余列不一致 | 数据错乱 | Service 单写入回填 + 事务内校验 + 唯一约束 |
| binding flip 期间读写不一致 | 索引/缓存短暂不一致 | flip 事务 + 全量重建 + 旧 binding 回滚窗口；candidate 不对外服务 |
| 把镜像误当 binding 双写 | 历史分叉、审计失真 | 数据库唯一约束保证单 active；镜像走独立 `repo_mirror`，单向只写 |
| 平台托管仓成为真源 | 备份/恢复/合规责任上升 | 快照备份 + 异地副本 + 恢复演练 + 加密 + 配额 + `git bundle` 导出 + DPA |
| MySQL 无 RLS | 隔离依赖应用层 | 统一 Repository 入口强制注入 org 条件；CI 加跨租户用例 |
| 平台运营账号被攻破 | 跨租户操作风险 | 独立 IdP + 强制 MFA + 最小权限 + 双人审批 + 审计 + 可选 IP 白名单 |
| break-glass 滥用 | 客户内容泄露 | 客户授权、限时只读、横幅、通知、全量审计 |
| 自助注册滥用 | 资源消耗 / 垃圾组织 | 邮箱/域名验证、限流、默认配额、人工审核、暂停 |
| 域名抢注 / 重复组织 | 同一公司多个组织 | 域名认领 + 加入已有组织流程 |
| 组织管理员流失 | 组织无法管理 | 平台辅助所有权恢复（审批 + 审计） |

---

## 12. 备选方案与拒绝理由

| 备选 | 拒绝理由 |
|---|---|
| 根节点命名为 `tenant` | 单租户部署下无意义；层级只能向上加，反向改名成本高；`tenant` 隐含强隔离承诺 |
| `project_group` 项目分组 | 现有层级已够；5–50 人团队项目数有限 |
| 自建 `api_group` 表 | 与 OpenAPI `tag` 双真源，必然漂移 |
| 全局 SQLite + `tenant_id` | 搜索最容易漏过滤；FTS 跨租户风险高 |
| 默认直接 push 主干 | 分支保护/合规/最小权限都过不去 |
| 平台托管 git 作为所有租户的唯一默认 | 退化为「独立文档仓」，与「spec 跟代码同仓」主推路径冲突 |
| 双主 / 双写 binding | 历史分叉、冲突处理、重复 Webhook、双攻击面、审计失真 |
| 不设平台运营角色 | 无法暂停滥用/管配额/审计，运营靠脚本 |
| 平台运营作为租户超级用户 | 跨租户数据风险；合规不可接受 |
| 纯自助注册 | 企业无法销售/开通；滥用成本高 |
| 纯销售开通 | PLG 获客成本高；小团队流失 |
| 平台运营 impersonation（可写登录） | 责任与审计不可控；只做只读 break-glass |
| 用租户 ID 直接做仓库名 | 多项目冲突、改名困难、自增 ID 可枚举；改用 slug 命名空间 + 不可变 UUID |
| 自研 git 服务（裸仓裸接 SSH/HTTP） | 安全面大、低收益；用 Gitea/Forgejo |
| 全局 bot token 服务所有租户 | 无法归因、无法最小权限、泄露即全局沦陷 |
| URL 作为仓库唯一键 | URL 可变、大小写/重定向问题 |
| Web 长期 token 放 localStorage | XSS 即失窃；改为 HttpOnly 会话 + 短期 token |

---

## 13. 开放问题

1. `visibility` 三档是否足够？`public` 是否需要独立域名/门户与备案要求。
2. 服务账号是否提前到阶段 A（CI/MCP 现在就需要项目级凭据）。
3. 每租户 SQLite 与共享 PostgreSQL 的切换拐点（租户数、索引大小、并发）。
4. RLS 是否在阶段 A 就上，还是 D1；MySQL 客户的隔离承诺如何表述。
5. SSO 优先级：OIDC / SAML / SCIM；是否需要多 IdP。
6. `repo_mirror` 是否在 D1 提供，还是更晚；镜像失败是否告警。
7. binding flip 的回滚窗口时长（建议 30 天）。
8. 平台托管 git 的定价与数据责任（备份、SLA、DPA）。
9. 计费系统选型（Stripe / 国内支付）与 N2 的边界。
10. 数据驻留区域与跨境合规。
11. UI 文案：`team` 对外叫「团队」还是「分组」。
12. 平台运营登录使用哪个 IdP、是否独立域名、是否要求 IP 白名单。

---

## 14. 非目标

- 细粒度资源级 ACL（项目角色 + 可见性已够）。
- `project_group`、`api_group`。
- 多区域双活、跨区域复制。
- 公开的匿名试用（不验证邮箱即可用）——D2 自助注册仍要求邮箱/域名验证。
- 同域名多个顶层组织（企业审批的 sub-org 除外）。
- 平台运营 impersonation（可写登录）。
- 计费引擎本身（N2 单独设计）。

---

## 15. 验收标准

### 阶段 A

- 创建项目 A/B；用户 `u` 仅加入 A：
  - `GET /api/projects` 只返回 A；
  - `GET /api/repos`、`/api/apis`、`/api/docs` 不出现 B 的数据；
  - `GET /api/apis/{id}`、`/api/repos/{id}`、导出 B 的资源返回 404；
  - 搜索关键词在 B 内命中也不返回；
  - 写 B 的资源返回 404/403。
- `POST /api/repos` 带 `projectId=B` 时仓库归属正确。
- 每个 repo 恰好一个 `active` binding；数据库拒绝第二条 active。
- `managed` 注册自动创建 `active` binding；`retired` binding 不可读写。
- `internal` 项目：非成员登录后只能读 release，读不到 draft/评论。
- dev 免登录模式行为不回归。

### 阶段 B

- 创建团队、加成员、绑定订阅；团队改名后订阅仍有效；通知只投递给团队成员。

### 阶段 C

- 禁用用户后其会话与 token 立即失效；服务账号只能访问所属项目；密码/OIDC 登录可用。

### 阶段 D1

- 两个 org 在管理库、搜索索引、对象存储、Webhook 路由上完全隔离；
- 跨 org 的 repo ID / webhook / 凭据使用一律拒绝；
- 连接外部仓创建 `candidate`；verify 返回一致/差异；flip 后 `repo_id`、评论、订阅、评审不变；旧 binding 可回滚；
- `candidate` 存在期间不对外服务；并发 flip 只有一个成功（409）；
- `platform support` 只能看到组织元数据，无法通过平台 API 读取 spec/评论/评审内容；
- `platform admin` 可创建/暂停/恢复/归档组织；暂停后租户写与摄取全部拒绝；
- break-glass：无客户批准时内容接口 403；标准访问客户 `org_admin` 单人批准、24h 只读；紧急通道平台双人审批、4h 只读 + 事后通知；到期自动失效；审计可查；任何支持会话不可写；
- 租户注销后平台侧无镜像、索引、产物、管理数据残留（external 客户 git 除外）。

### 阶段 D2

- 自助注册必须验证邮箱；未验证组织处于 `pending_verification`，不能邀请或写数据；
- 同域名已有组织时，不再创建新组织，进入加入已有组织流程；
- 新组织受默认配额限制；超限/异常注册可被 platform 暂停；
- 配额 80% 提醒、100% 停写保读；Trial 到期降级 Free（只读 30 天），不删数据；
- kill switch 可关闭新注册且不影响已有组织。

---

## 16. 生效后需要同步的文档

- [SpecHub-产品方案](SpecHub-产品方案.md) §5.3 / §6.3 / R1：管理模型、org/project/team 定义、托管形态与 binding 迁移、平台运营与组织开通。
- [SpecHub-开发计划](SpecHub-开发计划.md)：新增阶段 A–D2 任务，I4 标注为「单项目最小版」。
- [SpecHub-管理数据持久化最终选型结论](SpecHub-管理数据持久化选型.md)：补 `org_id`、RLS、每租户索引、平台运营表。
- [SpecHub-UI方案](SpecHub-UI方案.md)：补项目管理、团队管理、成员管理、设置页、平台控制台、自助注册页。
- [SpecHub-运维手册](SpecHub-运维手册.md)：补多租户配置、KMS、租户备份/注销、平台运营与 break-glass、组织暂停/删除流程。
