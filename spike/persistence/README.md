# 管理库持久化 spike（Phase 2 前置验证）

独立 Gradle 项目，**不并入主构建**（根 `settings.gradle.kts` 不包含此目录）。

## 运行

```bash
./gradlew -p spike/persistence test
```

需要 Docker daemon；固定镜像 `postgres:18.6`、`mysql:8.4.11`。首次运行会拉镜像与依赖。

## 验证范围

| # | 验证点 | 结果 |
|---|---|---|
| 1 | Kotlin 不可变 data class 构造器映射 | ✅ 需要 `kotlin-reflect`；`@Id` / `@Column` / `@Version` 正常 |
| 2 | 派生查询 / `@Query` / 布尔映射 | ✅ `findByUsername`、`findAllByIsAdminTrue`、`BOOLEAN` / `TINYINT(1)` 均正常 |
| 3 | `@Version` 乐观锁 | ✅ insert 初始 0，update 后 1；旧版本更新抛 `OptimisticLockingFailureException` |
| 4 | 唯一约束冲突 | ✅ 两库统一映射为 `DataIntegrityViolationException` |
| 5 | Flyway vendor 目录 | ✅ `classpath:db/migration/{vendor}` 占位符可用（`postgresql` / `mysql`） |
| 6 | PG 18.6 + `timestamptz` → `Instant` | ✅ 微秒精度 UTC 往返一致 |
| 7 | MySQL 8.4.11 + `DATETIME(6)` → `Instant` | ⚠️ **依赖 JVM 时区 UTC**：非 UTC 下漂移 8 小时；`-Duser.timezone=UTC` 后一致 |
| 8 | MySQL `TIMESTAMP(6)` 备选 | ✅ 非 UTC JVM 下也一致（但 2038 上限） |
| 9 | 共享容器 + 每类 schema 隔离 | ✅ PG：`CREATE SCHEMA` + URL `currentSchema`；MySQL：容器默认库/用户 |

## 版本与坐标要点（Boot 4 实测）

1. **Flyway 自动配置在 `spring-boot-starter-flyway`**：Boot 4 把自动配置从
   `spring-boot-autoconfigure` 拆出，只加 `flyway-core` 不会触发迁移（实测表不存在）。
2. **Testcontainers 2.0 坐标改名**：`org.testcontainers:testcontainers-*`
   （`testcontainers-junit-jupiter` / `testcontainers-postgresql` / `testcontainers-mysql`）。
   Boot 4 BOM 未把模块版本传递到 Gradle 解析，需显式
   `testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))`。
3. **PG URL 拼接**：Testcontainers 的 `jdbcUrl` 已带 `?loggerLevel=OFF`，
   追加参数必须判断已有 `?` 并用 `&`，否则 `currentSchema` 被吞（实测表找不到）。
4. **`{vendor}` 占位符**可用，目录约定 `db/migration/postgresql`、`db/migration/mysql`。

## 对主方案的结论

- 选型文档的 Spring Data JDBC + Flyway 双 vendor + Testcontainers 双库方案**成立**。
- 新增两条强约束：
  - MySQL 用 `DATETIME(6)` 时，**JVM 必须 UTC**（`-Duser.timezone=UTC`，容器部署默认 UTC）；
  - Flyway 依赖必须用 `spring-boot-starter-flyway`，不能只加 `flyway-core`。
- 测试基建按 `SharedContainers + 每类 schema/database` 模式即可复用。
