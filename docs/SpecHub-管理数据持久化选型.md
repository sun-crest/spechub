# SpecHub · 管理数据持久化最终选型结论

> **日期**：2026-11-16（2026-11-17 补决策）　**状态**：结论已确认——版本矩阵已定（PG 18.x / MySQL 8.4 LTS）、管理库仅 PostgreSQL / MySQL（无 SQLite 管理库）、存量数据重置不迁移、`project` / `repo` 归管理库
> **范围**：管理数据（用户、角色、项目、仓库注册、评论、订阅、通知、评审、mock 规则、changelog、发布状态）的存储与持久化技术选型。
> **不覆盖**：投影层（api/schema/doc/FTS/版本快照）继续使用 SQLite + kernel raw JDBC，不随本文迁移；git 继续作为内容真源。

---

## 1. 结论摘要

| 决策项 | 结论 |
|---|---|
| 数据分层 | git = 内容真源；管理 DB = 持久管理数据；投影 DB = 可重建索引 |
| 数据源 | **两个数据源**：管理数据源（PostgreSQL / MySQL，Spring 唯一 DataSource）+ 投影数据源（SQLite，kernel `IndexDb` 自管连接，不进 Spring） |
| 管理库引擎 | **PostgreSQL 18.x 为参考默认；MySQL 8.4 LTS 为受支持替代**（MySQL 9.7 LTS 列入升级路线） |
| 持久化技术 | **Spring Data JDBC（Repository CRUD）+ JdbcTemplate（自定义 SQL）** |
| 事务 | 管理域用 Spring `@Transactional`；投影层保留 kernel 显式事务；git + DB 不做分布式事务 |
| 建模规则 | 单表 CRUD、扁平实体、ID 引用、批量聚合；管理库内保留外键，禁止跨投影边界外键 |
| 双库兼容 | Flyway 按 vendor 维护迁移；双库 Testcontainers CI；禁方言 SQL |
| 不采用 | JPA/Hibernate 作为默认、MyBatis-Plus 作为默认、单库混存管理+投影、kernel 引入 Spring 事务 |

---

## 2. 数据分层与归属

### 2.1 三层模型

| 层 | 载体 | 内容 | 生命周期 |
|---|---|---|---|
| 内容真源 | git | `specs/` 下 OpenAPI、`docs/` 下 Markdown | 永久、可 diff/回滚/评审 |
| 平台管理数据 | 管理 DB（durable） | 用户、角色、token hash、项目、仓库注册、评论、订阅、通知、评审、mock 规则、changelog、发布状态（release 标记） | 持久，必须备份；**本阶段存量数据重置，不迁移** |
| 可重建投影 | 投影 DB（disposable） | `api` / `schema` / `doc` 投影、FTS、版本快照、draft/release 投影副本 | 可随时清空，从 git + 管理库（repo 列表 / release 标记）重扫 |

### 2.2 关键边界

- **禁止**管理数据外键指向投影表。投影重建（DELETE + INSERT）会级联删除管理数据。
- **`project` / `repo` 归管理库**（仓库注册是管理数据）。投影库**不保留** `project` / `repo` 表；投影表的 `project_id` / `repo_id` 只是逻辑列，重建时由 server 从管理库读取 repo 列表并传入 id。
- **发布状态归管理库**：release 标记（`repo_id + path + spec_hash`）无法从 git 重建，必须落管理库；投影层 `version.stage` 与 release 投影副本是重建时按 release 记录重放的派生结果。
- 管理数据引用内容时使用**稳定键**：`repo_id + path`、`repo_id + method + path`、`repo_id + name`。
- **管理库内部保留外键**（user ↔ membership、project ↔ repo 等），默认 `ON DELETE RESTRICT`，删除由 Service 显式编排。
- 投影表引用管理表的 `project_id` / `repo_id` 使用逻辑引用（不建跨库外键）。

---

## 3. 数据源与模块归属

### 3.1 两个数据源

| 数据源 | 用途 | 技术 |
|---|---|---|
| **管理数据源** | 持久管理数据 | PostgreSQL（默认）/ MySQL 8.4 LTS |
| **投影数据源** | 可重建索引 | SQLite（每实例一份，WAL） |

- 两个数据源**不共享事务**，不做 XA/JTA。
- **Spring 侧只有一个 DataSource**：管理库注册为 Spring DataSource，承载 `@Transactional`、Spring Data JDBC 与 Flyway；投影库由 kernel `IndexDb` 以 `DriverManager` 自开连接，不注册为 Spring DataSource，不参与 Spring 事务、Flyway 与 `@Transactional`。
- **不提供 SQLite 管理库**：管理库只有 PostgreSQL / MySQL 两个 vendor。本地开发与测试一律使用真实引擎（compose dev 服务或 Testcontainers），Flyway 只维护这两套迁移。

### 3.2 模块归属

```
modules/server/
  repository/     # 管理数据 Repository（Spring Data JDBC）
  service/        # 业务编排 + 管理域 @Transactional
  web/            # HTTP 层
  ingest/         # 摄取：写投影 + 更新管理域同步状态/通知

modules/kernel/spec-index/
  # 投影 Store：SQLite + raw JDBC；保持零 Spring

modules/mock/
  # MockEngine：读投影 + MockRuleSource 端口（由 server 实现）
```

- **投影 Store 留在 kernel**：mock 模块依赖它，且 kernel 零框架硬约束。
- **管理 Store 放 server**：管理数据是 server 独占关注点，可使用 Spring 体系。
- mock 需要 mock 规则时，通过 kernel/mock 定义的窄接口 `MockRuleSource` 由 server 注入，避免 mock 依赖 server。

---

## 4. 事务策略

| 场景 | 策略 |
|---|---|
| 管理域单表 / 多表写 | Spring `DataSourceTransactionManager` + Service 层 `@Transactional`；同一管理库内多表写保持本地事务 |
| 投影层批量重建 | kernel `IndexDb.transaction` 显式事务；每个 Store 原语独立短事务 |
| 跨管理库 + 投影 + git | **不做分布式事务**；固定次序 + 幂等 + 状态机 + 最终一致 |
| git + DB 审批/写回 | 明确步骤顺序、失败停点、可重试幂等；审批状态机标识进行中状态 |

**明确不做：**

- 不在 kernel/投影层加 `@Transactional`（无 Spring 事务管理器，且违反零框架约束）。
- 不让 `@Transactional` 包住 git、Shell、网络调用。
- 不使用 XA/JTA 协调两个数据源。

---

## 5. 管理库引擎选型

### 5.1 结论

- **PostgreSQL 18**：参考默认引擎（18.x，EOL 2030-11）。PG 19 仅到 RC1（2026-10），未 GA，不纳入。
- **MySQL 8.4 LTS**：受支持替代引擎（EOL 2032，含 Extended）。MySQL 9.7 LTS 已于 2026-04 GA，列入升级路线（8.4 → 9.7，不可跳过 LTS）。
- 不支持第三个管理库引擎（投影层 SQLite 除外）。

### 5.2 理由（不依赖大文本/JSON/Upsert）

管理层不需要把「大文本、Upsert、JSON」作为选型依据：

- 大文本只有 `review.new_content` / `diff`，低频，两种库都能处理；
- Upsert 用「唯一约束 + 事务内先查后写」即可，不依赖数据库原生 upsert；
- 当前管理数据没有 JSON 字段需求。

因此按以下因素选择：

| 因素 | PostgreSQL | MySQL |
|---|---|---|
| 事务性 DDL | 优 | 弱（单语句原子） |
| 类型/语义严格性 | 优 | 需 strict mode |
| 时间类型 | `timestamptz` 清晰 | `DATETIME` 无时区语义 |
| 大小写/collation | 默认区分，需显式不敏感 | 默认不敏感，隐式影响 |
| 升级/LTS | 每年大版本；18 EOL 2030-11 | 8.4 LTS EOL 2032；9.7 LTS 2026-04 GA |
| 在线 DDL | 部分重写 | 优 |
| 开箱 HA | 依赖生态 | InnoDB Cluster |
| 许可/可移植 | 宽松、标准 SQL | GPLv2、方言更多 |

管理层表小、迁移安全与语义清晰更重要，因此 **PostgreSQL 18 作为参考默认**；MySQL 8.4 LTS 完全可作为受支持替代。

---

## 6. 持久化技术选型

### 6.1 主选：Spring Data JDBC + JdbcTemplate

**定位：一套 Spring JDBC 内核的两层用法，不是两套框架。**

- **Spring Data JDBC**：Repository CRUD、实体映射、派生查询、分页、`@Version` 乐观锁；
- **JdbcTemplate / NamedParameterJdbcTemplate**：自定义 SQL、复杂查询、批量更新；
- 两者共享同一 `DataSource` 与 `DataSourceTransactionManager`；
- 无 JPA 持久化上下文、无脏检查、无代理、无懒加载；
- 支持 Kotlin 不可变 `data class` 实体（构造器映射），无需 no-arg/open 插件。

适用理由：

- 管理数据以单表 CRUD 为主；
- 不做跨表关联，关联数据在代码中批量聚合；
- Kotlin 不可变模型；
- 双库兼容下，简单 SQL 可移植性最好；
- 复杂 SQL 可自然下探 `JdbcTemplate`。

### 6.2 备选

| 方案 | 定位 | 结论 |
|---|---|---|
| 纯 `JdbcTemplate` | 不要 Repository 抽象，所有 SQL 可见 | 可行；CRUD 样板较多 |
| JPA + JdbcTemplate | Hibernate + JDBC 两套内核 | 仅在团队强制要求 JPA 时采用；需扁平实体 + Flyway + `ddl-auto=none` + flush 纪律 |
| MyBatis-Plus | 单一 SQL 中心栈 | 不作为默认；仅在团队 SQL 导向、接受可变 Bean 与双库方言配置时采用 |

**不采用：**

- JPA 作为默认（实体需 no-arg/open、会话/flush、其关联映射能力在本设计中被刻意禁用）；
- MyBatis-Plus 作为默认（Kotlin 需 `var` Bean、双库分页/databaseId 配置、第三方耦合）；
- JPA + MyBatis-Plus 同时使用。

---

## 7. 建模与查询规则

### 7.1 实体与表

- 扁平实体：只放 `projectId`、`repoId` 等 ID 字段，不做 ORM 关联映射。
- 单表 CRUD 为主；每个聚合独立 Repository。
- 主键：`BIGINT` 自增/identity；`Long`。
- 时间：`Instant` + UTC；PG 用 `timestamptz`，MySQL 用 `DATETIME(6)` 并**强制 JVM UTC**（spike 实测：非 UTC JVM 下往返漂移 8 小时）。
- 布尔：PG `BOOLEAN`，MySQL `TINYINT(1)`；由迁移脚本定义，ORM 只做映射。
- 大文本：`TEXT` / `LONGTEXT`；JPA/Spring Data JDBC 不生成 DDL。

### 7.2 查询

- 默认不做跨表 SQL JOIN；关联数据用批量查询：
  - `findAllByIdIn(ids)` / `WHERE id IN (...)`；
  - 主表分页 → 收集关联 ID → 批量查关联表 → 内存组装。
- `IN` 列表按分页大小控制；批量导出时分块。
- **标准只读 JOIN 可作为例外**：禁的是方言 SQL，不是标准 JOIN。
- 跨表过滤/排序/分页是应用层聚合的真实限制；必要时反规范化或允许只读 JOIN。

### 7.3 冗余字段

- 仅在「需要按关联字段排序/过滤」或「读多写少」时引入。
- 优先同事务直接批量更新；异步事件必须配 outbox/重试/对账。
- 不为了单个冗余字段引入事件基础设施。

### 7.4 幂等

- 唯一约束 + 事务内先查后写；并发冲突捕获重复键或重试。
- 不依赖数据库原生 upsert（`ON CONFLICT` / `ON DUPLICATE KEY UPDATE`）表达业务幂等。
- 跨 git + DB 的操作按业务键/内容 hash 幂等。

### 7.5 JSON

- 当前管理数据不使用 JSON 列。
- 未来如需结构化负载，优先规范化列或 `TEXT`；确需灵活查询再评估 `JSONB`（PG）。

---

## 8. 双库兼容纪律

### 8.1 支持矩阵

- PostgreSQL：**18.x**（当前 18.6，2026-08；EOL 2030-11）。PG 19 仅到 RC1（2026-10），未 GA，不纳入。
- MySQL：**8.4 LTS**（当前 8.4.11，2026-07；EOL 2032 含 Extended）。8.0 已于 2026-04 EOL，不可选。
- 投影层：SQLite（sqlite-jdbc，WAL）。

**MySQL 9.7 LTS 升级路线**：9.7.0 LTS 于 2026-04-21 GA，8.4 → 9.7 是受支持的升级路径（不可跳过 LTS，也不支持降级）。本阶段不采用 9.7，原因是它 GA 仅半年、Flyway 与 Connector/J 对 9.x 的支持需实测；spike 阶段顺带验证，通过后把「2027 年前升级 9.7」写入运维计划。

**镜像固定策略**：compose 与 Testcontainers 一律固定小版本 tag（`postgres:18.6`、`mysql:8.4.11`），不使用 `postgres:18` 这类漂移 tag；按季度小版本（2/5/8/11 月）升级并跑双库回归。

### 8.2 强制规则

1. **DDL/迁移**：Flyway 按 vendor 维护（`db/migration/postgresql`、`db/migration/mysql`，`classpath:db/migration/{vendor}` 占位符实测可用）；**Boot 4 必须引入 `spring-boot-starter-flyway`**（自动配置已从 autoconfigure 拆出，只加 `flyway-core` 不触发迁移），另需 `flyway-database-postgresql` 与 `flyway-mysql` 模块；禁止 ORM 自动建表。
2. **类型映射**：统一 `BIGINT IDENTITY`、`VARCHAR`、`TEXT`/`LONGTEXT`、`BOOLEAN`/`TINYINT(1)`、`TIMESTAMPTZ`/`DATETIME(6)`。
3. **时间**：统一 UTC；**JVM 必须 UTC**（`-Duser.timezone=UTC`，容器部署默认 UTC）——MySQL `DATETIME(6)` 在非 UTC JVM 下 `Instant` 往返漂移（spike 实测 8 小时），JVM UTC 后一致；MySQL 服务端另配 `--default-time-zone=+00:00`，Connector/J 配置 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。备选 `TIMESTAMP(6)`：不依赖 JVM 时区，但有 2038 上限。PG 用 `timestamptz`，JDBC 按 `Instant` 读取（微秒精度往返一致）。
4. **大小写/collation**：显式设计；用户名不敏感唯一在 PG 用 `lower()` 唯一索引，MySQL 用 CI 索引；`token_hash` 用区分大小写/二进制比较。
5. **命名**：小写 snake_case；避开 `schema`、`version` 等潜在保留字。
6. **SQL**：禁方言函数、方言 upsert、方言分页语法；分页用 `LIMIT/OFFSET`。
7. **测试**：Testcontainers 同时跑 PostgreSQL 18.x 与 MySQL 8.4.x（固定小版本）；Testcontainers 2.0 坐标为 `testcontainers-*`，需显式 `platform("org.testcontainers:testcontainers-bom:2.0.5")`（Boot 4 BOM 未将模块版本传递到 Gradle）；共享容器 + 每类 schema/database 隔离。CI 双库矩阵（仓库当前无 CI，需先建骨架）。
8. **备份**：只备份管理库；投影库可重建。

---

## 9. 被否决方案

| 方案 | 否决理由 |
|---|---|
| Spring `@Transactional` 管 kernel/投影层 | 无 DataSource/事务管理器；`DriverManager` 自开连接不参与 Spring 事务；违反 kernel 零框架 |
| 把 spec-index 投影 Store 搬进 server/repository | mock 反向依赖 server；破坏 kernel 零框架与模块依赖方向 |
| 管理库与投影库合并为一个数据源 | 生命周期/备份/重建语义冲突；误删风险 |
| 管理库使用 JPA 作为默认 | Kotlin 实体约束、会话/flush、双栈摩擦；其关联映射能力在本设计中被禁用 |
| 管理库使用 MyBatis-Plus 作为默认 | Kotlin 需可变 Bean；双库分页/databaseId 配置；第三方耦合；对单表 CRUD 无决定性优势 |
| 完全禁止外键 | 外键在 MySQL/PG 都高度可移植；管理库内丢外键换来的是孤儿数据与一致性成本 |
| 完全禁止标准 JOIN | 标准 JOIN 在两库可移植；禁的是方言 SQL，不是 JOIN |
| 用大文本/JSON/Upsert 作为选型依据 | 管理层无此需求或两库均能处理；不是决策因素 |
| 一次部署同时连接 MySQL 与 PostgreSQL 管理库 | 无跨库事务；运维面翻倍；按部署二选一 |
| 管理库用 SQLite（本地开发降依赖） | 需要第三个 vendor 的 DDL 与类型语义（`BOOLEAN` / `TIMESTAMPTZ` / `IDENTITY` / 大小写唯一全部不一致），本地与 CI 行为分叉；容器化引擎成本可控 |

---

## 10. 实施顺序

### Phase 0 · 决策与文档
- 确认本结论；更新产品方案中的数据归属与「索引可重建」表述。
- 确定支持矩阵（PG/MySQL 版本）与备份/RPO。

### Phase 1 · 逻辑拆分与边界修复
- [x] 拆 `PlatformStore` / `ProjectionStore`（可先同库）。✅ 2026-11-17：两域 Store 落地，共享同一 `IndexDb`、SQL 与事务边界零变化；22 个调用点按域注入；测试拆为 `PlatformStoreTest` / `ProjectionStoreTest`。
- [x] `comment.doc_id`、`mock_rule.api_id` 去跨边界 FK，改稳定寻址。✅ 2026-11-17：DDL 去 FK（两列降为展示快照）；旧库 `migrate` 原地重建（rename → create → copy → drop，数据保留）；文档评论改 `repo_id + path` 稳定寻址；`IndexDbMigrationTest` 覆盖。
- [x] `membership.project_id`、`comment.repo_id`、`subscription.repo_id`、`notification.repo_id`、`review.repo_id`、`changelog.repo_id` 随 `project` / `repo` 归管理库而成为管理库内部外键。✅ 2026-11-17：`project`/`repo` 已迁管理库；投影库对应表已删，`repo_id` 全部降为逻辑列（旧库原地重建保数据）。
- [x] 投影库删除 `project` / `repo` 表；`api` / `schema` / `doc` / `version` 的 `repo_id` 改为逻辑列（无 FK）。✅ 2026-11-17（随 Phase 2 project/repo 迁移完成）。
- [x] 发布状态迁管理库（`release` 表：`repo_id + path + spec_hash + released_at`），投影 `version.stage` 改为重建时重放的派生值。✅ 2026-11-17：`ReleaseService` + `V2__release.sql`（双 vendor）；`ReleaseController` 先落管理库再投影 promote；`Ingestor` 摄取完成后 replay；`ReleaseServiceTest` 覆盖幂等 / 重建恢复 / 跳过不存在 hash。
- [x] 补「清空投影 → 从 git 重扫 → 管理数据仍在」的重建测试。✅ 2026-11-17：`IngestorE2ETest` 覆盖 7 类管理数据，已启用转绿。

### Phase 2 · 物理拆分两个数据源
> **前置 spike 已完成（2026-11-17）**：Spring Data JDBC + Kotlin 不可变实体、`@Version` 乐观锁、Flyway 双 vendor、双库 Testcontainers 均实测通过；结论与复现见 `spike/persistence/README.md`。
- [x] 管理库独立 DataSource；投影继续 SQLite。✅ 2026-11-17：`spring.datasource` 指向管理库；投影 `IndexDb` 不进 Spring DataSource；Flyway `classpath:db/migration/{vendor}`。
- [x] **存量数据不迁移**：现有 SQLite 库中的管理数据直接丢弃；新管理库由 Flyway 建表 + 启动种子（default project / admin）重建。✅ 已落实（管理库空库启动）。
- [x] 管理表 DDL/迁移迁到 server（Flyway per vendor）。✅ 2026-11-17：10 张管理表 PG/MySQL 两版迁移脚本。
- [x] 管理 Repository 用 Spring Data JDBC；复杂 SQL 用 JdbcTemplate。✅ 首个纵切：用户/成员域（`UserService` + `UserRepository` / `MembershipRepository`，多表写 `@Transactional`；`AuthService` / `AuthController` 已接入）。
- [x] project / repo 迁管理库（`RepoService`）；投影库删除 project/repo 表，`repo_id` 降为逻辑列；旧库迁移原地重建管理表保数据。✅ 2026-11-17：`Ingestor` 同步状态回写改为函数注入；RepoController/Ingestor/Polling/Reindex/ApplicationInit/AuthService 与 12 个 Controller 全部切换；`RepoServiceTest` 覆盖。
- [x] subscription / notification 迁管理库（`SubscriptionService`；Ingestor 经 `IngestHooks` 端口写入通知）。✅ 2026-11-17：订阅幂等、通知历史不随取消清空；`SubscriptionServiceTest` 覆盖。
- [x] comment 迁管理库（`CommentService`；接口评论 repo+method+path、文档评论 repo+path 稳定寻址）。✅ 2026-11-17：`CommentServiceTest` 覆盖。
- [x] review 迁管理库（`ReviewService`：创建 / 组合查询 / 审批状态流转）。✅ 2026-11-17：`ReviewServiceTest` 覆盖。
- [x] mock_rule 迁管理库 + `MockRuleSource` 端口（mock 模块不再依赖管理域类型）。✅ 2026-11-17：`MockRuleService` 实现端口注入 MockEngine；`MockRuleServiceTest` 覆盖幂等 upsert / 项目级与接口级并存。
- [ ] changelog 迁管理库（所有写路径的审计留痕，最后收口）。
- [ ] 双库（MySQL）集成测试矩阵接入。

### Phase 3 · 事务与原子性
- 管理域 Service 加 `@Transactional`；多表写本地事务。
- 投影层保留 `IndexDb.transaction`；补复合原语。
- 审批/写回链路定义次序、幂等与状态机。

### Phase 4 · 双库与运维
- CI 双库 Testcontainers 矩阵（PG 18.x + MySQL 8.4.x；当前仓库无 CI 流水线，需先建 CI 骨架）。
- 备份/恢复演练；支持矩阵文档。
- 监控：连接池、慢查询、迁移状态。

---

## 11. 验收标准

- [ ] 管理库与投影库可独立重置：删除投影库后，由 server 从管理库读取 repo 列表与 release 标记，从 git 全量重建投影，管理数据不受影响。
- [ ] 管理库内无指向投影表的外键；内容引用全部使用稳定键。
- [ ] 投影库 DDL 不含 `project` / `repo` 表，投影表对管理库零外键。
- [ ] 空管理库启动即可用：Flyway 建表 + 种子重建 default project 与 admin。
- [ ] 管理域多步写有明确事务边界；投影层无 Spring 事务注解。
- [ ] 同一套管理代码在 PostgreSQL 与 MySQL 8.4 LTS 上通过同一组集成测试。
- [ ] 迁移脚本按 vendor 分离；无 ORM 自动 DDL。
- [ ] 管理库备份/恢复可用；投影库不纳入备份。
- [ ] 无方言 SQL、无原生 upsert、无 JSON 列依赖。
- [ ] mock 模块不依赖 server；投影 Store 不搬入 server。

---

## 12. 风险与待确认

| 项 | 说明 |
|---|---|
| Spring Data JDBC 团队熟悉度 | 若不熟悉，可退回纯 `JdbcTemplate`；Repository 只是省样板 |
| Spring Data JDBC + Kotlin | ✅ spike 已验证：构造器映射需 `kotlin-reflect`；`@Version` insert=0、update 递增，旧版本更新抛 `OptimisticLockingFailureException` |
| 评审内容体积 | `review.new_content` 可外置为文件/对象存储；当前留在 DB 也可行 |
| 发布状态持久性 | 已定为管理库 `release` 表（`repo_id + path + spec_hash`）；如需更强保障可加 git tag/manifest，并明确 RPO |
| mock 规则跨模块 | 通过 `MockRuleSource` 端口解决；不要把 mock 规则放回投影库 |
| 双库差异 | 时区、collation、生成键、分页需双库测试；不能只测一个 |
| MySQL 版本路线 | 9.7 LTS 已于 2026-04 GA，但 Flyway / Connector-J 对 9.x 支持待实测；本阶段锁 8.4，2027 年前评估升级（8.4 → 9.7 路径受支持） |
| MySQL 时区 | `DATETIME(6)` 强依赖 JVM UTC（spike 实测非 UTC 漂移 8 小时）；部署与测试必须设置 `-Duser.timezone=UTC`，否则改用 `TIMESTAMP(6)`（2038 上限） |
| Testcontainers 2.0 | 坐标改为 `testcontainers-*`，且 Boot 4 BOM 不传递模块版本，需显式 `testcontainers-bom`（已写入 §8.2） |
| 许可 | 若产品对外分发/自托管，MySQL GPLv2 需评估；PostgreSQL 许可更宽松 |
| 多实例演进 | 管理库先支持共享；投影库可继续每实例 SQLite，后续再评估共享索引 |
| 本地开发依赖容器 | 管理库无 SQLite 后，本地 `./gradlew test` 需 Docker（Testcontainers）；WSL2 需确认 Docker daemon 可用 |
| CI 载体缺失 | 当前仓库无 CI 配置，「双库同一组集成测试」需随 CI 骨架一起落地，不能只靠本地 |
