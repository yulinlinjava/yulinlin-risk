# Java SDK 对接指南

SDK 供 Java 17+、Spring Boot 3 Servlet MVC 业务后端使用。手工上报提供 **用户 ID、资源 ID、IP、行为发生时间和 URL**，SDK 自动生成事件 ID，并延迟批量上传。业务代码不需要统计接口耗时；需要区分行为类别时，可额外传入 action。

交给其他 AI 实施接入时，使用这份自包含的 [AI 用 Java SDK 接入指南](doc/SDK_AI_INTEGRATION.md)，包含可直接复制的任务说明和验收清单。

服务端接收 IP，并随完整上报事件、抽样行为和异常片段按发生时间保存最近 12 小时。Admin“事件”页分页展示完整已接收的 v2 上报，用户报告中的明细仍按额度抽样。旧客户端不传 IP 仍可上报，缺失 IP 时显示 `—`。现有判定规则保持不变，不新增 IP 判定。

PHP、Node.js 参考 [通用 HTTP 对接指南](HTTP_INTEGRATION.md)。每个项目独立配置 API Key；同一项目的不同后端使用统一用户 ID 和资源 ID，即可合并分析。浏览器不直接持有项目 API Key。

需要可以直接运行的业务端项目，参考 [Java 五字段手工上报完整示例](examples/java/README.md)：包含依赖、配置、演示登录、业务 Controller、延迟批量上报和本地黑名单检查，不使用 `@RiskReport`。

## 1. 安装依赖

在本仓库根目录执行：

```shell
mvn -pl sdk -am install
```

若当前位于 `sdk` 目录，执行 `mvn -f ../pom.xml -pl sdk -am install`。命令只安装 SDK 和父 POM，不构建服务端，不需要服务端的 `security-risk`、`security-spring` 依赖，单独构建 SDK 可使用 JDK 17+。

工程包含 `server`、`sdk` 两个模块，共用父工程 `com.yulinlin:yulinlin-risk-parent:0.2.0`。`-am` 会安装父 POM，使业务项目能够解析 SDK；根目录 `mvn install` 则构建全部模块，需要 JDK 25。

安装位置以 Maven `settings.xml` 中的 `localRepository` 为准，默认 `~/.m2/repository`。业务项目和 IDE 使用同一仓库，安装后重新加载 Maven 依赖。SDK 尚未发布到 Maven Central，其他机器需要构建安装或使用包含父 POM 和 SDK 的团队私服。

业务项目添加：

```xml
<dependency>
    <groupId>com.yulinlin</groupId>
    <artifactId>yulinlin-risk-sdk</artifactId>
    <version>0.2.0</version>
</dependency>
```

安装更新后的 SDK 即可使用五参数入口，版本仍为 0.2.0。原有两参数、三参数上报、`RiskEvent` 十参数构造及注解继续保留，已有调用不需要迁移。

## 2. 配置

打开风控服务 `/admin/`，首次访问注册管理员，随后登录并创建项目，保存 API Key。已有管理员升级后继续使用原账号密码。Admin 的登录、Cookie 会话、权限和注解限流统一使用 `security-spring`，详见 [Admin 安全与限流](README.md#admin-安全与限流)。SDK 调用的服务端接口标注 `@ApiPublic`，无需管理员登录会话，但仍在服务端代码中校验项目 API Key；黑名单仍默认每 60 秒刷新、失败保留旧缓存。

业务应用配置：

```yaml
yulinlin:
  risk:
    enabled: true
    base-url: http://127.0.0.1:8080
    api-key: ${RISK_API_KEY}
    queue-capacity: 4096
    batch-size: 100
    flush-interval: 1s
    connect-timeout: 2s
    request-timeout: 5s
    max-retries: 3
    retry-delay: 500ms
    blacklist-refresh-interval: 60s
    blacklist-max-users: 200000
    shutdown-timeout: 5s
```

`enabled` 默认 false。地址指向风控服务，跨机器使用部署方提供的地址；API Key 只放在业务后端。SDK 自动配置单例客户端及可选的 MVC 注解采集器。

## 3. 五个参数上报行为

```java
import com.yulinlin.risk.sdk.RiskClient;
import org.springframework.stereotype.Service;

@Service
public class BehaviorReporter {
    private final RiskClient riskClient;

    public BehaviorReporter(RiskClient riskClient) {
        this.riskClient = riskClient;
    }

    public boolean accessed(String trustedUserId, String resourceId,
                            String trustedIp, long occurredAt, String url) {
        return riskClient.tryReport(trustedUserId, resourceId, trustedIp, occurredAt, url);
    }

    public boolean videoViewed(String trustedUserId, String videoId,
                               String trustedIp, long occurredAt, String url) {
        return riskClient.tryReport(trustedUserId, "video-view", "video:" + videoId,
                trustedIp, occurredAt, url);
    }
}
```

在行为发生时记录时间并上报，不需要计算接口运行耗时。例如在业务 Controller 或已有拦截器中：

```java
long occurredAt = System.currentTimeMillis();
behaviorReporter.accessed(currentUserId, "video:" + videoId,
        request.getRemoteAddr(), occurredAt, request.getRequestURI());
```

`userId` 必须来自业务服务端认证上下文，不能直接信任前端提交的身份；手工上报不依赖 `RiskUserResolver` 或 Principal。

| 参数 | 含义 |
| --- | --- |
| userId | 稳定的业务用户 ID，非空，最多 128 字符。 |
| resourceId | 操作对象，可为 null；非空时最多 256 字符。 |
| ip | 可信的请求来源 IP，非空，最多 45 字符；支持 IPv4/IPv6 文本，不做 DNS 查询。 |
| occurredAt | 行为发生时记录的 Unix 毫秒时间戳；延迟上传时继续使用原值。 |
| url | 行为对应的 URL 路径或接口模板，非空，最多 256 字符。建议 `/videos/{videoId}`，不要附带 Token 或查询串。 |

五参数入口的 action 默认是 `access`。需要分类时使用六参数重载 `tryReport(userId, action, resourceId, ip, occurredAt, url)`；action 非空，最多 64 字符，不能为保留值 `__OTHER__`。

IP 从可信请求处理链获取。直接连接可使用 `request.getRemoteAddr()`；经过反向代理时，应先由可信代理配置解析真实来源，再读取处理后的地址。不要直接信任任意客户端提交的 `X-Forwarded-For`。

资源 ID 在项目内统一命名，建议使用 `video:123`、`article:123` 区分资源。同一视频在查看详情、查看评论等行为中使用相同 `video:123`，不要把 action 拼进资源 ID，也不要用随机请求 ID 作为资源。

`tryReport` 不访问网络，返回 true 只表示成功进入本地队列，false 表示未入队。SDK 自动生成事件 ID；发生时间由调用方在行为发生时记录，不能用后台发送时刻替代。后续等待、发送和重试不改变事件 ID 和时间。对同一行为只上报一次，不要同时使用手工调用和注解。

### 延迟批量上传

默认累计 100 条，或从批次首条事件开始等待 1 秒，先满足哪个条件就发送。`batch-size` 最大 200，`flush-interval` 可设置为 1 毫秒至 30 秒。例如 `batch-size: 200`、`flush-interval: 5s` 表示低流量等待最多 5 秒，达到 200 条立即发送。

每条事件保留真实发生时间，批次接收时间不会代替发生时间。积压、网络和重试可能增加实际送达延迟。客户端关闭时在 `shutdown-timeout` 内尽力发送剩余数据。

SDK 使用有界内存队列；队列满、进程退出或重试耗尽可能丢失数据。关注 `metrics().dropped()`、`sendFailures()` 等指标，不能把上报缺失解释为用户没有活动。

### 已有历史事件

如果已有代码需要使用已保存的事件 ID 补报历史行为，`tryReport(RiskEvent)` 高级入口仍可用。新增 IP 位于构造参数末尾，原十参数构造仍保留：

```java
riskClient.tryReport(new com.yulinlin.risk.sdk.RiskEvent(
        savedEventId, trustedUserId, "video-view", null, "video:123",
        "/videos/{videoId}", "EVENT", savedOccurredAt, 0, 200, trustedIp));
```

普通业务使用五参数入口即可。发生时间必须位于最近 24 小时，最多提前 60 秒；在线最大延迟默认 2 分钟，可配置至 5 分钟，更迟事件不进入实时判定。最近 12 小时内的仍保存明细，12～24 小时的补报只进入统计。不要为了重试重新调用便捷入口生成新事件；SDK 会复用原批次重试。

### 原有简化入口

`tryReport(userId, action, resourceId)` 和 `tryReport(userId, action)` 继续兼容：它们自动使用调用时刻，不提供 IP，并使用 `/behavior` 作为 URL 占位值。需要完整的五项行为信息时使用新的五参数或六参数入口。

## 4. 可选：通过注解采集

如果某些行为与 Controller 接口一一对应，也可以使用方法注解：

```java
import com.yulinlin.risk.sdk.RiskReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BehaviorController {
    @RiskReport(action = "content-view", resource = "resourceId")
    @GetMapping("/contents/{resourceId}")
    public String detail(@PathVariable("resourceId") String resourceId) {
        return resourceId; // 替换为业务逻辑；资源 ID 示例：video:123
    }
}
```

注解继续保留 `action`、可选 `resourceType` 和 `resource`，已有用法不变。`resource` 是路径变量或查询参数名，不是表达式，也不读取 JSON/form 请求体。不标注的方法不自动采集。注解自动取得 URL 模板、进入接口时的行为时间和 `request.getRemoteAddr()`，完成后入队，支持 MVC 异步重派发去重；不会自行信任未验证的转发头。

原注解有资源 ID 且未填写 `resourceType` 时，资源类型默认使用 action；跨行为共用资源时应显式设置相同 `resourceType`。便捷手工入口使用完整的统一资源 ID，不单独设置资源类型。同一业务应统一采集方式及资源标识，避免把同一对象记录为不同资源。

注解默认用 `request.getUserPrincipal().getName()` 作为用户 ID；没有 Principal 的请求跳过。有自定义登录框架时注册解析器：

```java
@Bean
com.yulinlin.risk.sdk.RiskUserResolver riskUserResolver() {
    return request -> {
        // 此属性必须由可信的登录过滤器验证身份后设置。
        Object id = request.getAttribute("authenticatedUserId");
        return id == null ? null : id.toString();
    };
}
```

需要把路径中的裸资源 ID 转为统一资源 ID，可提供 `RiskResourceResolver` Bean：

```java
@Bean
com.yulinlin.risk.sdk.RiskResourceResolver riskResourceResolver() {
    return (request, handler, annotation) -> {
        Object resource = request.getAttribute("riskResourceId");
        return resource == null
                ? com.yulinlin.risk.sdk.RiskReportInterceptor.defaultResource(request, handler, annotation)
                : resource.toString();
    };
}
```

解析在 Controller 执行前发生，属性须由更早的可信过滤器或拦截器设置。依赖业务计算结果、消息消费或定时任务的行为直接手工上报即可。

## 5. 黑名单与风险查询

```java
boolean blocked = riskClient.isBlacklisted(trustedUserId);
```

- `isBlacklisted` 只读本地缓存，不访问网络；业务自行决定限制措施。
- `metrics().blacklistReady()` 表示是否至少成功同步过一次；首次加载失败不能把空缓存当作已确认没有黑名单。
- SDK 启动后立即异步同步，以后每轮结束后等待 `blacklist-refresh-interval`（默认 60 秒）再次同步。60 秒是刷新间隔，缓存不会到点删除。
- 每轮先拉取第一页（500 条）。校验通过后，如果已有完整缓存，且返回的版本号、总数与缓存一致，就跳过后续分页；首次同步或版本变化时，继续分页拉取，全部页版本和总数一致且数据完整后，一次性替换缓存。成功同步空名单会清空旧缓存。
- 同步失败时保留原名单、成功版本和最后成功同步时间，下一轮再尝试；新增封禁和解除封禁在故障期间都会延迟生效。正常情况下，变更生效需要等待刷新间隔及分页拉取耗时。
- `refreshBlacklist()` 可主动发起一次同步，也采用相同的版本检查。不要在每次业务请求中调用它；请求只查 `isBlacklisted`，由后台任务同步名单。
- `queryRisk(userId)` 是显式同步网络调用，返回 Jackson JsonNode，可能抛出 IOException 或 InterruptedException。上报和黑名单查询不会自动调用它。
- `metrics()` 提供 queued、submitted、delivered、dropped、sendFailures、blacklistRefreshFailures、blacklistReady 和 lastBlacklistRefreshAt。

`lastBlacklistRefreshAt` 是最后一次成功同步或确认版本未变的时间；失败不会更新。`blacklistReady` 为 true 只表示曾成功加载，不保证当前缓存没有过时，可结合这个时间及失败次数监控。完整业务示例在首次同步完成前放行，业务可根据就绪状态调整处理。

SDK 不自动封禁，疑似风险与黑名单独立；管理员在后台维护黑名单。每个客户端只缓存其 API Key 对应项目的用户 ID，业务进程内复用同一个客户端。

## 6. 协议、重试与统计范围

SDK 调用 `POST /api/v2/batches`，请求头 `X-API-Key`，每批最多 200 个事件。五参数调用的 URL 放入现有 `route` 字段，IP 使用 `ip` 字段；SDK 自动补齐事件 ID、批次 ID 和兼容字段：

```json
{
  "batchId": "稳定的批次 UUID",
  "events": [{
    "eventId": "事件 UUID",
    "userId": "user-123",
    "action": "access",
    "resourceType": null,
    "resourceId": "video:123",
    "route": "/videos/{videoId}",
    "method": "EVENT",
    "occurredAt": 1794400000000,
    "durationMs": 0,
    "status": 200,
    "ip": "203.0.113.10"
  }]
}
```

`method=EVENT`、`durationMs=0`、`status=200` 是便捷入口内部的固定兼容值，不是实际 HTTP 方法、耗时或成功结果；业务方无需提供或计时。HTTP 协议允许旧客户端省略 `ip` 或传 null；新的五参数/六参数 Java 入口要求提供非空 IP。IP 随完整事件和抽样/异常明细保存并展示，不参与现有规则判定。PHP、Node.js 的接入方式仍见 [HTTP 对接指南](HTTP_INTEGRATION.md)。

示例时间须替换为业务实际记录的行为发生时间。成功返回 `{"accepted":1,"duplicate":false}`；同项目相同批次及内容重试返回 duplicate=true。批次内 eventId 不得重复，跨批次不额外去重；重试须保留 batchId、事件顺序和内容，不拆批、不重新生成 ID 或时间。

SDK 对网络异常、429 和 5xx 有限退避重试；400、401、409 等需要修正数据或配置。超时不代表服务端未提交，重试耗尽记为 dropped。

服务端按所有已上报 action 汇总行为频率、持续活动和资源遍历，项目相互隔离。便捷入口不采集真实 HTTP 结果，不能产生 HTTP 错误或拒绝响应证据，也不能据此判断业务请求是否成功。需要保留这类信息的原有完整事件上报继续按原逻辑处理。

Admin“事件”页按项目展示最近 12 小时内完整的已接收 v2 上报，可按用户 ID 筛选，每页 50/100/200 条，使用上一页、下一页和刷新。页面展示五个业务字段及行为、事件 ID，按发生时间与内部 ID 倒序排列。服务端复用 ORM 批量插入，事件与统计、批次收据在同一事务提交，重试相同批次不重复入库。

完整事件表自本次服务端升级后开始记录，不回填旧抽样数据。完整事件和抽样/异常明细按发生时间保留 12 小时，定时分批清理；查询始终只返回最近 12 小时。统计趋势、汇总风险依据、窗口及批次收据继续保留 24 小时，管理员、项目和黑名单独立保留。用户报告仍展示五分钟趋势、行为分布、风险证据及抽样片段。

SDK 调用方式无需调整，仍只上报业务选定的行为；“完整事件”指服务端成功接收的上报，不能证明所有业务请求均已采集或送达。Admin 事件分页接口见 [HTTP 对接指南](HTTP_INTEGRATION.md#admin-事件分页接口)。

`GET /api/v2/users/{userId}/risk` 的 coverage 为 SELECTED_ENDPOINTS，分析只覆盖接入的行为；dataComplete 不能证明客户端没有丢事件。旧 `POST /api/events` 仅用于原播放行为兼容，不代替 v2 批次接口。
