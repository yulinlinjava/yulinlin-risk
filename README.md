# yulinlin-risk

独立部署的用户行为风控服务，单个 Spring Boot 应用包含 Admin，使用本地 H2。Java SDK 支持手工上报及方法注解采集，服务端按项目和用户汇总多种行为。聚合统计保留最近 24 小时，完整上报事件和抽样明细按发生时间保留最近 12 小时。

让其他 AI 接入业务项目时，可直接提供 [AI 用 Java SDK 接入指南](doc/SDK_AI_INTEGRATION.md)，包含五字段手工上报、本地黑名单检查、完整代码和验收清单。

## Maven 工程结构

根 POM 是父工程和聚合入口，统一管理版本与构建配置，包含 `server` 和 `sdk` 两个模块：

```text
yulinlin-risk/
  pom.xml       yulinlin-risk-parent，父工程与聚合入口
  server/
    pom.xml     yulinlin-risk-server，可运行的服务端应用
    src/        服务端、Admin 与测试
  sdk/
    pom.xml     yulinlin-risk-sdk，普通依赖 JAR
    src/        SDK 源码与测试
```

在仓库根目录选择所需的构建范围：

| 用途 | 命令 |
| --- | --- |
| 测试并打包全部模块 | `mvn verify` |
| 测试、打包并安装全部模块到本地仓库 | `mvn install` |
| 只安装 SDK 及其父 POM | `mvn -pl sdk -am install` |
| 只测试并打包服务端及其父工程 | `mvn -pl server -am verify` |

SDK 单独安装命令不会构建服务端，也不需要服务端的 `security-risk`、`security-spring` 依赖。`-am` 会一并安装父 POM，保证其他项目能够解析 SDK 的依赖配置。其他业务项目仍只需声明 `com.yulinlin:yulinlin-risk-sdk:0.2.0`，不需要依赖服务端或 H2。

安装目标由 Maven 的 `settings.xml` 中 `localRepository` 决定，默认是用户目录下的 `.m2/repository`。业务项目和 IDE 使用同一仓库即可直接解析 SDK。其他机器需要另行安装，或使用团队 Maven 私服。

## 构建与启动

完整构建和服务端需要 JDK 25；单独构建 SDK 可使用 JDK 17+，面向 Spring Boot 3 MVC。服务端使用本地 yulinlin-orm 完成建表、CRUD、批量写入和事务，通过 `com.yulinlin:security-risk:3.0` 保留旧版播放行为接口，并使用 `com.yulinlin:security-spring:3.0` 管理 Admin 登录、会话、权限和限流。以下命令均从仓库根目录执行：

```powershell
mvn -f ../yulinlin-data/pom.xml -pl "common,h2,security-risk,security-spring" -am clean install -DskipTests
mvn install
java -jar server/target/yulinlin-risk-server-0.2.0.jar
```

访问 `http://127.0.0.1:8080/admin/`。首次访问会进入注册页，自行设置管理员账号和密码；创建成功后登录后台，再创建项目并保存 API Key。账号为 3–64 位字母、数字、下划线、点或短横线，密码至少 8 个字符且 UTF-8 编码不超过 72 字节。首个管理员创建后注册入口自动关闭，账号与密码哈希保存在 H2，重启继续使用，无需设置 `RISK_ADMIN_PASSWORD`。升级后已有管理员继续使用原账号密码；库中尚无管理员时才需注册，已有项目和黑名单不变。

项目表格可直接查看、复制 API Key，也可确认后重置。重置立即使旧密钥失效，需更新使用该项目的业务端配置，项目、行为和黑名单数据保留。新建或重置的密钥保存在 H2 项目表中供管理员再次查看；旧库只有摘要的密钥无法还原，可继续使用原配置或按需重置。

从仓库根目录启动时，H2 默认仍位于 `data/risk.mv.db`。数据库默认路径相对于进程工作目录；若改为从 `server` 目录启动，需用 `RISK_DB_FILE` 指向原库，避免打开另一份数据文件。详见 [Java SDK 对接指南](JAVA_INTEGRATION.md)。

框架的 `core`、`jdbc`、`h2`、`common`、`security`、`security-risk` 和 `security-spring` 必须来自同一份源码构建；本地同为 `3.0` 的旧 JAR 也可能使用不同 API。上面的 `-am clean install` 会同步安装所需上游模块，避免混用不兼容的版本。

PHP、Node.js 等项目参考 [通用 HTTP 对接指南](HTTP_INTEGRATION.md)，含字段说明、批量重试、黑名单缓存和可运行示例。后台可创建多个项目，业务接口通过各项目独立 API Key 隔离数据；同一项目的不同语言后端可以共同上报。

Windows 如出现 `Unable to establish loopback connection`，可使用可写的短临时目录：

```powershell
java "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path server/target).Path)" -jar server/target/yulinlin-risk-server-0.2.0.jar
```

默认仅绑定回环地址。跨机器部署可使用 HTTPS 反向代理，或配置 `RISK_BIND_ADDRESS`、`RISK_PORT`。HTTPS 下设置 `RISK_SECURE_COOKIE=true`。可用 `RISK_DB_FILE`、`RISK_DB_PASSWORD` 配置 H2。

## Admin 安全与限流

Admin 使用 `security-spring` 的统一认证与 `SpringSessionSupport` Cookie 会话。`POST /login` 通过框架校验账号密码，`POST /logout` 注销框架会话，管理接口以 `@ApiRole("ADMIN")` 校验权限。登录、首次注册入口标注 `@ApiPublic`，注册代码仍限制只创建一个管理员。表单及管理写请求继续校验 CSRF。管理员账号仍由 ORM 存储在原 H2 表中，不新增权限表、JWT 或密钥配置。

限流直接使用框架 `@ApiLimit`，按请求 IP 和接口分别计数，作用于当前服务实例：

| 入口 | 每分钟上限 |
| --- | --- |
| `POST /login` | 10 次 |
| 首次注册 `POST /setup` | 5 次 |
| 各 Admin API | 每个接口 120 次 |

超限返回 `429`，并通过 `Retry-After` 告知重试等待时间。默认 `yulinlin.security.limit.trusted-proxies: []`，按直连客户端 IP 计数；使用反向代理时，在服务端配置中明确填写实际可信代理地址，不能仅凭来访者提交的转发头确定 IP。

业务 API 通过 `@ApiPublic` 声明无需 Admin 登录，每个接口仍在代码中校验项目 `X-API-Key`，不会匿名开放项目数据。SDK 的批量上报、60 秒黑名单刷新和失败保留旧缓存行为不变。

## 数据库初始化

启动时扫描 [RiskTables](server/src/main/java/com/yulinlin/riskapp/schema/model/RiskTables.java) 中声明 `@JoinTable(autoSchema = true)` 的结构实体，用 yulinlin-orm 的 `H2SchemaManager.createTableSql` 生成并执行建表语句。实体统一维护 10 张表（含管理员账号与完整事件表）的字段、文本长度、大文本和单字段主键，生产资源中不再维护完整的 `CREATE TABLE` 脚本。

[RiskSchemaInitializer](server/src/main/java/com/yulinlin/riskapp/schema/RiskSchemaInitializer.java) 继续使用 Spring 的同一个 DataSource，并在业务组件访问数据库之前完成初始化。框架暂不支持的复合主键、自增列、非空、默认值和外键，由 [RiskSchemaConstraints](server/src/main/java/com/yulinlin/riskapp/schema/RiskSchemaConstraints.java) 检查后补齐；索引保留 [schema-indexes.sql](server/src/main/resources/schema-indexes.sql) 中的原名称和列顺序，避免旧库出现重复索引。已有数据和自增序列不会重置，初始化中断后再次启动可补齐尚未创建的约束。

首次启动自动建表。本次 IP 升级使用 ORM 为已有 `risk_global_sample` 自动补齐可空的 `IP` 列（最多 45 字符），已有明细保留且 IP 为 null，不改写旧数据。除此之外，已有表的字段新增、重命名或类型变更仍需显式迁移，本项目不会自动重建表。业务请求期间不执行建表逻辑。原 SQL 仅作为测试资源，验证旧库升级兼容性。

事件页使用新增的 `risk_global_event` 表，重启升级后的服务时自动创建；只记录升级后成功接收的 v2 上报，不从旧抽样数据补造完整事件。

## ORM 数据访问

[RiskOrmConfiguration](server/src/main/java/com/yulinlin/riskapp/persistence/RiskOrmConfiguration.java) 将现有 Hikari DataSource 注册为 ORM 的 `risk` Session，沿用同一个 H2 文件和连接池。表实体同时用于建表与运行时映射，业务层统一注入框架 `RouteSession`，不再使用 `JdbcTemplate` 或 Spring `TransactionTemplate`。

- 项目、黑名单、行为与状态的基础 CRUD 使用框架 Wrapper 和 Request；自增 ID 保持 `null`，由 H2 生成。
- 事件批量写入由 ORM 统一发送 JDBC batch。最近 12 小时的完整事件、全局统计、状态、样本、采样额度和批次收据放在同一个 ORM 事务中，事务提交成功后才更新内存窗口并返回 ACK；相同批次重试不会重复插入事件。
- 命中依据、全局汇总详情和用户窗口使用实体中的集合/对象字段，由 ORM JSON 编解码器直接读写；沿用原 CLOB 列及 JSON 格式，无需迁移旧数据。批次内容摘要仍使用原序列化方式，保持重试幂等。
- 黑名单增删使用 ORM 行锁及原子递增，版本与名单在同一事务提交；分页仍由一条参数化 SQL 返回条目、总数及版本。框架通用分页会分开计数和读取，不适用于这个版本快照要求。
- 复杂统计、复合键 `MERGE` 和有界清理使用框架原生 SQL Request。所有业务参数绑定；查询明确以 Java 属性名作为带引号的列别名。可空字段通过实体插入时省略，不向原生参数 Map 传 `null`。
- 项目 API Key 缓存使用框架 `afterCommit` 更新；外层事务回滚不会留下可鉴权的项目。风控查询显式关闭 ORM 查询缓存和自动写回，避免陈旧结果或查询触发额外写入。

ORM 事务与 Spring JDBC 事务不共享连接上下文；后续新增业务数据访问也应通过同一个 `RouteSession`，使用 `orm.transaction(...)` 划定事务，不要混入直接 JDBC 写入。

服务端保留有容量上限的写入队列，负责过载拒绝、同批事务和提交后确认；ORM 延迟写队列不能等价替代这些业务约束。完整事件页使用发生时间和内部 ID 游标、多取一条判断 `hasMore`，不做全表计数或深 OFFSET；原用户抽样历史继续使用页码分页。汇总批量读取使用完整复合主键 `(project_id, user_id, bucket_at)`，避免 H2 扫描整个项目。未额外封装通用 DAO 或队列框架。

## Java 接入

引入 `com.yulinlin:yulinlin-risk-sdk:0.2.0`，启用 `yulinlin.risk.enabled`，配置地址和 API Key，在选定业务接口中手工上报五个字段即可。身份从业务项目已有认证上下文取得；不需要更换登录体系。

直接调用 `riskClient.tryReport(userId, resourceId, ip, occurredAt, url)`：默认行为为 `access`，需要分类时使用 `tryReport(userId, action, resourceId, ip, occurredAt, url)`。用户 ID、IP 来自可信业务请求上下文，发生时间是业务记录的 Unix 毫秒，URL 建议传路径或模板；SDK 自动生成事件 ID，无需统计接口耗时。IP 随完整事件和抽样/异常明细保留最近 12 小时，在后台展示；旧客户端缺失 IP 时显示 `—`。现有规则不变，不新增 IP 判定。原有 `@RiskReport` 注解入口仍可选用，同一行为不要同时通过注解和手工调用上报。详见 [Java 对接指南](JAVA_INTEGRATION.md)。

SDK 使用有界队列、延迟批量上报和有限退避重试，并提供黑名单缓存。默认累计 100 条或等待 1 秒即发送，`flush-interval` 可配置到 30 秒，`batch-size` 最大 200。上报失败不改变业务响应；丢弃与失败可通过 SDK metrics 查看。SDK 不自动拒绝请求，业务方调用 `isBlacklisted` 决定限制策略。

Java SDK 启动后立即异步同步黑名单，每轮结束后等待 60 秒再次同步。已有完整缓存且第一页版本、总数未变时跳过后续分页；版本变化时完整拉取并一次性替换，失败保留旧名单。60 秒是刷新间隔，缓存不会到点删除。业务请求只查本地内存；完整手工上报与黑名单检查示例见 [Java 业务端示例](examples/java/README.md)。

## 接口

业务接口使用 `X-API-Key`，由服务端确定项目。

| 接口 | 用途 |
| --- | --- |
| POST /api/v2/batches | 接收 SDK 事件批次，提交后确认；同项目相同 batchId 与内容幂等。 |
| GET /api/v2/users/{userId}/risk | 查询跨接口的当前风险及当前黑名单状态。 |
| GET /api/blacklist?page=1&pageSize=500 | 分页同步黑名单，响应带 version；SDK 完整版本一致才替换缓存。 |

旧 `POST /api/events` 和旧风险查询接口仅用于已有播放行为客户端兼容。新 SDK、Admin 主界面均使用 v2；两条管线的数据不会自动混合。

Admin 新增“事件”页：选择项目后查看最近 12 小时内成功接收的完整 v2 上报，可按用户 ID 精确筛选，每页 50/100/200 条，支持上一页、下一页和刷新。展示发生时间、用户 ID、资源 ID、IP、URL、行为和事件 ID，按发生时间与内部 ID 倒序排列。对应接口为 `GET /admin/api/projects/{projectId}/v2/event-log`，使用管理员登录会话，见 [事件分页接口](HTTP_INTEGRATION.md#admin-事件分页接口)。未接入或未送达服务端的业务请求不会出现在这里。

## 统计与证据

- 仅对业务方选定的接口统计。没有采集到某个行为，不作为恶意证据。
- 按用户汇总跨接口行为，使用发生时间，结合持续请求强度、资源遍历和拒绝响应判断；一次正常页面并发和深夜在线不直接命中。
- 在线使用短窗口增量统计，持久化用户窗口供恢复；五分钟汇总用于 Admin 趋势。历史查询显示实际桶边界，不能精确还原任意秒级区间。
- 用户报告中的正常与异常明细仍有采样额度，界面标注为抽样片段；独立事件页保留最近 12 小时的完整已接收上报。两者均不代表站点所有请求，也不提供任意规则重算。
- 批次收据、聚合、窗口状态、完整事件及样本由 H2 事务协调；重试须保持 batchId 和内容不变。跨批次相同 eventId 不去重，客户端不可把失败批次拆分重发。
- 超过在线窗口的延迟事件不进入实时判定；发生在最近 12 小时内的仍保存事件明细，12～24 小时的补报只进入统计。容量不足和采样限制须结合报告标记判断。
- 完整事件和抽样/异常明细按发生时间滚动保留 12 小时，统计趋势、汇总风险依据、窗口和批次收据保留 24 小时。定时清理按有限批次独立提交，事件查询始终过滤掉 12 小时以前的数据。管理员账号、项目与黑名单独立保留，管理员手动管理黑名单，风险命中不自动封禁。
- 单实例运行；接收、分析查询和证据保存均有上限。当前实现的吞吐需用实际部署和流量分布压测，尚未验证“10 万日活”的容量目标。
