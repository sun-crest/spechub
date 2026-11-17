# SpecHub · 事务边界审计（Phase 3）

> **日期**：2026-11-17　**范围**：`modules/server`（管理域 + 编排）+ `modules/kernel/spec-index`（投影层）。
> **前提**：Phase 1/2 已完成——管理数据全部在管理库（PG/MySQL），投影库只剩 `version/api/schema/doc`。
> **验证**：PostgreSQL 18.6 与 MySQL 8.4.11 全量用例通过。

---

## 1. 结论摘要

| 检查项 | 结果 |
|---|---|
| 管理域多表写是否有事务边界 | ✅ 各 Service 已有 `@Transactional` |
| 投影层是否保留显式事务 | ✅ `IndexDb.transaction`；本轮新增摄取复合原语 |
| 是否有 `@Transactional` 包住 git/网络/Shell | ✅ 无（git 编排全在 Controller/Writer 层） |
| 跨库编排是否有固定次序 + 幂等 | ✅ 已明确；本轮修复两处不幂等 |
| 审批状态机 | ✅ 本轮改为条件更新（并发兜底 409） |

---

## 2. 事务边界现状

### 2.1 管理域（单一 DataSource）

| 写路径 | 事务边界 | 说明 |
|---|---|---|
| `UserService.bootstrapAdmin` | `@Transactional` | 查 + 更新/插入 |
| `UserService.createUserWithMembership` | `@Transactional` | 用户 + 成员两表 |
| `UserService.deleteUser` | `@Transactional` | 先删成员再删用户（FK RESTRICT） |
| `UserService.setMembership` | `@Transactional` | 先查后写 |
| `RepoService.ensureProject / register` | `@Transactional` | 项目 + 仓库 |
| `SubscriptionService.subscribe / unsubscribe` | `@Transactional` | 先查后写 / 单表删 |
| `ReviewService.resolve` | `@Transactional` + 条件更新 | 本轮改为 `WHERE status='pending'` |
| `MockRuleService.upsert / delete` | `@Transactional` | 先查后写 |
| `ReleaseService.promote` | `@Transactional` | 单表 upsert |
| `CommentService.add` / `ChangelogService.add` / `Notification.add` | 单表 | 无需事务包装 |

### 2.2 投影层

- `IndexDb.transaction`：每操作独立连接 + 显式提交/回滚。
- **本轮新增** `ProjectionStore.replaceProjectionForRepo(repoId, apis, schemas, docs)`：
  一次摄取的 api/schema/doc 全量替换在**单事务**内完成，消除「api 已更新、schema/docs 未更新」的中间态。
- `version` 的 upsert 仍在摄取解析循环内（specHash 幂等），先于复合写完成。

### 2.3 跨库 / git 编排（不做分布式事务）

| 链路 | 固定次序 | 幂等/重试语义 |
|---|---|---|
| Web 直写 spec | `writeSpec(git) → ingest(投影+管理库状态)` | git 内容相同 → 不重复提交；摄取幂等 |
| 评审 approve | `writeSpec(git) → ingest → resolve(管理库) → changelog` | 重试：writeSpec 幂等、ingest 幂等、resolve 条件更新（已处理返回 409） |
| 发布 promote | `releaseService.promote(管理库) → projection.promotePath(投影) → changelog` | 重试：管理库 upsert 幂等、投影 promote 幂等 |
| 摄取 reindex/轮询/webhook | `ensureMirror → parse → replaceProjectionForRepo → replayReleases → updateSyncStatus` | 投影可重建；syncStatus 失败可重跑 |

---

## 3. 本轮修复

### 3.1 摄取投影写非原子

- **问题**：`Ingestor` 依次调用 `replaceApisForRepo` / `replaceSchemasForRepo` / `replaceDocsForRepo`，各自独立事务；中途失败会留下混合投影。
- **修复**：新增 `ProjectionStore.replaceProjectionForRepo`，三个替换在单事务内完成；`Ingestor` 改为一次调用。

### 3.2 审批竞态

- **问题**：`requirePending` 检查与 `resolve` 无条件更新之间存在竞态，两人同时审批可能都成功。
- **修复**：`ReviewRepository.resolveIfPending / rejectIfPending` 用 `UPDATE ... WHERE status='pending'` 返回影响行数；`ReviewService.resolve` 返回 Boolean；Controller 对 `false` 抛 409。测试覆盖「已处理评审不可再次迁移」。

### 3.3 写回重试不幂等

- **问题**：`SpecWriter.writeSpec` 对相同内容再次提交会因 `git commit` 无变更而失败，导致审批重试失败。
- **修复**：写入后检查 `git status`，目标文件无变更则直接返回当前 HEAD（不重复 commit/push/changelog）。
- **注意**：`git status` 对未跟踪目录会折叠为 `?? specs/import/`，匹配时必须按目录前缀判断（本轮实测踩到）。

---

## 4. 遗留项（不阻塞当前验收）

| 项 | 风险 | 建议 |
|---|---|---|
| 并发 upsert 先查后写 | `RepoService.register`、`SubscriptionService.subscribe`、`MockRuleService.upsert`、`UserService.bootstrapAdmin` 在并发下可能撞唯一约束（未被捕获） | 捕获 `DataIntegrityViolationException` 后重查，或改用条件更新 |
| changelog 与业务写同库不同事务 | 业务成功、审计失败时缺一条留痕 | 把 audit 作为 Service 参数收口到同一 `@Transactional`（Phase 2 迁移后已具备条件） |
| 通知先于投影写 | 摄取失败时通知已发出（投影未更新） | 通知移至复合投影写成功之后；或按 specHash 去重 |
| git 与状态迁移非原子 | approve 在 git 成功后、resolve 前崩溃 → 重试可恢复（已幂等） | 可加「进行中」状态，当前可重试语义已足够 |

---

## 5. 验证

```bash
./gradlew test --rerun-tasks                        # PostgreSQL 18.6
./gradlew :server:test -Dspechub.test.db=mysql --rerun-tasks   # MySQL 8.4.11
```

两套均通过。
