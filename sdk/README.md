# yulinlin-risk Java SDK

Java 17+、Spring Boot 3 Servlet MVC 的行为上报客户端，默认关闭。业务提供用户 ID、资源 ID、IP、行为发生时间和 URL，SDK 自动生成事件 ID，使用有界队列延迟批量上传；无需统计接口耗时。不支持 Spring Boot 2 的 `javax.servlet` 或 WebFlux。

在仓库根目录安装 SDK 及其父 POM：

```shell
mvn -pl sdk -am install
```

从 `sdk` 目录执行时使用 `mvn -f ../pom.xml -pl sdk -am install`。命令包含测试、打包和安装，只选择 SDK 及 `com.yulinlin:yulinlin-risk-parent:0.2.0`，不构建服务端、不依赖 H2 或 `security-risk`。产物为普通 JAR：`sdk/target/yulinlin-risk-sdk-0.2.0.jar`。

安装位置以 Maven `settings.xml` 的 `localRepository` 为准，默认 `~/.m2/repository`。业务项目和 IDE 使用同一仓库；其他机器需另行安装，或使用同时提供父 POM 和 SDK 的团队 Maven 私服。

```xml
<dependency>
    <groupId>com.yulinlin</groupId>
    <artifactId>yulinlin-risk-sdk</artifactId>
    <version>0.2.0</version>
</dependency>
```

在风控后台创建项目并保存 API Key，业务应用配置：

```yaml
yulinlin:
  risk:
    enabled: true
    base-url: http://127.0.0.1:8080
    api-key: ${RISK_API_KEY}
    batch-size: 100
    queue-capacity: 4096
    flush-interval: 1s
    blacklist-refresh-interval: 60s
```

注入单例 `RiskClient`，在行为发生时调用：

```java
long occurredAt = System.currentTimeMillis();
boolean queued = riskClient.tryReport(trustedUserId, "video:123",
        request.getRemoteAddr(), occurredAt, "/videos/{videoId}");

// 需要区分行为时，多传一个 action。
riskClient.tryReport(trustedUserId, "comment-view", "video:123",
        request.getRemoteAddr(), occurredAt, "/videos/{videoId}/comments");
```

主入口为 `tryReport(userId, resourceId, ip, occurredAt, url)`，action 默认为 `access`；分类入口为 `tryReport(userId, action, resourceId, ip, occurredAt, url)`。资源可为 null，同一资源跨行为使用相同 ID，建议 `video:123`、`article:123`，不要用 action 或随机请求 ID 区分同一资源。

用户 ID 来自业务服务端认证上下文。IP 非空、最多 45 字符，不做 DNS 查询；直接连接可读 `request.getRemoteAddr()`，经过代理时由可信配置解析来源，不直接信任客户端提交的 `X-Forwarded-For`。URL 非空、最多 256 字符，建议路径或接口模板，不含 Token 或查询串。发生时间是行为发生时记录的 Unix 毫秒，延迟发送仍用原值。

返回 true 只表示本地入队，不是服务端确认；false 表示未入队。方法不等待网络。SDK 自动生成事件 ID，保留调用方提供的时间，重试不改写。原两参数、三参数入口和十参数 `RiskEvent` 构造保留兼容；原简化入口不提供 IP，并以 `/behavior` 作为 URL 占位值。新版完整 `RiskEvent` 在末尾增加 ip，注解 `resourceType` 保留。

可选使用 `@RiskReport(action = "content-view", resourceType = "video", resource = "videoId")` 采集 MVC 方法，原用法保持兼容。`resource` 是路径变量或查询参数名，默认路径变量优先，不读取请求体、不执行表达式。注解默认使用 Principal，匿名请求跳过；可用 `RiskUserResolver`、`RiskResourceResolver` 对接已有身份和资源。注解自动记录 URL 模板、进入接口时的时间与 remoteAddr，完成后入队，并对 MVC 异步重派发去重；不自行信任转发头。同一行为只选注解或手工上报一种方式，并统一资源标识。

默认累计 100 条或首条等待 1 秒就发送；每批最多 200 条，`flush-interval` 可设 1 毫秒至 30 秒。关闭时在 `shutdown-timeout`（默认 5 秒）内尽力刷新队列。服务端在线最大延迟默认 2 分钟，可配置至 5 分钟，更迟事件不进入实时判定；最近 12 小时内的仍保留事件明细，12～24 小时的补报只进入统计。

重试保留相同批次 ID、事件 ID、时间及内容。网络失败、429、5xx 可有限重试；其他拒绝不重试。队列满、进程退出或重试耗尽可能丢失数据，关注 `metrics()` 的 dropped、sendFailures 等指标。

黑名单在启动后立即异步同步，以后每轮结束后等待 60 秒再次同步，可通过 `blacklist-refresh-interval` 调整。每轮先校验第一页（500 条）；已有完整缓存且版本号、总数未变时跳过后续分页，首次同步或版本变化时拉取全部分页，版本一致且条数完整后一次性替换缓存。成功同步空名单会清空旧缓存。同步失败保留旧名单和成功版本，60 秒是刷新间隔，不是缓存过期时间。

`isBlacklisted(userId)` 只读当前项目的本地缓存，SDK 不自动封禁；不要在每次请求中调用同步方法 `refreshBlacklist()` 或网络风险查询 `queryRisk(userId)`。`metrics().blacklistReady()` 为 false 表示尚未成功加载，完整业务示例在此阶段放行。`metrics().lastBlacklistRefreshAt()` 记录最后成功同步或确认版本未变的时间，失败不会更新；结合 `metrics().blacklistRefreshFailures()` 监控陈旧缓存。`queryRisk(userId)` 是显式同步网络查询，可能抛出网络或中断异常。

版本保持 0.2.0。五参数/六参数入口将 URL 放入原 route 字段，并发送 ip；内部使用 `resourceType=null`、`method=EVENT`、`durationMs=0`、`status=200` 作为兼容值，不代表真实 HTTP 结果，业务方无需填写或计时。服务端将完整事件和抽样/异常明细按发生时间保存最近 12 小时，Admin“事件”页可按项目和用户分页查看完整已接收的 v2 上报，用户报告仍使用抽样片段；统计趋势继续保留 24 小时。完整事件从服务端升级后开始记录，不回填旧数据，SDK 调用方式不变。旧客户端缺失 IP 仍兼容，后台显示 `—`；新的五参数/六参数入口要求非空 IP。现有判定规则保持不变，不新增 IP 判定规则。PHP、Node.js 的接入方式见 HTTP 文档。

完整示例、配置和协议见 [Java 对接指南](../JAVA_INTEGRATION.md)。
