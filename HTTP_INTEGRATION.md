# 风控 HTTP 对接指南

适用于 PHP、Node.js 及其他能够发送 HTTP JSON 的业务后端。业务方选择需要观察的接口，记录可信用户身份和请求结果，延迟组成批次后发送；风控服务按项目和用户汇总多个接口的行为。

Java 项目可直接使用 [Java SDK](JAVA_INTEGRATION.md)。其他语言可参考仓库中的 [Node.js 示例](examples/node/risk-client.mjs) 和 [PHP 示例](examples/php/RiskClient.php)，不依赖 Java。

## 后台创建项目

1. 打开风控服务的 `/admin/`，首次访问注册管理员；已有账号直接登录，升级后继续使用原账号密码。
2. 在“项目管理”填写项目名称并点击“新建项目”。
3. 从项目表格直接查看并复制 API Key，配置到对应业务后端。已有项目可随时再次复制。
4. 业务方完成上报后，在后台选择项目，在“事件”页分页查看最近 12 小时的完整已接收上报，也可查看用户行为分析、统计趋势和抽样证据，按需加入或解除黑名单。

同一个服务可以接入多个项目。每个项目有独立的 API Key，接口根据密钥确定项目，不需要在请求体中传 `projectId`。

| 使用场景 | 结果 |
| --- | --- |
| 项目 A 和项目 B 都有用户 `user-123` | 分别统计，风险和黑名单互不混用。 |
| 同一个项目有 PHP、Node.js、Java 多个后端 | 使用同一项目密钥、统一用户 ID，行为可以合并分析。 |
| 同一个发送进程服务多个项目 | 按项目分别建队列、封批和缓存；一个批次不能混入不同项目的数据。 |
| 项目 A 的用户被加入黑名单 | 只在 A 的黑名单接口返回，B 不会自动拉黑同名用户。 |

当前后台由一个管理员管理所有项目；没有项目专属管理员、项目停用和删除功能。项目表格提供“重置密钥”：确认后生成新密钥，旧密钥立即失效，项目与行为数据保留；所有使用该项目的业务后端都需要更新配置。旧版本只保存摘要的项目可通过重置取得可展示的新密钥，重置前原密钥继续有效。项目名称可以相同，实际隔离依赖项目 ID。多个项目共享实例资源、规则配置及采样预算，数据隔离不代表独立容量保障。

## 地址与鉴权

业务接口统一使用：

```http
X-API-Key: <本项目的 API Key>
Accept: application/json
Content-Type: application/json
```

`Content-Type` 用于 POST 请求。示例默认地址为 `http://127.0.0.1:8080`，跨机器使用部署方提供的地址；部署 HTTPS 时保持证书校验开启。业务接入密钥放在后端配置，不发送给业务前端；管理员可在受保护的后台查看和复制。禁止将密钥写入 URL 或日志。

业务上报、风险查询和黑名单分页通过 `@ApiPublic` 声明无需 Admin 登录或 Cookie，每个接口仍在代码中校验项目 API Key。Admin 通过 `security-spring` 统一管理登录、Cookie 会话、注销和 `ADMIN` 权限，保留 CSRF 校验。登录与首次注册同样使用 `@ApiPublic`，注册代码限制只能创建一个管理员。

Admin 使用 `@ApiLimit` 按 IP、按接口限流：登录每分钟 10 次，首次注册每分钟 5 次，各管理 API 每分钟 120 次。超限返回 `429` 和 `Retry-After`。代理部署的可信 IP 配置见 [Admin 安全与限流](README.md#admin-安全与限流)；这些 Admin 限额不改变下述业务 API 协议和黑名单缓存方式。

| 方法和路径 | 用途 |
| --- | --- |
| `POST /api/v2/batches` | 延迟批量上报选中接口的事件。 |
| `GET /api/v2/users/{userId}/risk` | 查询用户当前综合风险和黑名单状态。 |
| `GET /api/blacklist?page=1&pageSize=500` | 分页获取本项目黑名单，用于业务侧缓存。 |

## 批量上报

### 请求字段

`batchId` 是非空字符串，最多 128 字符，建议使用 UUID 或随机 128 位以上标识。`events` 每批必须为 1～200 条，同一批次中 `eventId` 不得重复。

| 事件字段 | JSON 类型 | 约束与含义 |
| --- | --- | --- |
| `eventId` | string | 非空，最多 128 字符，业务服务端生成。 |
| `userId` | string | 非空，最多 128 字符，取服务端认证上下文。 |
| `action` | string | 非空，最多 64 字符，如 `content-view`、`comment-list`；不能使用保留值 `__OTHER__`。 |
| `resourceType` | string 或 null | 可选，非空时最多 64 字符，如 `video`。 |
| `resourceId` | string 或 null | 可选，非空时最多 256 字符，如视频 ID。 |
| `ip` | string 或 null | 可选，来自可信业务请求链，非空时最多 45 字符；旧客户端可省略。 |
| `route` | string | 非空，最多 256 字符，使用 `/videos/{videoId}` 这样的接口模板。 |
| `method` | string | 非空，最多 16 字符，如 `GET`、`POST`。 |
| `occurredAt` | integer | 请求实际发生时间，Unix 毫秒。 |
| `durationMs` | integer | 请求耗时毫秒，0～86400000。 |
| `status` | integer | HTTP 响应状态码，100～599。 |

字符串不能包含 NUL；可选字符串传 null 或省略，不传空字符串。标识符和 action 建议使用稳定、简短的 ASCII 值。请求时间必须在服务端最近 24 小时内，最多允许提前 60 秒。PHP 使用 64 位运行环境保存毫秒整数。

IP 应来自可信来源地址解析，不直接相信客户端提交的转发头。服务端将 IP 随完整事件和抽样/异常明细按发生时间保存最近 12 小时，后台事件页及抽样事件表均展示 IP；旧客户端及旧明细缺失 IP 时显示 `—`。用户报告的采样额度不变，不新增基于 IP 的判定规则。

同一种资源在多个接口中使用相同 `resourceType` 和 `resourceId`，例如视频详情和视频评论都引用 `video + v-456`。通用客户端应明确传资源类型；不应把完整 URL、随机请求 ID 当成业务资源。

### 请求与响应

```json
{
  "batchId": "batch-8b062d13-5899-43db-bfba-fb6fe5aef61f",
  "events": [
    {
      "eventId": "event-cdfb7713-1f7b-44ec-ad11-eb672995a10d",
      "userId": "user-123",
      "action": "content-view",
      "resourceType": "video",
      "resourceId": "v-456",
      "ip": "203.0.113.10",
      "route": "/videos/{videoId}",
      "method": "GET",
      "occurredAt": 1794400000000,
      "durationMs": 28,
      "status": 200
    }
  ]
}
```

例子中的时间需要替换为实际发生时间，不能直接长期复用。提交后返回 HTTP 200：

```json
{"accepted": 1, "duplicate": false}
```

`accepted` 是本批事件数量，不表示本批存在多少风险用户。响应在 H2 事务提交后发出；新批次整体提交，没有部分成功。服务端升级后接收、且发生在最近 12 小时内的事件会批量写入完整事件表；12～24 小时的历史补报只进入统计，不保存明细。用户报告中的明细仍按采样策略选择。

同一项目重试相同批次返回：

```json
{"accepted": 1, "duplicate": true}
```

### 延迟封批与重试

建议初始配置为累计 100 条或等待 1 秒，先达到条件就封批。允许调整为 200 条或 5 秒等组合。服务端没有客户端定时器，接收的是业务方已经封好的批次。

从首条事件开始计时，每条事件保留原始 `occurredAt`。一次批量上传不会把其中所有请求都改成上传时发生。服务端在线最大延迟 `risk.global.online-max-delay` 默认 2 分钟，可配置至 5 分钟；还要满足当前在线分钟窗口。更早的数据不进入实时判定，发生在最近 12 小时内的仍保留事件明细，12～24 小时的补报只进入统计。

封批时生成一次 batchId 并固定事件集合。网络超时不代表服务端没有提交，重试必须保留整个批次、事件顺序、ID 和时间，不能重新生成 ID 或重新拆批。

幂等范围是“项目 + batchId”，回执保留约 24 小时。跨不同批次的相同 eventId 不去重。需要进程重启后继续可靠发送时，将已封批内容保存到业务现有的持久队列或任务存储，成功确认后再移除；普通进程内数组不具备这种保证。

| 状态 | 建议处理 |
| --- | --- |
| 200 | 校验 accepted 与批次事件数一致，完成该批次。duplicate=true 也属于成功。 |
| 400 | 字段、时间或批次格式不合法，修正数据；不要无限重试。 |
| 401 | 检查项目 API Key 和服务地址。 |
| 409 | 同一 batchId 用于不同内容，排查 ID 或重试逻辑。 |
| 429、5xx、连接失败、超时 | 保留原批次，有限次数退避重试；耗尽后记录失败并按业务重发策略处理。 |

错误正文可能是 Problem Details JSON。以 HTTP 状态判断策略，不依赖具体中文错误文案。示例客户端只负责 HTTP 发送、重试、查询和快照拉取，延迟封批、队列容量及持久化由业务 worker 管理。

仓库中的 PHP、Node.js 示例 helper 本次未调整，仍按原完整事件接口使用，不会自动采集 IP。需要携带 IP 时，按上面的字段表构造 JSON，并确认所用客户端保留 `ip` 字段；下面原示例可继续不传 IP。

## Node.js 接入示例

运行要求为 Node.js 18+，无需安装第三方依赖。示例源码见 [risk-client.mjs](examples/node/risk-client.mjs)。

```javascript
import { randomUUID } from 'node:crypto';
import { RiskClient, prepareBatch } from './examples/node/risk-client.mjs';

const client = new RiskClient({
  baseUrl: process.env.RISK_BASE_URL,
  apiKey: process.env.RISK_API_KEY,
});

// 在业务 worker 中获取此前采集的事件；发生时间保持不变。
const batch = prepareBatch([{
  eventId: randomUUID(), userId: 'user-123', action: 'content-view',
  resourceType: 'video', resourceId: 'v-456', route: '/videos/{videoId}',
  method: 'GET', occurredAt: Date.now(), durationMs: 28, status: 200,
}]);
await client.sendBatch(batch);
const risk = await client.getRisk('user-123');
console.log(risk.status, risk.blacklisted);
```

常驻 Node.js 服务可使用有界队列和 timer 形成批次；发送过程中不修改已经封好的批次。每个项目独立配置一个客户端。队列满和重试失败需要业务指标，进程退出时应等待正在发送的任务，或交给持久队列继续处理。

提供 CLI 用于联调或队列任务：先准备只包含事件数组的 `events.json`，再执行：

```shell
node examples/node/example.mjs prepare events.json batch.json
node examples/node/example.mjs send batch.json user-123
```

发送前配置 `RISK_BASE_URL`、`RISK_API_KEY` 环境变量。失败时重用原 batch.json。prepare 命令拒绝覆盖已有文件，避免把重试任务变成新批次。

## PHP 接入示例

运行要求为 64 位 PHP 8.1+，启用 cURL 和 JSON。示例源码见 [RiskClient.php](examples/php/RiskClient.php)。

```php
<?php
require __DIR__ . '/examples/php/RiskClient.php';

use Yulinlin\RiskExample\RiskClient;

$client = new RiskClient(getenv('RISK_BASE_URL'), getenv('RISK_API_KEY'));
$batch = $client->prepareBatch([[
    'eventId' => bin2hex(random_bytes(16)),
    'userId' => 'user-123',
    'action' => 'content-view',
    'resourceType' => 'video',
    'resourceId' => 'v-456',
    'route' => '/videos/{videoId}',
    'method' => 'GET',
    'occurredAt' => (int) floor(microtime(true) * 1000),
    'durationMs' => 28,
    'status' => 200,
]]);

// 在常驻 worker 或队列任务中发送，重试复用同一个 $batch。
$ack = $client->sendBatch($batch);
$risk = $client->getRisk('user-123');
```

PHP 示例的 sendBatch 是同步函数，退避期间会等待。PHP-FPM 项目应在业务请求中采集后交给现有队列或常驻 worker，由 worker 按时间和条数封批；不要用业务请求里的 sleep 实现延迟上传，也不要把请求内的静态数组当作跨请求可靠队列。

CLI 可发送已经生成、保存的批次文件：

```shell
php examples/php/example.php batch.json user-123
```

同样从环境变量读取地址和项目密钥。文件结构为完整的 `{batchId,events}`，可与 Node.js 产生的批次文件互通。

## 查询用户风险

调用 `GET /api/v2/users/{userId}/risk`，路径中的用户 ID 做 URL 编码。建议统一使用字母、数字、`-`、`_` 组成的 ID；含 `/`、`\` 或路径特殊值的 ID 即使编码也可能被 HTTP 容器拒绝。已有特殊格式的账号可在业务端映射为稳定的安全 ID，并在上报、查询和黑名单中使用同一个映射。

```json
{
  "status": "OBSERVING",
  "reasons": [],
  "judgedAt": 1794400001000,
  "dataComplete": true,
  "blacklisted": false,
  "coverage": "SELECTED_ENDPOINTS"
}
```

| 状态或字段 | 含义 |
| --- | --- |
| NO_DATA | 当前在线窗口没有数据，judgedAt 为 null；历史可能仍有记录。 |
| OBSERVING | 观察窗口或样本尚未满足判定条件。 |
| NOT_MATCHED | 当前没有命中，不代表已经证明用户正常。 |
| SUSPECTED | 命中疑似规则；reasons 提供 ruleId、reason 和 metrics。 |
| blacklisted | 当前项目的黑名单状态，与风险状态独立。 |
| dataComplete | 服务端已接收窗口的统计是否完整；不能证明客户端从未丢事件。 |
| coverage | SELECTED_ENDPOINTS，分析仅覆盖业务方接入的接口。 |

风险查询结果不会自动封禁用户。请求前的常规限制可使用业务本地黑名单缓存；需要查询实时状态时再调用此接口。用户身份从服务端登录态或可信会话获取，未接入接口不可见，不能把缺失的前置操作当成违规证据。

## 黑名单分页与缓存

调用 `GET /api/blacklist?page=1&pageSize=500`。page 从 1 开始，pageSize 为 1～500，默认 100。

```json
{
  "items": [{"userId": "user-123"}],
  "page": 1,
  "pageSize": 500,
  "total": 1,
  "version": 8
}
```

按以下步骤刷新每个项目自己的缓存：

1. 从第一页读取 version 和 total，使用临时集合收集用户 ID。
2. 拉取全部分页，要求每页 version、total 一致，页号、条数和用户 ID 有效。
3. 所有页成功且总条数一致后，原子替换本项目缓存；成功得到 total=0 时清空缓存。
4. 网络失败、版本变化或分页不完整时，丢弃临时集合，保留上一次完整缓存，稍后从第一页重试。

Node.js 的 `fetchBlacklistSnapshot()` 返回 Set，PHP 返回用户 ID 数组；两者失败时抛异常。只有成功返回后才替换业务缓存。首次加载和长期刷新失败需要业务制定处理方式，不能把未加载的空集合当作已经确认没有黑名单。多进程 PHP 项目应将完整快照存入业务已有的共享缓存，或每个 worker 独立维护；缓存键必须含项目标识。

Java SDK 已实现每轮结束后等待 60 秒刷新：每轮先校验第一页，已有完整缓存且版本号、总数未变时跳过后续分页，版本变化才拉取全部页面。成功检查未变版本也会更新最后同步时间；失败保留旧名单和成功版本。60 秒是刷新间隔，不是缓存到期删除时间。这里的 PHP、Node.js `fetchBlacklistSnapshot()` 示例仍执行完整分页拉取，定时调度和缓存替换由业务端管理；需要相同优化时，可复用上述版本检查协议。

## 保留期与后台查看

完整事件和正常/异常抽样明细按发生时间滚动保留最近 12 小时，统计趋势、汇总风险依据、窗口和批次收据继续保留 24 小时，管理员、项目和黑名单独立保留。摘要以五分钟时间桶展示实际统计区间；用户报告中的抽样片段不代表完整轨迹。

Admin“事件”页按项目展示完整的已接收 v2 上报，可按用户 ID 精确筛选，每页 50/100/200 条，支持上一页、下一页和刷新。展示发生时间、用户 ID、资源 ID、IP、URL、行为和事件 ID，按发生时间与内部 ID 倒序排列。完整事件表从升级后开始记录，不从旧抽样数据回填，也不包含未接入或未送达服务端的业务请求。

事件写入通过 yulinlin-orm JDBC batch 执行，与统计、状态及批次收据在同一事务提交。过期明细按有限批次独立提交清理，查询始终限制最近 12 小时；即使物理清理尚未完成，过期事件也不会返回。最旧摘要桶在整桶过期后删除。

多个项目的上报会共享服务器容量。容量评估应看所有项目合计的请求量、活跃用户窗口、完整事件、摘要和样本写入量，不能把每个项目的单独容量直接相加。

### Admin 事件分页接口

`GET /admin/api/projects/{projectId}/v2/event-log` 使用管理员登录会话，与业务 API Key 鉴权分开。

| 参数 | 含义 |
| --- | --- |
| `userId` | 可选，精确筛选用户 ID。 |
| `from`、`to` | 可选，Unix 毫秒时间范围；首次不传时查询最近 12 小时。后续翻页沿用响应中的范围。 |
| `pageSize` | 默认 50，最大 200。 |
| `cursor` | 可选，下一页使用上次响应的 `nextCursor` 原值，不自行构造。 |

响应包含 `items`、`hasMore`、`nextCursor`、`from`、`to`；每项包含 `eventId`、`userId`、`action`、`resourceId`、`ip`、`route`、`occurredAt`。分页使用游标和多取一条判断是否还有数据，不计算总数，也不使用深 OFFSET。前端保存各页游标支持返回上一页，刷新或更换筛选条件时重新开始查询。原 `/v2/events` 接口继续用于单用户抽样历史。
