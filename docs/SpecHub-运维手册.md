# SpecHub · 运维手册

> **日期**：2026-11-20　**适用**：Phase 2+ 部署形态（管理库 + Redis 会话 + 可重建投影）。
> **关联**：[选型结论](SpecHub-管理数据持久化选型.md)、[事务边界审计](SpecHub-事务边界审计.md)、[阶段 C 拆解](SpecHub-阶段C任务拆解.md)。

---

## 1. 部署拓扑

| 服务 | 作用 | 持久化 |
|---|---|---|
| `db` | 管理库（PostgreSQL 18.x 默认 / MySQL 8.4 LTS 替代） | `db-data` 命名卷 |
| `redis` | Spring Session 会话 + 登录限流（**阶段 C 起必需**） | 无持久卷；会话可丢失后重新登录 |
| `backend` | Spring Boot：REST / 摄取 / mock / 导出 | `gitsource`（git 镜像）、`sqlite`（投影）、`artifacts` |
| `gitea` | 内置文档仓托管（可替换为外部 git） | `gitea-data` |

- **管理库必须备份**；投影库（`sqlite` 卷）是 git 的可重建派生，不纳入备份；**Redis 会话 / 限流数据不备份**。
- 启动：`docker compose -f compose/docker-compose.yml up -d --build`；健康检查：`curl -fsS localhost:8080/actuator/health`。
- 版本矩阵：PostgreSQL 18.x（默认）/ MySQL 8.4 LTS；Redis 7.4；镜像固定小版本（`postgres:18.6`、`mysql:8.4.11`、`redis:7.4.2-alpine`），按季度小版本升级。
- 所有环境都必须真实登录（admin-token / 密码 / OIDC → Spring Session）；`SPECHUB_ADMIN_TOKEN` 缺失/空串启动失败，不存在免登录模式。

---

## 2. 备份与恢复

### 2.1 备份（只备份管理库）

```bash
scripts/backup-db.sh [输出目录]        # 默认 ./backups
# 环境变量：SPECHUB_DB_CONTAINER（默认 spechub-db）、SPECHUB_DB_NAME（默认 spechub）
```

- PostgreSQL 输出 `management-postgres-<UTC时间>.dump`（`pg_dump -Fc`）；
- MySQL 输出 `management-mysql-<UTC时间>.sql`（`mysqldump --single-transaction`）；
- 建议：每日全量 + 大版本/迁移前手动备份；管理库很小（表结构随 Flyway V1–V7 演进，当前 21 张 + 小数据量），RPO 24h 通常足够。
- 团队与订阅是管理数据（`team` / `team_member` / `subscription.team_id` / `notification.team_id`），随管理库一起备份；投影重建不会影响它们。
- **阶段 C 新增管理数据**：`user_token` / `service_account`（仅 SHA-256 hash + 前缀）/ `user_identity` / `audit_event` 随管理库备份；`app_user` 的 `email` / `password_hash` / `status` 等身份列同样在备份范围。
- **不在备份范围**：Redis 中的 Spring Session 会话与登录限流键（丢失后用户重新登录即可）、SQLite 投影。

### 2.2 恢复

```bash
# 先停止后端写入（避免恢复期间部分写入）
docker compose -f compose/docker-compose.yml stop backend
scripts/restore-db.sh <备份文件>
docker compose -f compose/docker-compose.yml start backend
```

- `.dump` → `pg_restore --clean --if-exists`；
- `.sql` → `mysql` 导入。

### 2.3 演练记录（2026-11-17）

临时 PG 18.6 容器上完成：建表（真实 V1+V2 迁移）→ 写入种子（`alice` + 1 条 changelog）→ 备份 → 删除 `changelog` 表与用户 → 恢复 → 数据一致。结论：脚本可用。

### 2.4 投影重建（不需要备份）

删除 SQLite 投影后重启或触发 reindex：

```bash
docker compose -f compose/docker-compose.yml stop backend
rm -rf compose 卷内 sqlite 文件（或 docker volume rm spechub_sqlite）
docker compose -f compose/docker-compose.yml start backend
# 或启动参数 --reindex
```

管理数据不受影响；release 标记从管理库重放，快照随当前 git 内容恢复（旧 hash 不存在时跳过）。

---

## 3. 监控

| 端点 | 用途 |
|---|---|
| `/actuator/health` | 存活/就绪（compose healthcheck） |
| `/actuator/metrics` | JVM、HikariCP 连接池（`hikaricp.connections.*`）等 |
| `/actuator/flyway` | 迁移状态（已应用版本） |

- **慢查询**：compose 的 `db` 已开启 PG `log_min_duration_statement=500` 与 `log_lock_waits`；MySQL 替代时用 `--slow_query_log=1 --long_query_time=0.5`。
- **Redis（阶段 C）**：`docker exec spechub-redis redis-cli ping` 应为 `PONG`；关注 `INFO memory` / `maxmemory`、`INFO keyspace` 中 `spechub:session:*` 与 `spechub:ratelimit:login:*` 键数量。Redis 不可用时登录 / 会话校验 fail-closed（5xx，不降级匿名）。
- 建议告警：连接池耗尽（`hikaricp.connections.pending` 持续 > 0）、迁移失败（启动失败即暴露）、`/actuator/health` DOWN、Redis 连接异常 / 登录 401/429/5xx 比例突增、`audit_event` 日均写入量异常。

---

## 4. 升级

1. **季度小版本**：改 compose 镜像 tag（如 `postgres:18.6 → 18.7`、`mysql:8.4.11 → 8.4.x`），跑双库回归：
   ```bash
   ./gradlew test --rerun-tasks
   ./gradlew :server:test -Dspechub.test.db=mysql --rerun-tasks
   ```
2. **MySQL 9.7 LTS**（2026-04 GA）：升级路径 8.4 → 9.7（不可跳 LTS）；先在 spike/测试环境验证 Flyway 与 Connector/J，再切镜像与驱动。
3. **迁移脚本**：只追加 `V<n>__*.sql`（PG/MySQL 两份），不修改已应用的历史版本；Flyway 启动自动执行，失败即启动失败。
4. **阶段 B 团队迁移（V4/V5）**：V4 建 `team` / `team_member` 并把旧 `subscription.team` 文本按同组织同名合并回填 `team_id`（过渡期保留旧唯一键与可空 `team_id`）；V5 在团队写入路径上线后回填过渡期 NULL、去重、切换唯一键、`team_id NOT NULL` 并给 `team_member` 加代理主键。从 V3 直接升级到最新版会连续执行，无需手工干预。若 V4 因空团队名/无法确定 `org_id` 失败，先修 `subscription.team` / `org_id` 再重启。
5. **阶段 C 身份 / 审计迁移（V6/V7）**：V6 增加 `app_user` 身份列、建 `user_token` / `service_account` / `user_identity` / `audit_event`，并把旧 `app_user.token_hash` 回填为 `user_token(purpose='legacy')`；V7 删除 `app_user.token_hash` / `is_platform_admin`，V6 后漏回填的 token 补记为 revoked legacy。升级前备份管理库；V6 因重复 `token_hash` 中止时先修数据；V7 后旧 admin-token 仍可认证。**升级窗口内 Redis 必须可用**（会话 / 登录限流依赖；Flyway 本身不依赖 Redis）。

---

## 5. 故障处理

| 现象 | 处理 |
|---|---|
| 启动失败，Flyway 报错 | 检查管理库连接与迁移脚本 vendor 目录；修复后重启（Flyway 事务性 DDL 回滚/标记） |
| 投影查询为空 | 触发 reindex 重建投影；检查 `repo.sync_status=error` 的仓库日志 |
| 管理库误删/损坏 | 停后端 → `restore-db.sh` → 启动；投影可重建，无需恢复 |
| token 全部失效 | 检查 `SPECHUB_ADMIN_TOKEN` 是否变化（启动会刷新 admin 用户 token hash） |
| 删除团队返回 409 | 团队仍有订阅：先取消订阅（`DELETE /api/subscriptions/{id}`）再删；通知历史保留（`team_id` 置 NULL） |
| 团队改名后订阅仍有效但显示旧名 | 订阅以 `team_id` 为真源；重新拉取 `/api/subscriptions`，检查前端缓存 |
| V4/V5 团队迁移失败 | 检查 `subscription.team` / `org_id`；空团队名或无法确定组织的行需先修复。Flyway 失败会回滚本次迁移，修复后重启 |
| MySQL 时间偏移 8 小时 | 确认 JVM `-Duser.timezone=UTC`（Dockerfile 已固定）与连接参数 `connectionTimeZone=UTC` |
| Redis 不可用 / 连接被拒 | 登录与会话校验 fail-closed（5xx，无法建立会话）；恢复 Redis 后用户重新登录。检查 `SPECHUB_REDIS_URL`、Redis 容器 / 网络与健康状态 |
| 禁用用户后旧会话仍能访问 | 禁用事务吊销令牌并删除 Redis 会话；每请求 DB 状态校验兜底。检查日志中 Redis 删除失败记录 |
| 最后一名 org_admin 无法禁用 / 降级 / 删除（409） | 先提升另一名用户为 `org_admin` 再操作；平台恢复流程属 D1 |
| OIDC 登录失败 / 回调 400 | 在 IdP 注册回调 `${SPECHUB_EXTERNAL_URL}/login/oauth2/code/<registration-id>`；确保 `external-url` / `redirect-uri` 与浏览器地址同源（dev 用 Vite 代理 `/oauth2`、`/login/oauth2`） |
| 会话 Cookie 丢失 / CSRF 403 | 确认同源访问（推荐 Vite 代理）；`SPECHUB_SESSION_COOKIE_SECURE` 与访问协议匹配；写请求带 `X-CSRF-Token` |
| 审计查询 403 | 仅 `org_admin` 可查；项目 admin / 服务账号 / 普通成员返回 403 |

---

## 6. CI

`.github/workflows/ci.yml`：

- `test (postgresql)` / `test (mysql)` 矩阵：同一组用例跑两个管理库；
- `bootJar`：验证可构建产物；
- Testcontainers 使用 runner 预装 Docker；失败上传测试报告 artifact。

---

## 7. 账号与会话运维（阶段 C）

- **会话存储**：Spring Session Data Redis（namespace `spechub:session`），空闲 24h / 绝对 7d；`cleanup-cron` 每 15 分钟清理过期键；Redis 故障 fail-closed（不降级匿名 / 内存会话）。
- **HA**：compose 默认单实例；生产建议 Redis Sentinel（主从 + 自动故障转移）或 Cluster。当前配置入口为 `SPECHUB_REDIS_URL` / `spring.data.redis.*`；启用 Sentinel / Cluster 时按 Spring Boot 属性补充 `spring.data.redis.sentinel.master/nodes` 或 `spring.data.redis.cluster.nodes`（或挂载外部 `application-prod` 配置），切换后验证会话连续性与 Redis 故障时的 fail-closed。
- **会话列表 / 吊销**：用户「账号与安全 → 活动会话」查看脱敏 id / IP / User-Agent / 创建与最近访问时间，标记当前会话并吊销其它会话；`org_admin` 可通过 `GET/DELETE /api/auth/users/{id}/sessions[/{sessionId}]` 运维吊销。
- **禁用用户**：管理库事务内吊销该用户全部 `user_token`，提交后删除其 Redis 会话；每请求状态校验兜底。重新启用不恢复旧会话 / 旧令牌。服务账号是项目级独立身份，不随用户禁用。
- **重置密码**：生成 24h 一次性 `password_reset` 令牌（只返回一次），删除该用户全部会话；用户用 `/api/auth/password/setup` 设置新密码，令牌第二次使用失败。
- **最后 org_admin 保护**：禁用 / 降级 / 删除最后一名可登录 `org_admin` 返回 409；先提升另一名用户。
- **Cookie / CSRF / 安全头**：`spechub_session` 为 HttpOnly + SameSite=Lax（prod 加 Secure）；会话写请求必须带 `X-CSRF-Token`；Bearer 链无 Cookie / CSRF；所有未显式放行的端点默认拒绝，401 / 403 返回统一 `ApiErrorBody`。
- **失败排查**：登录 401 统一文案（不区分账号 / 密码）；429 为登录限流（标识 5 次 / 5 分钟，锁 15 分钟；IP 20 次 / 5 分钟）；5xx 优先检查 Redis 与管理库。

---

## 8. OIDC 登录（可选）

- **配置**：`SPECHUB_OIDC_ENABLED=true` + `SPECHUB_OIDC_ISSUER_URI` / `CLIENT_ID` / `CLIENT_SECRET`；回调地址 `/login/oauth2/code/<registration-id>`（默认 `spechub`）需在 IdP 注册。
- **地址一致性**：`SPECHUB_EXTERNAL_URL` 必须是浏览器可访问地址；dev 下 Vite 已代理 `/oauth2`、`/login/oauth2`，并将 `external-url` / `redirect-uri` 指向前端（如 `http://localhost:5173`），保证回调与 Cookie 同源。
- **绑定规则**：只绑定已验证邮箱；`user_identity(provider, subject)` 唯一；`auto-provision` 默认关闭（关闭时未绑定用户登录被拒）；不保存 IdP token（`OAuth2AuthorizedClientRepository` 为 no-op）。
- **故障**：`/?loginError=oidc` 表示回调失败；检查 IdP 可达性、state / nonce、时钟偏差与回调地址。

---

## 9. 审计（阶段 C）

- **存储**：管理库 `audit_event`；保留 `SPECHUB_AUDIT_RETENTION_DAYS`（默认 365 天），每日清理过期记录；随管理库备份。
- **查询**：`GET /api/audit?from=&to=&actor=&action=&targetType=&outcome=&limit=&offset=`；仅 `org_admin`，固定当前 org，跨 org 不可见，按 `created_at DESC, id DESC`；Web「审计日志」提供过滤 + 分页（50 条 / 页）。
- **写入范围**：登录成功 / 失败 / 登出、密码与会话、用户生命周期、令牌、服务账号、OIDC、项目 / 成员 / 团队 / binding / Git 连接；认证与限流事件使用独立事务尽力而为。
- **敏感值**：`detail` 只存摘要（≤1024），不含密码 / 令牌 / Cookie / spec 正文；审计查询本身不写审计（避免自增循环）。
- **故障**：查询 403 检查 `org_admin`；跨 org 为空属预期；审计写入失败不改变已完成的管理动作（控制器动作后写入）。
