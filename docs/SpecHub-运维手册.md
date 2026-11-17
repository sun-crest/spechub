# SpecHub · 运维手册

> **日期**：2026-11-17　**适用**：Phase 2+ 部署形态（管理库 + 可重建投影）。
> **关联**：[选型结论](SpecHub-管理数据持久化选型.md)、[事务边界审计](SpecHub-事务边界审计.md)。

---

## 1. 部署拓扑

| 服务 | 作用 | 持久化 |
|---|---|---|
| `db` | 管理库（PostgreSQL 18.x 默认 / MySQL 8.4 LTS 替代） | `db-data` 命名卷 |
| `backend` | Spring Boot：REST / 摄取 / mock / 导出 | `gitsource`（git 镜像）、`sqlite`（投影）、`artifacts` |
| `gitea` | 内置文档仓托管（可替换为外部 git） | `gitea-data` |

- **管理库必须备份**；投影库（`sqlite` 卷）是 git 的可重建派生，不纳入备份。
- 启动：`docker compose up -d --build`；健康检查：`curl -fsS localhost:8080/actuator/health`。
- 版本矩阵：PostgreSQL 18.x（默认）/ MySQL 8.4 LTS；镜像固定小版本（`postgres:18.6`、`mysql:8.4.11`），按季度小版本升级。

---

## 2. 备份与恢复

### 2.1 备份（只备份管理库）

```bash
scripts/backup-db.sh [输出目录]        # 默认 ./backups
# 环境变量：SPECHUB_DB_CONTAINER（默认 spechub-db）、SPECHUB_DB_NAME（默认 spechub）
```

- PostgreSQL 输出 `management-postgres-<UTC时间>.dump`（`pg_dump -Fc`）；
- MySQL 输出 `management-mysql-<UTC时间>.sql`（`mysqldump --single-transaction`）；
- 建议：每日全量 + 大版本/迁移前手动备份；管理库很小（表结构 10 张 + 小数据量），RPO 24h 通常足够。

### 2.2 恢复

```bash
# 先停止后端写入（避免恢复期间部分写入）
docker compose stop backend
scripts/restore-db.sh <备份文件>
docker compose start backend
```

- `.dump` → `pg_restore --clean --if-exists`；
- `.sql` → `mysql` 导入。

### 2.3 演练记录（2026-11-17）

临时 PG 18.6 容器上完成：建表（真实 V1+V2 迁移）→ 写入种子（`alice` + 1 条 changelog）→ 备份 → 删除 `changelog` 表与用户 → 恢复 → 数据一致。结论：脚本可用。

### 2.4 投影重建（不需要备份）

删除 SQLite 投影后重启或触发 reindex：

```bash
docker compose stop backend
rm -rf compose 卷内 sqlite 文件（或 docker volume rm spechub_sqlite）
docker compose start backend
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
- 建议告警：连接池耗尽（`hikaricp.connections.pending` 持续 > 0）、迁移失败（启动失败即暴露）、`/actuator/health` DOWN。

---

## 4. 升级

1. **季度小版本**：改 compose 镜像 tag（如 `postgres:18.6 → 18.7`、`mysql:8.4.11 → 8.4.x`），跑双库回归：
   ```bash
   ./gradlew test --rerun-tasks
   ./gradlew :server:test -Dspechub.test.db=mysql --rerun-tasks
   ```
2. **MySQL 9.7 LTS**（2026-04 GA）：升级路径 8.4 → 9.7（不可跳 LTS）；先在 spike/测试环境验证 Flyway 与 Connector/J，再切镜像与驱动。
3. **迁移脚本**：只追加 `V<n>__*.sql`（PG/MySQL 两份），不修改已应用的历史版本；Flyway 启动自动执行，失败即启动失败。

---

## 5. 故障处理

| 现象 | 处理 |
|---|---|
| 启动失败，Flyway 报错 | 检查管理库连接与迁移脚本 vendor 目录；修复后重启（Flyway 事务性 DDL 回滚/标记） |
| 投影查询为空 | 触发 reindex 重建投影；检查 `repo.sync_status=error` 的仓库日志 |
| 管理库误删/损坏 | 停后端 → `restore-db.sh` → 启动；投影可重建，无需恢复 |
| token 全部失效 | 检查 `SPECHUB_ADMIN_TOKEN` 是否变化（启动会刷新 admin 用户 token hash） |
| MySQL 时间偏移 8 小时 | 确认 JVM `-Duser.timezone=UTC`（Dockerfile 已固定）与连接参数 `connectionTimeZone=UTC` |

---

## 6. CI

`.github/workflows/ci.yml`：

- `test (postgresql)` / `test (mysql)` 矩阵：同一组用例跑两个管理库；
- `bootJar`：验证可构建产物；
- Testcontainers 使用 runner 预装 Docker；失败上传测试报告 artifact。
