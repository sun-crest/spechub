# SpecHub · 后端重构计划

> **关联**：规范真源见 [SpecHub-后端编码规范.md](SpecHub-后端编码规范.md)（本计划严格按其执行）；开发任务与里程碑见 [SpecHub-开发计划.md](SpecHub-开发计划.md)。
> **适用**：`modules/server`（web 聚合层）+ `modules/kernel/spec-index`（技术债触点）。**不覆盖**规范 §12 刻意保留的架构决策（自研鉴权、kernel 零框架、单配置类、Controller 小 DTO）。
> **日期**：2026-11-16　**状态**：R1/R2 已完成（2026-11-16 实测通过：server 全量 42 用例绿，含 `ErrorContractTest` 5 例 + `ValidationContractTest` 6 例）；R3–R12 待执行。
> **原则**：当前为开发阶段，**不考虑重构复杂度**，严格按规范 `[强]`/`[推]` 执行；全部任务**不改变任何公开契约**（路径、方法、成功响应字段、状态码语义）。

---

## 0. 结论先行（通读代码后的三个地面事实）

1. **单原语层面事务已到位**：[IndexDb.transaction](../modules/kernel/spec-index/src/main/kotlin/io/spechub/spec/index/IndexDb.kt) 是 `autoCommit=false` 的真事务，`SpecIndexStore` 每个方法独立成事务。故「无 `@Transactional`」**不是缺陷**，真正的缺口是**跨原语多步编排**（见 §四决策 1：`@Transactional` 在本项目不适用）。
2. **鉴权底座已具备收敛条件**：[AuthService](../modules/server/src/main/kotlin/io/spechub/server/web/AuthService.kt) 已有 `requireRepoRole / requireProjectRole / requireAuthenticated / effectiveRole` 全套，只差 `AuthController` 一份等价私有 `requireManager` 没收编、及 3 处重复的 `currentActor`。
3. **错误契约只能落 server 层**：kernel 零框架、以结果物/`Result` 表达成败（`SpecParser`→`ParseResult`、`GitStore`→`Result`），异常映射由 server 承载。

---

## 1. 任务全景图（4 阶段 · 12 任务 · 依赖）

```
阶段 0 基础契约     阶段 1 分层收敛         阶段 2 原子性     阶段 3 收尾
R1 统一错误契约 ──┐
R2 声明式校验 ────┤→ R3 鉴权收敛
                 │→ R4 Service 化(用户/订阅)──→ R6 复合原子写 ──→ R7 !!收敛
                 │→ R5 Spec 写回编排下沉                                ├→ R8 出参改 *Response
                                                                       ├→ R9 日志纪律+请求日志
                                                                       ├→ R10 配置分层
                                                                       ├→ R11 springdoc 落地
                                                                       └→ R12 ktlint/detekt+CI
```

依赖原则：**R1/R2 先行**（后续所有任务的异常/校验都建立在契约上）；**R3–R5** 在 R1/R2 之后可并行；**R6** 依赖 R4/R5 定型的 Service 形态；**R7–R12** 相互独立可穿插。每个任务完成后跑全量 `./gradlew test`（现有 81 用例为回归基线）。

---

## 2. 任务详述

### 阶段 0 · 基础契约（一次性建好，后续任务依赖）

#### R1 · 统一错误契约　`[强]`（规范 §2）

| | |
|---|---|
| **现状证据** | 78 处 `ResponseStatusException` 散布 16 个文件（最多 [AuthController.kt](../modules/server/src/main/kotlin/io/spechub/server/web/AuthController.kt) 14 处）；错误的机器语义在中文 `message` 里；[TokenAuthFilter.kt:55](../modules/server/src/main/kotlin/io/spechub/server/web/TokenAuthFilter.kt#L55) 与 [MockController.kt:45](../modules/server/src/main/kotlin/io/spechub/server/web/MockController.kt#L45) 手写 `{"error":...}` 第三方格式 |
| **改动** | ① 新建 `server/web/common/ApiException.kt`（`status/code/message/fieldErrors?`）；② 新建 `ErrorCode.kt`（enum，前缀族 `AUTH_/NOT_FOUND_/CONFLICT_/VALIDATION_/INTERNAL`）；③ 新建 `ApiExceptionHandler.kt`（`@RestControllerAdvice` 处理 `ApiException`/校验异常→fieldErrors/报文不可读→400/未知异常→500 脱敏）；④ `TokenAuthFilter` 与 `MockController.mock` 的拒绝体改走同一结构；⑤ 存量 78 处**替代优先**：新端点直接 `throw ApiException`，旧端点按 compat 表逐步迁移 |
| **测试** | 新增 `ErrorContractTest`（错误体结构断言 + 未知异常不进栈）；逐一核验并修正现有 `web/*ApiTest` 中对错误 JSON 的精确断言 |
| **验收** | 任何错误响应均含 `code`；仓库内无第三类 `{"error":...}`；500 响应体零内部细节 |
| **状态** | ✅ 2026-11-16 完成：78 处存量 `ResponseStatusException` 全部替换归零；`TokenAuthFilter`/`MockController` 并入统一错误体（消除第二、三种格式）；500 脱敏落实（DocumentController / TryController 详情进日志）；新增 `ErrorContractTest`（NOT_FOUND / MALFORMED_BODY / AUTH_UNAUTHORIZED / MOCK_NO_MATCH / 500 脱敏）＋ server 全量回归全绿 |

#### R2 · 声明式校验落地　`[强]`（规范 §3）

| | |
|---|---|
| **现状证据** | 已引 `starter-validation` 但零注解；18 处手写 `isBlank/isNotBlank/contains`（用户创建、`Role.ALL.contains`、导入、订阅、mock 规则、评审 status 等） |
| **改动** | ① 请求 DTO 声明 `@field:NotBlank/@field:Size/@field:Pattern`（**`@field:` 前缀，挂在 getter 上会静默失效**）；② Controller 写参数加 `@Valid`/类级 `@Validated`；③ `Role.ALL.contains` 型枚举校验移到「注解 + Service 判定抛 `ApiException(VALIDATION_*)`」；④ 校验失败由 R1 advice 出 `fieldErrors` |
| **测试** | 每个被改端点补「参数非法 → `fieldErrors` 非空」用例 |
| **验收** | 新代码零手写散装校验；缺参/空串/类型错统一 400 + `fieldErrors` |
| **状态** | ✅ 2026-11-16 完成：9 个请求 DTO 加 `@field:NotBlank` + `@Valid`（Login/CreateUser/UpdateRole/Comment/DocComment/Import/Subscribe/Rule/Register/Promote/WriteSpec/Try）；枚举值域类校验（role/type/status/ruleType/format/stage/baseUrl 协议/path 扩展名）统一升级为 `VALIDATION` 错误体 + `fieldErrors`；手写散装 `isBlank` 校验清零（残留 3 处为合法内部判空）；新增 `ValidationContractTest` 6 例 + server 全量回归绿 |

---

### 阶段 1 · 分层收敛

#### R3 · 鉴权决策收敛　`[强]`（规范 §9、§13 第 2 项）

| | |
|---|---|
| **现状证据** | [AuthController.kt:139-149](../modules/server/src/main/kotlin/io/spechub/server/web/AuthController.kt#L139-L149) 私有 `requireManager` 与 `AuthService.requireProjectRole` 等价；`currentActor` 在 AuthController、[SubscriptionController.kt:76](../modules/server/src/main/kotlin/io/spechub/server/web/SubscriptionController.kt#L76) 重复 |
| **改动** | ① `AuthController` 换 `auth.requireProjectRole(request, projectIdByName(DEFAULT_PROJECT), Role.ADMIN, op)`；② `AuthService` 补 `currentActor(req)` 单一实现；③ 遵守 §9 边界：**不**扩展 `TokenAuthFilter` URL 白名单 |
| **验收** | `grep requireManager` 归零；`currentActor` 仅在 `AuthService` 一处定义 |

#### R4 · Service 化：用户域 + 订阅域　`[强]`（规范 §1.2、§1.1 新增 `service` 包）

| | |
|---|---|
| **现状证据** | `AuthController.createUser` 内联「createUser + setMembership + addChangelog」三步；`ReviewController` 审批编排；`SubscriptionController.subscribe` 内嵌「apiId 尽力关联」业务规则 |
| **改动** | ① 新建 `server/service/UserService.kt`（`createUserWithMembership/setRole/resetToken/deleteUser`，编排 + changelog 收口，内部用 R6 复合原语）；② 新建 `server/service/SubscriptionService.kt`（`subscribe/unsubscribe` 含关联规则 + changelog）；③ 评论/文档评论/mock 规则等**单次 Store 写 + changelog** 的小写路径暂以 R6 复合 API 收口（§1.2 允许薄 Controller） |
| **验收** | 涉改 Controller 只剩 HTTP 映射 + 校验 + 转换；业务规则在 Service |

#### R5 · Spec 写回编排下沉　`[强]`（规范 §1.2）

| | |
|---|---|
| **现状证据** | [ReviewController.approve:91-105](../modules/server/src/main/kotlin/io/spechub/server/web/ReviewController.kt#L91-L105)：「writeSpec(git)→ingest→resolveReview→addChangelog」4 步编排；[ApiQueryController.writeSpec:73-102](../modules/server/src/main/kotlin/io/spechub/server/web/ApiQueryController.kt#L73-L102)「鉴权走评审 / 非鉴权直写」分支业务规则在 Controller；`ImportController`、`RepoController.register` 同款「writeSpec+ingest」 |
| **改动** | ① 新建 `server/service/SpecWriteService.kt`：`writeOrQueue(repo, path, content, actor)`（含鉴权→评审分支）、`approve(...)`（全序编排）、`reject(...)`、`importWrite(...)`；② `ReviewController / ApiQueryController / ImportController / RepoController` 编排全部下沉；`requireOtherReviewer` 规则上移；③ `SpecWriter`/`Ingestor` 维持基础设施角色 |
| **测试** | `ReviewApiTest` 补「approve 全链路状态一致」用例 |
| **验收** | 涉改 4 个 Controller 的 git+数据库编排引用归零；鉴权/非鉴权分支只在 Service 一处判定 |

---

### 阶段 2 · 写路径原子性

#### R6 · 复合原子写　`[强]`（规范 §5，落法见 §四决策 1）

| | |
|---|---|
| **现状证据** | 用户创建三独立事务（中途失败留「半用户」）；`approve` 的 git 写与 DB 状态无次序承诺 |
| **改动** | ① `SpecIndexStore` 新增复合原语 `createUserWithMembership(username, displayName, tokenHash, projectId, role): UserRecord`（单 `db.transaction`）；② git 侧固定次序「先落 review/直写记录 → git commit+push → 索引重建」，失败抛错即停、重试幂等（git/specHash 幂等已具备），Service 注释写清承诺语义；③ 小写路径「写 + changelog」复合化 |
| **测试** | kernel 层 `SpecIndexStoreTest` 补「中途失败全量回滚」（注入失败点断言 membership 也不存在） |
| **验收** | 所有「结果一致性要求」的写路径：要么单事务、要么有明确次序与失败重试语义（注释注明） |

---

### 阶段 3 · 收尾

#### R7 · `!!` 收敛　`[强]`（规范 §11 + root CLAUDE.md 第 4 条）

| | |
|---|---|
| **现状证据** | main 侧 38 处 `!!`（[ReviewController.kt](../modules/server/src/main/kotlin/io/spechub/server/web/ReviewController.kt) 5、[CommentController.kt](../modules/server/src/main/kotlin/io/spechub/server/web/CommentController.kt) 2、[DocController.kt](../modules/server/src/main/kotlin/io/spechub/server/web/DocController.kt) 1 等，多为 `record.id!!`） |
| **改动** | 逐处评估：SQLite 必有 id → `checkNotNull(x){"原因"}`；编译器已确认非空的 → 删除；`user.id!!` → 走已含 null 判定的 `effectiveRole` 签名 |
| **验收** | main 侧 `grep "!!"` 归零（kernel 确需处留注释说明） |

#### R8 · 出参 DTO 统一 `*Response`　`[强]`（规范 §4.1、§13 第 5 项）

| | |
|---|---|
| **现状证据** | `UserView/CommentView/DocCommentView/ReviewView/ReviewDetail` + `toView` |
| **改动** | 统一改 `UserResponse/CommentResponse/DocCommentResponse/ReviewResponse/ReviewDetailResponse`；`toView→toResponse`；跨文件共享的独立成文件（§4.2 阈值）。**JSON 字段不变**，纯改名 |
| **验收** | main 侧无 `*View`/`toView` 残留；既有测试通过（无行为变更） |

#### R9 · 日志纪律 + 请求日志　`[强]`（规范 §6）

| | |
|---|---|
| **现状证据** | 仅 [ApplicationInit.kt](../modules/server/src/main/kotlin/io/spechub/server/ingest/ApplicationInit.kt)、[ReindexRunner.kt](../modules/server/src/main/kotlin/io/spechub/server/ingest/ReindexRunner.kt) 有 slf4j；无请求访问日志 |
| **改动** | ① 新增 `RequestLogFilter`：`method path status durationMs`（**不记请求体/响应体/token**）；② 关键编排落 INFO（`writeOrQueue/approve/import/register`，与 changelog 互补不替代）；③ 审计一致核对 |
| **验收** | 每个 HTTP 请求一条访问日志；token 关键字日志零出现（grep 审计） |

#### R10 · 配置分层　`[推]`（规范 §7）

| | |
|---|---|
| **改动** | ① `application.yml` 拆公共 + `application-dev.yml`（默认）+ `application-prod.yml`；② `SpecHubProperties` 加 `@Validated` + 必填项校验（prod 安全项未配 → **启动即 fail**）；③ logback 基础配置 |
| **测试** | prod profile 冒烟「缺必填项 → 启动失败而非运行期降级」 |
| **验收** | profile 隔离生效；安全配置缺失在启动期显式报错 |

#### R11 · springdoc 落地　`[推]`（规范 §8）

| | |
|---|---|
| **改动** | 引入 `springdoc-openapi-starter-webmvc`；新/重构端点逐步补 `@Operation/@Tag`；`/v3/api-docs` + swagger-ui 可用；迁移期保留文件头注释清单为唯一文档，直到全端点带注解 |
| **测试** | 冒烟断言 `/v3/api-docs` 200 |
| **验收** | API 文档机器可读；注释清单信息无丢失 |

#### R12 · 工具链：ktlint/detekt + CI　`[推]`（规范 §11）

| | |
|---|---|
| **改动** | 根构建加 `ktlint`（或 detekt）+ spotless；`.editorconfig`；server/kernel test 挂 gate；先以 `warningsAsErrors=false` 跑通再收严 |
| **验收** | `./gradlew ktlintCheck` 绿 |

---

## 3. 关键机制决策（任务落法前提，评审重点）

**决策 1：`@Transactional` 在本项目不适用，规范 §5「Service 层 @Transactional」按下述落法实现**
- kernel 用 JDBC 自管理连接（`DriverManager` + `IndexDb.transaction`），不在 Spring 事务管理器内——**加 `@Transactional` 实际无效果，不得按字面加**。
- 落法 a（纯 DB 多步）：`SpecIndexStore` 提供**复合原语**，单 `db.transaction` 内完成（R6）。
- 落法 b（git 写 + DB 状态，物理不可原子）：固定次序 + 失败即停 + 重试幂等，Service 注释写清承诺语义（R5/R6）。

**决策 2：错误体变更向后兼容**：新增 `code/instance/fieldErrors` 为纯增量字段，不破坏既有前端契约；现有测试按 R1 测试项逐一核验。

**决策 3：公开契约不变**：全部任务**不改路径、方法、成功响应字段、状态码语义**；`*View→*Response` 是 JSON 字段不变的改名。

---

## 4. 建议实施顺序与规模预估

| 序 | 任务 | 规模估计 |
|---|---|---|
| W1 | R1 + R2（契约与校验基建） | +4 文件，触 16 文件 |
| W1–2 | R3 + R4（鉴权收敛 + 用户/订阅 Service） | +2 文件，触 5 文件 |
| W2–3 | R5 + R6（写回编排下沉 + 复合原子写） | +2 文件 + kernel 复合法，触 5 文件 |
| W3 | R7 + R8（`!!` 与 DTO 改名，纯机械） | 触 ~8 文件 |
| W3–4 | R9 + R10（日志 + 配置） | +2 文件 + yml/logback |
| W4 | R11 + R12（springdoc + 工具链） | +1 依赖 + CI config |

每任务完成后跑全量 `./gradlew test`，并在 [开发计划](SpecHub-开发计划.md) 勾选。**本计划批准前不改任何代码。**