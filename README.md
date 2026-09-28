# local-request-tracer-java

本地请求追踪与延迟归因：一次请求在同步与异步链路中的耗时、异常与归属关系被稳定记录，
数据保存在进程内，可按追踪标识查询，不依赖任何外部服务。

## 快速开始

```bash
mvn spring-boot:run

# 触发一条正常链路（含同步 + 异步子操作）
curl -i http://localhost:8080/api/demo/ok
# 响应头携带 X-Trace-Id，用它查询完整追踪链
curl http://localhost:8080/api/traces/<traceId>

# 其他演示入口
curl http://localhost:8080/api/demo/slow?ms=600      # 慢操作归因
curl http://localhost:8080/api/demo/fail?type=timeout  # 失败归一化（type: validation/conflict/timeout/upstream/io/unknown）

# 按条件批量查最近的追踪（时间范围 + 整体结果 + 失败类别，倒序、限量/分页）
curl 'http://localhost:8080/api/traces?status=FAILED&errorCategory=TIMEOUT&limit=20'
# 用上一页返回的 nextCursor 翻下一页
curl 'http://localhost:8080/api/traces?limit=20&cursor=<nextCursor>'
```

## 追踪上下文传播规则

- **入口**：`TraceFilter` 为每个 HTTP 请求开启一条追踪链（SERVER 根 span），
  traceId 由服务端生成（不信任外部传入，避免跨系统串链），
  通过响应头 `X-Trace-Id`、请求属性 `traceId` 与日志 MDC 暴露。
- **同线程**：`TraceContext` 用 ThreadLocal 维护未结束 span 栈，
  子 span 自动以栈顶 span 为父。
- **跨线程 / 异步**：提交方 `TraceContext.capture()` 捕获
  `{traceId, 当前spanId}`，执行方 `attach()` 安装；执行线程的首个 span
  以提交时刻的 span 为父，之后恢复线程内正常栈式父子关系。
  `TraceTaskDecorator` 已挂到应用主异步执行器（`@Primary Executor`），
  `@Async` 与注入 `Executor` 的任务自动获得传播能力，无需业务代码配合。
- **清理**：`attach` 返回的 `Scope` 关闭后移除安装；请求结束 `TraceFilter`
  在 finally 中关闭 trace 并清理 ThreadLocal 与 MDC，
  线程池线程复用不会残留上一次请求的上下文（不串链）。
- **降级**：无追踪上下文时 `startSpan` 返回 null，静默降级，不阻断业务。

## 错误分类口径

原始异常经 `ErrorClassifier` 归一化为稳定类别，判定**只依赖异常类型**
（含 `CompletionException`/`ExecutionException` 包装链与 `cause` 链），
与入口、异常消息无关——同一类失败在任何入口下结论一致。

| 类别 | HTTP | 典型来源 |
|---|---|---|
| VALIDATION_ERROR | 400 | IllegalArgumentException、参数绑定/校验失败 |
| UNAUTHENTICATED | 401 | 显式 401 语义异常 |
| FORBIDDEN | 403 | AccessDeniedException（按类名匹配，无 security 硬依赖） |
| NOT_FOUND | 404 | 静态资源不存在等 |
| CONFLICT | 409 | IllegalStateException |
| RATE_LIMITED | 429 | 线程池拒绝 RejectedExecutionException |
| UPSTREAM_FAILURE | 502 | ConnectException / IOException |
| TIMEOUT | 504 | TimeoutException / SocketTimeoutException / HttpTimeoutException |
| INTERNAL_ERROR | 500 | 其他未识别 RuntimeException |
| UNKNOWN | 500 | 未识别受检异常 |

**对外口径**：响应体只有 `{category, message, traceId}`，message 固定为类别的
安全文案，绝不包含异常类名、消息或堆栈；原始异常只进服务端日志（带 traceId）。
span 上同样只记录类别与安全文案。首次失败结论为准，避免后续覆盖导致归因漂移。

**延迟归因**：span 耗时 ≥ `trace.slow-threshold-ms` 标记 `SLOW`，否则 `NORMAL`，
并输出慢操作日志（含 traceId、span 名、耗时、阈值）。

### 一层归因汇总（列表与详情通用）

为了“看得懂”，列表项与详情都直接附带一层归因 `attribution`，由定型快照纯函数派生：

- `status`：链路整体结论（任一 span 失败即 `FAILED`）。
- `errorCategory`：失败时的**最终归类**。一条链路上多个 span 失败时，
  在各类别中按固定优先级取最高者，并列时取最早开始、spanId 最小者，
  结论只取决于最终快照内容：
  `TIMEOUT > UPSTREAM_FAILURE > RATE_LIMITED > CONFLICT > FORBIDDEN > UNAUTHENTICATED
  > NOT_FOUND > VALIDATION_ERROR > INTERNAL_ERROR > UNKNOWN`。
  因此同一种失败不管从同步入口直入，还是绕了异步、被 `CompletionException`/
  `ExecutionException`/笼统 `RuntimeException` 转译包装，最终归类都一致；
  多条链路按类别统计时不会因路径不同被拆成两类（`ErrorClassifier` 沿 cause 链
  有限深度、防环遍历，取“最具体”归因，外层笼统异常不会盖过根因）。
- `slowestSpanName` / `slowestSpanDurationMs`：最费时间的那一环（名字与毫秒数）；
  耗时并列时取最早开始、spanId 最小者，结果稳定可复现。

## 存储上限与过期回收

留存由两条规则共同约束，**同时生效、先到先算**：任一条件先满足即回收该追踪。

- **容量上限**：`trace.max-traces`（默认 1024）。写满后按 **FIFO** 淘汰最早写入的追踪，
  淘汰顺序与查询频率无关；插入与淘汰在同一把内部锁下完成，`size` 永不超过容量。
- **按时间过期（TTL）**：`trace.ttl-ms`（默认 `0` 表示不启用，只受容量约束）。
  自一条追踪**结束时刻**起算，存活达到 TTL 即被回收。除定时扫描
  （`trace.retention-sweep-ms`，默认 30000ms）外，**写入与按标识查询也会顺带判定过期**：
  即使尚未轮到定时扫描，也绝不会把已过期记录当命中返回。
- 容量淘汰与 TTL 过期**在同一把锁内完成**；落库对象是 trace 关闭时一次性生成的
  **不可变快照**（span 列表防御式拷贝）。清理过程中并发查询拿到的要么是完整记录、
  要么是明确的“没有”，读不到半截记录；批量查询在锁内取一致性快照后在锁外过滤排序，
  后台持续写入/淘汰不会让同一批结果抖动。

### “已过期回收”和“压根没记录过”如何区分

回收时会留下**有界墓碑**（不超过 `max-traces`，TTL 墓碑留满一个 TTL 周期后老化）：

| 按标识查询状态 | 含义 | HTTP |
|---|---|---|
| FOUND | 命中 | 200 |
| EXPIRED | 曾记录，已按 TTL 过期回收 | 410 |
| EVICTED | 曾记录，因容量上限被 FIFO 淘汰 | 410 |
| UNKNOWN | 从未记录（或墓碑已老化） | 404 |

重新写入同一 traceId 时其旧墓碑作废（再次变 FOUND）。墓碑是纯进程内的尽力提示：
容量写满后最早的墓碑会被有界回收，因此“很久以前存在过”的标识最终会回归 UNKNOWN，
这是内存有界的明确代价。

配置（`application.properties`）：

```properties
trace.max-traces=1024        # 本地存储容量上限
trace.slow-threshold-ms=500  # 慢操作阈值
trace.ttl-ms=0               # 保留时长（毫秒），<=0 表示只受容量上限约束
trace.retention-sweep-ms=30000  # 过期扫描间隔（毫秒）
```

## 查询接口

### 按标识查详情

```
GET /api/traces/{traceId}
```

返回 `{trace, attribution}`：`trace` 为完整 `TraceSnapshot`（traceId、起止时间、
总耗时、整体状态、span 列表含父子关系/耗时/线程/状态/错误类别/延迟归因），
`attribution` 为上面的一层归因汇总。不存在返回 404（`reason=UNKNOWN`）；
已过期/被淘汰返回 410（`reason=EXPIRED|EVICTED`）。

### 按条件批量查最近的追踪

```
GET /api/traces?from=&to=&status=&errorCategory=&limit=&cursor=
```

- `from` / `to`：时间范围（epoch 毫秒，按 **trace 开始时间**过滤，闭区间）。
- `status`：整体结果筛选，`SUCCESS` / `FAILED`。
- `errorCategory`：失败最终归类筛选（如 `TIMEOUT`），只在失败结果中匹配；
  与 `status=SUCCESS` 同时给定时结果为空。
- `limit`：单页条数，1..500（默认 100），超限自动裁剪。
- `cursor`：上一页返回的不透明游标（Base64URL 编码的稳定排序键），不传表示第一页。

返回 `{items, nextCursor, hasMore}`，`items` 中每项为
`{trace, attribution}` 的列表项视图。

**排序与稳定性**：按 `(startEpochMs 倒序, traceId 倒序)` 排序，
游标只携带这两个稳定键（不含易变的写入序号），因此：
后台持续写入/淘汰不会让同一批结果来回抖动或出现半截记录；
沿 `nextCursor` 翻页对同一批数据不重不漏。非法 `status`/`errorCategory`/
时间范围/游标返回 400，且不泄露内部细节。

> **兼容范围**：数据全部在进程内，重启即清空；TTL 与容量仅约束本地副本，
> 不影响追踪埋点与业务调用。查询维度是在原“按标识查详情”之上的增量，
> 原详情字段（`trace` 内）保持不变，仅在外层增加 `attribution`。
> 查询接口 `/api/traces/**` 为只读操作，本身不开追踪、不写存储
> （`TraceFilter` 跳过该前缀），因此批量查询与翻页不会被“查询请求自身”插入的
> 记录污染，同一批结果可稳定复现。

## 验证方法

```bash
mvn test
```

测试以并发与异常路径为主，日志均打印 traceId 与判定依据：

- `TracerConcurrencyTest`：32 线程并发建链，验证不串链（每 trace 恰好 1 根 + 5 子、
  父链不跨 trace）、异步子操作归属提交点 span、三种失败类别可区分且不静默丢失。
- `TraceIntegrationTest`：端到端验证 `/api/demo/ok` 同步+异步子操作同属一条 trace、
  慢操作归因 SLOW、失败入口返回归一化类别且响应体不含异常细节、未知 traceId 返回 404。
- `ErrorClassifierTest`：类别可区分、异步/转译包装（含多层、cause 链）后结论一致、
  安全文案不泄露内部信息。
- `TraceAttributionTest`：一层归因口径——成功无失败类别、失败取最高优先级类别、
  span 排列不同（模拟同步直入 vs 绕异步聚合）结论一致、最耗时环节及并列兜底稳定。
- `BoundedTraceStoreTest`：容量上限确定、FIFO 淘汰确定、16 线程 × 200 次并发写入后
  size 不超容量且记录完整不混合。
- `BoundedTraceStoreRetentionTest`：TTL 过期回收、EXPIRED/EVICTED/UNKNOWN 三态区分、
  TTL 与容量同时触发时的边界、重新写入后墓碑作废、多条件筛选、稳定排序、
  游标分页不重不漏、limit 裁剪与非法游标拒绝、墓碑有界与老化、结果不可变。
- `BoundedTraceStoreConcurrencyTest`：TTL + 容量同时工作时，多线程写入/清理/
  按标识查询/批量查询并发进行，读不到半截记录、列表严格稳定倒序、size 不超容量。
- `TraceQueryIntegrationTest`：端到端验证列表多条件筛选与归因字段、成功不匹配失败类别、
  400 参数校验；纯 MockMvc 切片验证 410(EXPIRED/EVICTED) 与 404(UNKNOWN) 映射。
- `TraceRetentionIntegrationTest`：不启动容器，用可控时钟验证
  FOUND→EXPIRED→（墓碑老化）UNKNOWN 与容量 EVICTED 的状态流转。

### 本地手工验证过期回收

```properties
# application.properties
trace.ttl-ms=10000   # 仅保留 10 秒
```

```bash
mvn spring-boot:run
tid=$(curl -s -D - -o /dev/null http://localhost:8080/api/demo/ok | grep -i X-Trace-Id | tr -d '\r' | awk '{print $2}')
curl -i http://localhost:8080/api/traces/$tid   # 10 秒内：200
sleep 11
curl -i http://localhost:8080/api/traces/$tid   # 410 reason=EXPIRED
curl -i http://localhost:8080/api/traces/never-existed  # 404 reason=UNKNOWN
```
