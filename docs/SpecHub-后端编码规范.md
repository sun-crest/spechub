# SpecHub · 后端编码规范

> **关联**：主线真源见 [SpecHub-产品方案.md](SpecHub-产品方案.md)；开发任务拆解见 [SpecHub-开发计划.md](SpecHub-开发计划.md)；编译级也随仓库共享的另见 root `CLAUDE.md`（Kotlin 可空性与 Java 互操作·方案 A，**硬纪律**，本文只引用不重述）。
> **适用**：`modules/server`（web 聚合层）、`modules/mock`、`modules/kernel/*`（领域/基础设施层）的 JVM 侧新代码。**存量代码不强制回改**（见 §12 收敛策略）。
> **日期**：2026-11-16　**状态**：草案（企业级基线 × 本项目现状对照后落地，待评审）。
> **分级**：`[强]` 必须遵守（不遵守即视为缺陷）；`[推]` 默认采用，有理由可偏离并注释；`[参]` 参考级，按上下文取舍。命名沿用业界《阿里巴巴 Java 开发手册》的三级惯例。

---

## 0. 为何需要这份规范

本项目是**小规模单仓、内核已有一套刻意架构**的 Spring Boot 4 + Kotlin（Java 25 / 虚拟线程）工程。
通用企业规范（分层、统一异常、声明式校验……）多数**方向正确但与本项目现实有冲突点**，一刀切照搬会破坏既有良构（尤其 kernel 零框架依赖哲学）。本文的写法是：

1. 区分「**架构性决策**」（§12 刻意保留，勿按通用规范"修正"）与「**工程性纪律**」（§1–§11）；
2. 给每个条目标注**针对本项目现实的落法**，而不是空泛的行业标准复述；
3. 新代码直接按规范写；存量留给 §12 的分批收敛清单。

---

## 1. 工程结构与分层

### 1.1 模块与依赖方向（硬约束）

```
modules/
  kernel/spec-model     纯数据模型（零第三方理想态）
  kernel/spec-parser    解析/升级/归一化（只依赖 spec-model）
  kernel/git-store      git CLI 镜像/读写（零 Spring）
  kernel/spec-index     索引/SQLite/投影（零 Spring）
  mock                  依赖 kernel 的 mock 规则引擎（零 Spring，部署并入 server 进程）
  server                Spring Boot 聚合层：REST / 摄取 / mock / MCP remote / 导出子进程
```

- `[强]` **依赖只朝 kernel**：`server` / `mock` 可以依赖 `kernel:*`，kernel 模块之间按上表方向单向；kernel 内**禁止依赖 server / Spring / mock**。本决策是 §7.4/§6.4 的既有硬约束，改动需经过方案评审。
- `[强]` kernel 模块**不引入 Spring 注解 / Web 容器 / 数据库框架**（可引入 jdbc 驱动实现层代码但仍保持零框架注解，现状 `IndexDb` 即如此）。
- `[推]` server 内部按职责分包：`web`（HTTP 入口）/ `service`（业务编排，新建）/ `config`（装配）/ `ingest`（后台摄取）。

### 1.2 分层边界

| 层 | 职责 | 禁区 |
|---|---|---|
| `web/*Controller` | HTTP 映射、请求参数收集与首轮校验、DTO↔Service 结果转换 | **不做业务规则、不直接调 Store 做多步编排、不写事务、不拼 SQL** |
| `service/*Service` | 业务规则、跨 Store/跨模块编排、事务边界、鉴权决策复用 | 不依赖 jakarta.servlet / HTTP 注解；不在方法内手写散装校验（用 §3） |
| `kernel:*Store` / `GitStore` | 单一存取原语（一个行为一个方法）、结果映射 | 不含业务 if/else 分支策略；不感知 HTTP/用户上下文 |

`[强]` **新增业务逻辑放 Service，不放 Controller**。当前 Controller 里已积累的 `requireManager`、`addChangelog` 编排、view 映射（典型如 `AuthController`）是**历史存量，新写端点按本条规范，存量列入 §13 收敛**。
`[推]` 仅「端点=一次 Store 读 + 一次映射」的薄查询可不建 Service，直接 Controller → Store（避免为单行操作造空 Service）。

### 1.3 包名与类名

- `[强]` 包名 `io.spechub.<模块>.`，类名遵循「**功能名词**」：Controller 后缀 `*Controller`、Service 后缀 `*Service`、配置 `*Properties` / `*Config`。
- `[推]` 不引入 `utils`/`common` 大杂烩包；小而内聚的辅助函数放所属功能类内部或独立 `support` 包。

---

## 2. 异常处理与错误契约

### 2.1 现状与目标

现状：全程手抛 `ResponseStatusException`，错误信息中文混在 `message`，且 `TokenAuthFilter` 手写的 `{"error": ...}` 与其余错误体**三处格式不一致**。目标：**统一结构化错误体 + 集中映射**。

### 2.2 错误体结构（`[强]` 新代码遵循）

```json
{
  "status": 404,
  "code": "API_NOT_FOUND",
  "message": "api 1024 不存在",
  "instance": "/api/apis/1024/details",
  "fieldErrors": null   // 校验类错误时携带：[{"field":"username","message":"..."}]
}
```

- `code` 是**机器可分支的主键**（前端/脚本据此判断，不解析中文）；`message` 面向人，默认与 HTTP 语义吻合。
- `[强]` 不再新增「裸 `ResponseStatusException` + 唯一中文 message」作为唯一错误语义来源；`code` 必须带上。存量暂容忍，新端点按此契约。

### 2.3 实现骨架（`[推]`）

- 自定义业务异常 `ApiException(status, code, message, fieldErrors?)`，Service 层 `throw`，**不 try-catch 裸包**；
- `@RestControllerAdvice` 集中处理：`ApiException`、`MethodArgumentNotValidException`（→ fieldErrors，400）、`HttpMessageNotReadableException`（→ 400）、未知 `Exception`（→ 500 兜底，`message` 对客户端只给脱敏短句 + `status`，完整栈进日志）；
- `[强]` **未知异常不得把堆栈/内部细节写入返回体**（`e.message` 直接回给客户端是反例）；500 类错误客户端只收到 `code=INTERNAL`。

### 2.4 错误码命名

`[推]` 前缀式：`AUTH_*` / `NOT_FOUND_*` / `CONFLICT_*` / `VALIDATION_*` / `INTERNAL`；`code` 全大写下划线。在 `ApiException` 侧维护一张常量表（或 enum），**杜绝字符串散落**。

### 2.5 Filter / 安全层错误体

`[强]` `TokenAuthFilter` 等非 MVC 层的拒绝响应（HttpServletResponse 直写）也要输出**同一结构**（至少 `status` + `code` + `message`），与 §2.2 一致，禁止再出现第三种裸 JSON 格式。

---

## 3. 参数校验

### 3.1 Kotlin 关键坑（`[强]`）

Bean Validation 注解在 Kotlin data class 上必须写 **`@field:` 前缀**，否则注解挂在 getter 上、**校验静默失效**：

```kotlin
data class LoginRequest(
    @field:NotBlank val username: String? = null,
    @field:NotBlank val token: String,
)
```

### 3.2 落法

- `[强]` 引入 `spring-boot-starter-validation` 已就位：请求 DTO 字段声明 `@field:NotNull / NotBlank / Size / Pattern / Min / Max`；Controller 参数加 `@Valid`（或类级 `@Validated` 以支持 `@PathVariable` 约束）。
- `[强]` **手写的 `isBlank()` / `Role.ALL.contains(...)` 类校验不再在新代码出现**为唯一手段；能用注解表达的上注解，无法注解表达的业务规则留 Service 判定并抛 `ApiException(VALIDATION_*)`。
- `[推]` 跨场景复用同一 DTO 时用校验分组（`@Validated(UpdateGroup::class)`），避免一套校验阻塞两种用例。
- `[推]` `@PathVariable` / `@RequestParam` 的类型约束也加（负数/空串类）——治理「`"   "` 这种传参与空串混检」的现状。

---

## 4. DTO 与模型

### 4.1 命名

- `[强]` 入参 DTO：`*Request`（如 `CreateUserRequest`）；出参**统一 `*Response`**（`*View` 属历史存量，见 §13 收敛项）；转换函数 `toResponse` / `toXxx`。
- `[强]` **不直接把 kernel 持久化记录（`*Record`）序列化给前端**；需要裁剪/脱敏/拼装的写 DTO。`[参]` 纯读、字段即所需时可豁免（仍建议套一层 `*Response` 保证演进自由）。

### 4.2 位置：嵌套 vs 独立文件

- `[推]` **Controller 文件 < 300 行且 DTO ≤3 个**时可留在类内嵌套（本项目现状），超过则拆出对应文件 —— 给出阈值是防止「规范正确性 > 邻域可读性」的本项目平衡点。
- `[推]` 被多个 Controller / Service 共享的 DTO 独立成文件，禁止复制粘贴同名结构。

### 4.3 可空性

- `[强]` 引用方案 A（root `CLAUDE.md`）：Jackson 树系等**未标注可空性 Java API 一律按 `Type?` 从严接收**；其上的再转换交给 data class 默认值主导。此条是编译级硬约束，**勿当 bug 修、勿加多余 `!!`**。

---

## 5. 事务

- `[强]` **多步写必须在同一事务内**（要么全部生效要么全部回滚）。当前典型缺口：`AuthController` 的「创建用户 + 加默认 project 成员 + 写 changelog」是三个无原子性的顺序调用；`ReviewController` 审批写回类似。
- 落法（`[推]`）：把这类多步写提进 **Service**，标 `@Transactional`；或对 `IndexDb` 使用其 SQLite 显式事务接口（`begin/commit` + 失败 `rollback`）。
- `[推]` 单条写原语（只有一次 `insert`）不需事务注解，避免过度包装。
- `[参]` SQLite 并发注意：写库保持单写者纪律（现有 `maxParallelForks=1` 与 WAL 取舍在 §7 产品方案），**不要在包长事务里穿插 git/Shell/网络调用**（长事务 + 文件 I/O 会拖住整库写锁）。

---

## 6. 日志

### 6.1 基线

- `[强]` 一律 `org.slf4j`（`LoggerFactory.getLogger(javaClass)`，仅此一个入口），**禁止 `System.out/err`、`printStackTrace`**。
- `[强]` 分级：`INFO` 有业务意义的生命周期事件（启动、reindex、审批落地）；`WARN` 可恢复的异常/边界（单仓扫描失败不中断）；`ERROR` 影响正确性的失败，**必须带上下文**（`{repoId=…, repoUrl=…}` 占位符而非字符串拼接）。
- `[推]` 敏感值纪律：**token / 密码 / 完整令牌不外泄日志**；认证类事件只记 `username` 与时间，不记凭据本身。

### 6.2 审计

- `[推]` 关键写操作（登录、创建用户/仓库、导入导出、评审落真源、删除）= **写 `changelog` 业务表 + 一条 INFO 日志双记录**（现状已部分如此，新端点补齐两者一致）。日志用于排查，changelog 用于产品回看，二者不互相替代。

### 6.3 请求日志（`[参]`）

- 当前无统一请求访问日志。加 `OncePerRequestFilter` 记 `method path status duration` 即可（**不记请求体**，尤其登录体）。占位符避免误打 token。

---

## 7. 配置

### 7.1 绑定

- `[强]` 分组配置统一走 `@ConfigurationProperties` + `SPECHUB_*` 环境变量（relaxed binding），**现状 `SpecHubProperties` 已是范例，继续沿用**；禁止在代码里 `System.getenv` 取参。
- `[推]` `@ConfigurationProperties` 类加 `jsr305`/校验注解表达约束（如必填项 `@field:NotNull`）——绑定失败启动即显式报错，避免运行期空指针。

### 7.2 环境

- `[推]` 引入 profile：`application.yml`（公共）+ `application-dev.yml` / `application-prod.yml` 差分；**默认值只为开发友好，生产必须显式提供**（admin-token 等安全项默认空 = 鉴权关闭，已在代码注释声明，保持该默认但要求 prod 覆盖）。
- `[推]` 敏感配置（token、外部凭据）仅经环境变量/secret 注入，**不进 `application*.yml` 入库**（现 `adminToken` 即环境注入，符合）。

---

## 8. RESTful 契约与 API 文档

- `[强]` 路径风格沿用现状：`/api/<资源>`，名词复数、`{id}` 路径参数、子资源 `/api/apis/{id}/comments`；HTTP 语义状态码（200/201/204/400/401/403/404/409）**现状已规范，保持**。
- `[强]` 集合接口要定义**排序/分页**（现状无分页，`List` 直返；数据量增长前先定契约：`?page&size` 或 cursor，避免后补破坏前端）。
- `[推]` API 文档二选一并**成文**：① 接入 `springdoc` 自动生成（新端点可逐步补注解）；② 维持文件头注释清单（现状）但需接受「机器不可读、易漂移」。**默认倾向 ①**，成本低且与验证/契约测试相得益彰；若选 ② 必须写明理由。
- `[参]` URL 版本化 `/api/v1`：当前内部工具无外部消费者，**不强制**；但对外暴露的那天先定。

---

## 9. 安全

- `[强]` 现状自研 `TokenAuthFilter`（Bearer + `AuthService`）是 **MVP 期决策，不否定但不扩展其架构**：**禁止继续往 filter 的 URL 白名单里加前缀**来放行新能力（现状 `/mock` 放行已属例外，须在代码注释标注理由）。
- `[强]` 新增受保护端点**默认拒绝，显式声明**：一次性令牌在内存校验过的端点留注释；凡涉及写操作，必须「鉴权 + 角色 ≥ editor」或在端点注释里说明为何豁免。
- `[推]` 正式化鉴权时整体评估 Spring Security（`@PreAuthorize` + 方法级），替代「每个 Controller 复制一份 `requireManager`」；**在此之前，抽取鉴权决策到 `AuthService` 单一入口**即可（现状 `requireManager` 三处重复已在 `AuthService` 侧可收敛）。
- `[强]` token 只存摘要（`sha256`，现状正确），日志/响应体一律不回显完整令牌。

---

## 10. 测试

- `[强]` `@SpringBootTest` 一律沿用 `TestDbIsolation` 模式（每测试类 `@TempDir` + `@DynamicPropertySource` 全新投影目录）；**禁止**新增共享固定目录的集成测试（历史 `SQLITE_BUSY` 教训）。
- `[强]` kernel 层逻辑（解析/升级/索引/git 行为）以**纯 JVM 单测**覆盖（无 Spring context），保 CI 快；server 层以**端到端 REST 测试**覆盖契约（现状 `web/*Test` 已是范本）。
- `[推]` 断言统一一种风格（现状 `json-path` + Kotlin `assertEquals`），新测试尽量同一家族，**别在同一类里混两套断言**。
- `[推]` 每新增端点配至少「happy path + 鉴权拒绝 + 参数非法」三类用例（对应 §2.3 错误契约）。

---

## 11. 命名与代码风格

- `[强]` Kotlin 官方风格 + 现状中文注释惯例：**文件头 `/* ... */` 块注释**写清楚「做什么、端点清单、决策关联」（现状每个 Controller 都有，**保持**它——它是无 springdoc 时期的主要文档）。
- `[强]` 分支/状态值用 enum（`Role.*` 现状正确），禁止裸字符串散落比较；跨层传参用类型化对象而非 `Any`/`Map`。
- `[强]` 遵守方案 A 第 4 条：**不写多余的 `!!`**；表达「应有但缺失」用 `checkNotNull(x) { "原因" }`。
- `[推]` 引入 `ktlint`（或 detekt）做静态检查 + `.editorconfig` 对齐格式；CI 门禁。**这是低成本高收益项，优先于大多数手写规范。**

---

## 12. 刻意保留的架构决策（勿按通用规范"修正"）

以下条目是**有意识地偏离通用 Spring 惯例**，评审人/新人**不许**当成"不符合规范"打回：

| 决策 | 为什么保留 | 规则边界 |
|---|---|---|
| kernel 零 Spring / 零 Web 依赖 | §7.4 硬依赖约束；解析/索引/存储与框架解耦，可独立演进与测试 | 只在 kernel 内部严禁框架；server 装配层可正常用 Spring |
| 自研 Bearer 鉴权（而非 Spring Security） | MVP 期对外接口有限、无外部消费者；最小面替代 | 见 §9：不扩白名单、正式化时整体评估 |
| `@ConfigurationProperties` 单类承载全部 `spechub.*` | 配置总量小、聚查看；分拆成本 > 收益 | 超过 ~10 个键或出现明显分域（如 `mock.*`/`ingest.*`）再分 |
| actuator 仅暴露 `health,info` | 单机 compose 部署，healthcheck 已够 | 加多副本/外部监控时再开 `prometheus` metrics |
| 错误 `message` 中文化 | 面向内部团队的确定性达意 | 中文只入 `message`，**机器分支必须靠 `code`**（§2.2），不得反依赖 |
| Controller 内嵌套小 DTO | 文件规模小，就近可读性好 | 达 §4.2 阈值即拆出 |

---

## 13. 存量收敛（暂不改代码，作为 backlog）

按「成本低、收益高」排序，入开发计划排期：

1. **新增统一错误契约** `ApiException` + `@RestControllerAdvice`（§2）——新端点先享受，存量端点 compat 表逐步迁移；
2. **`AuthService` 收敛鉴权决策**，消灭各 Controller 私有 `requireManager` 复制（§9）；
3. **多步写事务化**：`createUser`、审批、导入等（§5）；
4. **请求 DTO 注解化** + `@Valid`（§3），用 `fieldErrors` 替代散装 `isBlank` 判断；
5. **出参后缀统一 `*Response`**：`UserView→UserResponse`、`CommentView→CommentResponse`、`toView→toResponse`（低风险改名，§4.1）；
6. **引入 ktlint/detekt** + CI 门禁（§11）；
7. **springdoc 决策落地**（§8）；
8. 视需补：请求日志 / 分页契约 / CORS。

> 每条收敛需配回归用例（§10 第 4 条），改完在 [SpecHub-开发计划.md](SpecHub-开发计划.md) 勾选。

---

## 附：《阿里巴巴 Java 开发手册》/行业对照来源

本规范的「行业基线」依据（检索于 2026-11-16）：[阿里 Java 开发手册（英文目录）](http://alibaba.github.io/Alibaba-Java-Coding-Guidelines/) · [Spring Boot 4 / REST API 设计（PCSalt）](https://pcsalt.com/spring-boot/spring-boot-4-rest-api-design/) · [Amigoscode 10 大 REST 误区](https://amigoscode.com/blogs/top-10-spring-boot-rest-api-best-practices) · [Ryan Harrison Spring Boot 工程指南](https://guidelines.ryanharrison.co.uk/frameworks/spring-boot/spring-boot-api-design/) · [Kotlin × Boot 四层设计](https://rhcwlq89.github.io/en/blog/spring-boot-pre-interview-guide-1/) · [SivaLabs 结构与分层经验](https://www.sivalabs.in/blog/a-few-hard-learned-lessons-on-structuring/)。