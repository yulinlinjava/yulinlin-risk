# 给 AI 的 Java SDK 接入指南

本文可直接交给负责业务项目的 AI。目标是在现有业务后端接入 `yulinlin-risk-sdk:0.2.0`，手工上报用户行为，并通过本地黑名单缓存拦截用户。采用简单设计，复用 SDK 已有能力。

## 1. 接入前先确认

- 业务项目使用 Java 17+、Spring Boot 3、Servlet MVC。本指南不直接适用于 Spring Boot 2 或 WebFlux。
- 风控服务已经启动，业务服务器能够访问它的地址。
- 在风控 Admin 新建或选择项目，从项目表格复制 API Key。旧项目若没有可复制的密钥，可继续使用业务方已保存的原密钥，或按需在后台“重置密钥”后更新业务端配置。
- 找到业务已有登录体系取得稳定用户 ID 的方法，以及需要采集的关键业务接口。
- 明确业务服务器是否经过反向代理；使用已有的可信代理配置获得真实 IP。

业务接入时把 API Key 放在后端环境变量或已有配置中心，不下发给业务前端，也不写进 URL、日志或示例源码。风控 Admin 的管理员账号与业务用户身份是两套不同用途的账号。

重置后旧密钥立即失效，同一项目的所有业务端都要更新配置。负责接入的 AI 不要擅自重置现有项目密钥；应先确认是否需要重置及业务端更新安排。

业务端只添加 SDK 依赖，不需要引入风控 server、H2、ORM 或 `security-spring`，也不需要更换业务项目原有的登录框架。

## 2. 安装和依赖

在 `yulinlin-risk` 仓库根目录执行：

```shell
mvn -pl sdk -am install
```

`-am` 会同时安装 SDK 所需的父 POM。业务项目和 IDE 应使用同一个 Maven 本地仓库。SDK 当前未发布到 Maven Central；其他机器需要构建安装，或从同时包含父 POM 和 SDK 的团队私服获取。

业务项目在已有 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.yulinlin</groupId>
    <artifactId>yulinlin-risk-sdk</artifactId>
    <version>0.2.0</version>
</dependency>
```

同版本的旧本地 JAR 可能不含最新五参数方法，接入前先安装当前源码。单独安装 SDK 使用 Java 17+ 即可；构建风控 server 才需要 JDK 25。

## 3. 业务配置

把下面配置合并到业务项目已有的 `application.yml`，不要覆盖原有业务配置：

```yaml
yulinlin:
  risk:
    enabled: true
    base-url: ${RISK_BASE_URL:http://127.0.0.1:8080}
    api-key: ${RISK_API_KEY}
    queue-capacity: 4096
    batch-size: 100
    flush-interval: 1s
    blacklist-refresh-interval: 60s
```

`RISK_BASE_URL` 是业务后端能访问到的风控服务地址，`127.0.0.1` 只适用于同机部署。`RISK_API_KEY` 是该项目的 API Key。SDK 自动发送 `X-API-Key` 请求头，业务代码无需自己拼 HTTP 请求。

必须设置 `enabled: true`，否则自动配置不会创建 `RiskClient`。在受支持的 Spring Boot MVC 应用中，SDK 自动创建单例客户端并管理启动、关闭；不要重复声明 Bean、每请求创建客户端，或手动调用 `start()`。

## 4. 只传这五个字段

使用以下真实方法签名，参数顺序不能改：

```java
boolean tryReport(String userId, String resourceId, String ip,
                  long occurredAt, String url);
```

| 字段 | 业务取值方式 |
| --- | --- |
| `userId` | 服务端已认证身份中的稳定用户 ID，非空，最多 128 字符。不能直接使用前端提交的 userId。 |
| `resourceId` | 稳定资源标识，例如 `video:123`、`article:456`，最多 256 字符；没有资源时允许 null。 |
| `ip` | 可信请求处理链提供的 IP，非空，最多 45 字符。直连使用 `request.getRemoteAddr()`。 |
| `occurredAt` | 行为发生时记录的 Unix 毫秒时间戳，例如 `System.currentTimeMillis()`，不是秒。 |
| `url` | URL 路径或接口模板，非空，最多 256 字符，例如 `/videos/123` 或 `/videos/{videoId}`；不包含 Token、查询串。 |

同一资源在不同接口中使用相同资源 ID，不要拼入行为名称或随机请求 ID。五参数入口的行为类型默认是 `access`；本次接入不需要额外采集接口耗时或响应状态。

经过代理时，应先配置可信代理解析，再读取处理后的地址。不要直接信任任意请求携带的 `X-Forwarded-For` 或 `X-Real-IP`。

## 5. 完整 Controller 示例

把采集调用合并到已有业务接口中，不需要新建一套登录接口。以下独立示例假设 `Principal.getName()` 就是稳定用户 ID；若它实际上是用户名，应换成业务项目现有的用户 ID 获取方法。

```java
package com.example.business;

import com.yulinlin.risk.sdk.RiskClient;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class VideoController {
    private final RiskClient riskClient;

    public VideoController(RiskClient riskClient) {
        this.riskClient = riskClient;
    }

    @GetMapping("/videos/{videoId}")
    public Map<String, Object> detail(
            @PathVariable("videoId") long videoId,
            HttpServletRequest request,
            Principal principal) {
        long occurredAt = System.currentTimeMillis();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        String userId = principal.getName(); // 必须确认为业务用户 ID

        // 记录访问尝试，包括随后被黑名单拦截的访问；只入本地队列。
        // 返回 false 时本示例继续业务，丢弃数量由 SDK metrics 记录。
        riskClient.tryReport(
                userId,
                "video:" + videoId,
                request.getRemoteAddr(),
                occurredAt,
                request.getRequestURI());

        // 只读当前项目的内存黑名单，不发起网络请求。
        if (riskClient.isBlacklisted(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前用户禁止访问");
        }

        // 实际接入时保留这里原有的业务逻辑和响应结构。
        return Map.of("id", videoId, "title", "演示视频 " + videoId);
    }
}
```

不使用 `@RiskReport`。不要再通过全局拦截器重复上报同一个行为；只在选定的有价值业务接口接入。黑名单命中后的限制方式可沿用业务项目现有的错误码和响应规范。

## 6. SDK 已处理的行为

**上报：** `tryReport()` 不发起网络请求。返回 true 仅表示入队成功，不代表服务器已接收，也不代表用户正常或是机器人；返回 false 表示未入队。

默认累计 100 条，或从批次首条开始等待 1 秒后发送，先满足的条件触发。积压、网络和重试会增加实际送达延迟。SDK 自动生成事件 ID、批次 ID，并在重试中保持原时间和 ID；不要额外包装线程、定时器或重复上报重试。

内存队列有容量限制，上报是尽力发送。队列满、重试耗尽或进程退出都可能丢失数据；上报失败不应在本示例中直接判定用户恶意或中断正常业务。

**黑名单：** `isBlacklisted(userId)` 只查询本地内存，SDK 启动后立即异步同步，之后每轮完成再等待 60 秒。

- 每轮先拉第一页。已有完整缓存且版本、总数一致时跳过后续分页；版本变化或首次同步时继续拉取。
- 全部分页完整且版本一致后才一次性替换缓存；成功同步空名单才清空旧名单。
- 同步失败继续使用旧缓存，60 秒是刷新间隔，不是缓存删除期限。新增封禁和解除封禁都可能因同步失败而延迟。
- 首次同步尚未成功时缓存为空，本示例放行；`metrics().blacklistReady()` 为 false，不等于已确认用户安全。

每个客户端只对应其 API Key 所属项目。不要自行增加黑名单定时器、分页下载器或另一层 60 秒过期缓存；不要在每个业务请求里调用 `refreshBlacklist()` 或 `queryRisk()`。`queryRisk()` 是显式同步网络查询，和本地黑名单检查不同。

运行中可读取 `riskClient.metrics()` 的 `dropped()`、`sendFailures()`、`blacklistRefreshFailures()`、`blacklistReady()` 和 `lastBlacklistRefreshAt()`。最后一项是最后成功同步或确认版本未变的时间；不要因 `blacklistReady()` 为 true 就认为缓存永远最新。

## 7. 验收清单

- 业务项目可以编译启动，`RiskClient` 通过构造器注入；没有额外启动的风控客户端或刷新定时器。
- 正常登录用户访问一个目标接口一次；风控 Admin 对应项目的“事件”页能查询到该用户、资源、IP、发生时间和 URL。
- 同一访问只出现一次上报；普通静态资源及未选定接口不因本次接入被全量采集。
- 在对应项目把该用户加入黑名单，等待刷新间隔及分页同步完成后再次访问，业务接口按约定拒绝访问；解除后等待同步，应恢复访问。
- 已同步过名单后暂时断开风控服务，旧黑名单仍生效；上报失败不阻断其他正常用户的主业务。恢复服务后能够继续同步。
- 确认首次黑名单未就绪时的处理符合业务约定，本示例为放行；检查 `blacklistReady()` 与失败指标，不把空缓存当作确认安全。
- 前端提交的其他 userId 不会替代真实身份；代理场景下 IP 正确；配置、日志和 URL 没有泄漏 API Key。

事件页保留最近 12 小时已成功接收的完整上报，统计趋势保留最近 24 小时；黑名单不随行为数据清理而删除。SDK 不自动把疑似机器人加入黑名单，管理员维护的黑名单与风险判断是不同结果。

## 8. 可直接交给 AI 的任务说明

```text
请按本文在当前业务项目接入 yulinlin-risk-sdk:0.2.0。
先检查项目版本、现有登录体系、稳定用户 ID 来源、可信代理配置和需要采集的接口。
添加 SDK 依赖和 yulinlin.risk 配置，地址及 API Key 使用环境变量或已有配置中心。
在选定接口直接调用五参数 tryReport，不使用 @RiskReport，不采集接口耗时。
用 isBlacklisted 做本地拦截，保留业务项目原有错误码/响应结构和认证机制。
复用 SDK 的异步批量发送、重试、黑名单分页同步与缓存，不另写定时器或 HTTP 上报器。
不要把 tryReport 的 boolean 当作机器人判定，不要把业务接口改为匿名访问。
不要擅自重置现有项目的 API Key；若需重置，先说明旧密钥失效和所有业务端更新要求。
没有服务地址、API Key、稳定用户 ID 来源或采集范围时，明确列出缺失项，不编造。
完成后执行适合本项目的编译与接入验证，列出修改文件、配置方法及实际验证结果。
```

## 9. 仓库内参考

- [Java SDK 详细说明](../JAVA_INTEGRATION.md)；[PHP / Node.js 的 HTTP 接入说明](../HTTP_INTEGRATION.md)。
- [可独立运行的 Java 示例](../examples/java/README.md)。
- [RiskClient 方法与缓存逻辑](../sdk/src/main/java/com/yulinlin/risk/sdk/RiskClient.java)。
- [SDK 配置与默认值](../sdk/src/main/java/com/yulinlin/risk/sdk/RiskClientProperties.java)。
- [Spring Boot 自动配置](../sdk/src/main/java/com/yulinlin/risk/sdk/RiskAutoConfiguration.java)。
