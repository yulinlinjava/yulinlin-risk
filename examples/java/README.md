# Java 业务端完整示例：五个字段手工上报

这是一个可以独立启动的 Spring Boot 3 MVC 项目，使用 Java 17+。不使用 `@RiskReport`，在业务接口中直接调用：

```java
riskClient.tryReport(userId, resourceId, ip, occurredAt, requestUrl);
```

这里只需要两个 Java 文件：[启动类](src/main/java/com/example/business/BusinessApplication.java)和[业务 Controller](src/main/java/com/example/business/VideoController.java)。配置见 [application.yml](src/main/resources/application.yml)，依赖见 [pom.xml](pom.xml)。它是独立接入示例，不加入根工程的 server/sdk 模块列表。

## 1. 准备 SDK 和项目 API Key

在风控仓库根目录安装 SDK（已经安装最新代码可以跳过）：

```shell
mvn -pl sdk -am install
```

启动风控服务，打开 `http://127.0.0.1:8080/admin/`，首次访问注册管理员，然后新建项目并保存该项目的 API Key。业务项目使用相同的 Maven 本地仓库；它只依赖 SDK，不依赖风控 server、H2 或 ORM。

## 2. 配置并启动业务端

示例业务端监听 `127.0.0.1:8081`，风控服务默认地址为 `http://127.0.0.1:8080`。演示用户 ID 为 `10001`，密码由你设置，与风控后台的管理员账号无关。

在仓库根目录使用 PowerShell 7：

```powershell
$env:RISK_API_KEY = Read-Host '请输入风控项目 API Key' -MaskInput
$env:DEMO_USER_PASSWORD = Read-Host '请设置演示用户密码' -MaskInput
# 风控服务地址不同时再设置：
# $env:RISK_BASE_URL = 'http://你的风控服务地址:8080'
mvn -f examples/java/pom.xml spring-boot:run
```

也可以在 IDE 中为 `BusinessApplication` 配置这两个环境变量后运行。实际接入已有项目，只需添加 SDK 依赖、复制 `yulinlin.risk` 配置并在业务代码中调用上报方法；演示登录配置无需复制。

## 3. 发起一次业务请求

另开一个 PowerShell 窗口执行。curl 会提示输入刚才设置的演示用户密码：

```powershell
curl.exe --user 10001 http://127.0.0.1:8081/videos/123
```

正常返回业务数据：

```json
{"id":123,"title":"演示视频 123"}
```

该请求会在 Controller 中直接上报下面五个值：

| 字段 | 示例值 | 获取方式 |
| --- | --- | --- |
| 用户 ID | `10001` | `principal.getName()`，来自服务端认证上下文 |
| 资源 ID | `video:123` | 根据业务资源生成稳定 ID |
| IP 地址 | `127.0.0.1` | `request.getRemoteAddr()` |
| 发生时间 | 当前 Unix 毫秒时间戳 | 进入接口时的 `System.currentTimeMillis()` |
| 请求 URL | `/videos/123` | `request.getRequestURI()`，不含查询串 |

本示例的登录名就是业务用户 ID。如果你现有项目的 Principal 保存的是用户名，应从自己的登录上下文取出稳定用户 ID 再上报。不要让前端通过参数冒充其他用户。

部署在代理后时，需要在业务服务器上配置受信任代理的真实 IP 处理，再读取处理后的 `getRemoteAddr()`。示例按本机直连配置，不直接读取客户端可伪造的 `X-Forwarded-For`。

## 4. 批量发送和后台查看

业务调用只进入 SDK 本地队列，默认累计 100 条或等待 1 秒后发送。SDK 自动生成事件 ID，五参数入口默认行为类型为 `access`；发生时间在排队和重试时保持不变，不采集接口耗时。

`tryReport` 返回 true 仅表示入队成功，false 表示未入队；它不会同步返回“是否机器人”。本示例在上报未入队时继续正常业务，运行中可通过 `riskClient.metrics()` 查看丢弃数、发送失败数和黑名单同步状态。队列和重试都有限制，不能保证所有事件最终送达。

在风控后台选择对应项目，打开“事件”页，按用户 ID `10001` 查询，即可查看已成功接收的这次上报，包括资源 `video:123`、IP、发生时间和 URL。事件页完整保留最近 12 小时的已接收 v2 上报，每页可选 50/100/200 条，使用上一页、下一页和刷新；排序为发生时间与内部 ID 倒序。完整事件表从服务端升级后开始记录，不回填旧抽样记录。

“分析”页继续查看 24 小时统计趋势和用户报告，报告中的抽样/异常明细只保留 12 小时。这里的完整事件仅指服务端成功接收的上报；SDK 未入队、发送失败或业务没有采集的请求不会出现在事件页。服务端使用 ORM 批量插入，重试同一批次不会重复保存。

Controller 还包含可选的本地黑名单检查，`isBlacklisted(userId)` 只查询当前项目的内存名单。SDK 启动后立即异步同步，以后每轮结束后等待 60 秒再同步。每轮先读取第一页；已有完整缓存且版本号、总数未变时跳过后续分页，首次同步或版本变化时才拉取全部分页，并在完整、版本一致后一次性替换缓存。业务请求无需自行拉取或刷新名单。

把 `10001` 加入对应项目黑名单后，等待后台刷新及分页拉取完成，再次访问将返回 403。取消黑名单后也需要等待刷新。60 秒是刷新间隔，缓存不会到点清空；后续同步失败会继续使用上一次成功的名单，故障期间新增封禁和解除封禁都会延迟。完整同步得到空名单时，才会清空旧缓存。

没有完成首次同步时本示例放行，可通过 `riskClient.metrics().blacklistReady()` 判断就绪状态。`lastBlacklistRefreshAt()` 记录最后成功同步或确认版本未变的时间，失败不会更新；结合 `blacklistRefreshFailures()` 可以判断缓存是否长时间未更新。这里的时间和计数均从 `riskClient.metrics()` 获取。

上报放在黑名单检查前，表示记录“访问尝试”，因此被黑名单拒绝的已登录用户也会上报一次。未通过身份认证的请求不会进入 Controller。若只需要收集行为，可以移除 `isBlacklisted` 判断。

其他接口同样直接调用这五参数方法即可，例如查看同一视频的评论仍使用 `video:123`，请求 URL 则是对应评论接口路径。完整 SDK 配置与字段限制见 [Java 对接指南](../../JAVA_INTEGRATION.md)。
