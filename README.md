# local-request-tracer-java

本地请求追踪与延迟归因：一次请求在同步与异步链路中的耗时、异常与归属关系被稳定记录，
数据保存在进程内，支持按标识查详情与按条件批量查最近追踪，不依赖任何外部服务。

## 快速开始

```bash
mvn spring-boot:run

# 触发一条正常链路（含同步 + 异步子操作）
curl -i http://localhost:8080/api/demo/ok
# 响应头携带 X-Trace-Id，用它查询完整追踪链
curl http://localhost:8080/api/traces/<traceId>

# 其他演示入口
curl http://localhost:8080/api/demo/slow?ms=600        # 慢操作归因
curl http://localhost:8080/api/demo/fail?type=timeout  # 失败归一化（type: validation/conflict/timeout/upstream/io/unknown）
```

## 查询接口

### 按标识查详情

```
GET /api/traces/{traceId}
```

返回 `TraceSnapshot`：traceId、起止时间、总耗时、整体状态（任一 span 失败即 FAILED）、
span 列表（含父子关系、耗时、线程、状态、错误类别、延迟归因），以及一层归因汇总
`attribution`（见下）。按标识查询有三种"没有"的明确结论：

| 场景 | HTTP | body.reason |
|---|---|---|
| 记录完整在库 | 200 | — |
| 曾记录，但超过保留时长被过期回收 | 410 | `EXPIRED` |
| 曾记录，但因容量上限被 FIFO 顶掉 | 410 | `EVICTED` |
| 从未记录过该标识 | 404 | `NEVER_RECORDED` |

> "已经过期回收了"和"压根没记录过"由此区分开。回收登记本身有界
> （保留最近 `max-traces` 个回收结论），同 traceId 重新写入会撤销其旧登记。

### 按条件批量查最近追踪

```
GET /api/traces?from=&to=&status=&category=&limit=&cursor=
```

| 参数 | 含义 |
|---|---|
| `from` / `to` | 时间窗起止，**epoch 毫秒，按 trace 结束时间过滤，两端含边界**；缺省（0）表示该侧不限 |
| `status` | 整体结果：`SUCCESS` / `FAILED`；缺省不过滤成败 |
| `category` | 失败类别（如 `TIMEOUT`/`VALIDATION_ERROR`），非空时隐含只取失败 |
| `limit` | 单页条数，默认 50，最大 200 |
| `cursor` | 上一页返回的不透明分页游标 |

返回 `TracePage`：`items`（不含 span 明细的 `TraceSummary`，但带同一层 `attribution`）、
`nextCursor`、`hasMore`。

**排序与稳定性**：统一按 `(结束时间倒序, traceId 倒序)`。游标只承载上一页最后一条的
`(endEpochMs, traceId)` 且不依赖存储当前内容，因此同条件翻页次序可复现、不重不漏；
后台持续写入不会让同一批结果抖动，窗口外的新写入也不会混进结果。
列表查询在锁内拷贝一份一致快照后再过滤排序，条目要么完整要么不出现，不会读到半截。

**查询动作本身不落库**：`/api/traces`（含列表与按标识查详情）是"读取统计结果"的入口，
`TraceFilter` 对这类请求直接放行，不开链、不落库（响应也不带 `X-Trace-Id`）。
因此查询请求不会混进被统计的追踪：同一筛选条件连续重复查询，结果集合、次序乃至
响应体都保持一致，不会"越查冒出的新条目越多"。被统计的只有 `/api/demo/**` 等
真实业务入口。

## 一层归因汇总（列表与详情同口径）

`TraceSnapshot.attribution` / `TraceSummary.attribution` 为：

```jsonc
{
  "errorCategory": "TIMEOUT",        // 整链最终失败类别；成功链路为 null
  "slowestSpan": {                   // 最费时间的一环：名字 + 耗时
    "spanId": "...", "name": "wait-http", "durationMs": 812
  }
}
```

口径：

- **最终失败类别**：span 按结束先后排列，取**第一个 FAILED span** 的归一化类别，
  代表链路上最早暴露失败的一环。
- **最慢一环**：优先在**子 span** 中取 `durationMs` 最大者（根 span 包住整条链路、
  耗时天然最大，不指向具体卡点，故不参与）；整条链路只有根 span 时回退到根 span；
  并列耗时取更早出现的一环，结论确定。
- **归类与路径无关，且以更深处更具体的根因为准**：判定只依赖异常类型。
  `ErrorClassifier` 遍历异常的整条 cause 链（`CompletionException`/`ExecutionException`
  等异步透明包装本身只落到兜底类别，无需特判剥层），在链上按以下规则选出唯一类别：
  1. **具体度高者胜**：兜底（UNKNOWN/INTERNAL_ERROR）最低，超时（TIMEOUT）是比上游
     IO 失败（UPSTREAM_FAILURE）更具体的归因，其余明确业务类别居中。因此**即使中间
     那层异常本身也对得上某个具体类别，只要更深处还有更明确的根因，最终仍以更具体的
     根因为准**。例如 `IllegalStateException`(→CONFLICT) 包着 `SocketTimeoutException`
     (→TIMEOUT)，最终归 `TIMEOUT`，而不是 CONFLICT；
  2. **具体度相同时取链上更深者**：同一条根因无论从同步入口直接抛、绕异步
     （CompletableFuture/线程池），还是中途被任意 `RuntimeException`/受检异常包装或
     转译（哪怕外层本身命中另一个具体类别），最终类别都一致，按失败类别做统计不会
     因路径不同被拆成两类；
  3. **兜底与成环安全**：整条链上确实没有更具体的根因时，维持外层已有的具体类别；
     链上只有兜底类别时给 INTERNAL_ERROR/UNKNOWN；cause 自引用或互相引用成环时
     用身份集合安全终止，绝不卡死或报错。

  对外安全文案仍只来自类别定义，不含异常类名、消息或堆栈。

## 追踪上下文传播规则

- **入口**：`TraceFilter` 为每个 HTTP 请求开启一条追踪链（SERVER 根 span），
  traceId 由服务端生成（不信任外部传入，避免跨系统串链），
  通过响应头 `X-Trace-Id`、请求属性 `traceId` 与日志 MDC 暴露。
- **同线程**：`TraceContext` 用 ThreadLocal 维护未结束 span 栈，子 span 自动以栈顶 span 为父。
- **跨线程 / 异步**：提交方 `TraceContext.capture()` 捕获 `{traceId, 当前spanId}`，
  执行方 `attach()` 安装；执行线程的首个 span 以提交时刻的 span 为父，
  之后恢复线程内正常栈式父子关系。`TraceTaskDecorator` 已挂到应用主异步执行器
  （`@Primary Executor`），`@Async` 与注入 `Executor` 的任务自动获得传播能力。
- **清理 / 降级**：`Scope` 关闭即移除安装，请求结束在 finally 中关闭 trace 并清理
  ThreadLocal 与 MDC，线程复用不串链；无上下文时 `startSpan` 返回 null，静默降级。

## 错误分类口径

| 类别 | HTTP | 典型来源 |
|---|---|---|
| VALIDATION_ERROR | 400 | IllegalArgumentException、参数绑定/校验失败 |
| UNAUTHENTICATED | 401 | 显式 401 语义异常 |
| FORBIDDEN | 403 | AccessDeniedException（按类名匹配，无 security 硬依赖） |
| NOT_FOUND | 404 | 静态资源不存在等 |
| CONFLICT | 409 | IllegalStateException |
| RATE_LIMITED | 429 | 线程池拒绝 RejectedExecutionException |
| UPSTREAM_FAILURE | 502 | ConnectException / IOException（含被包装在 cause 链中） |
| TIMEOUT | 504 | TimeoutException / SocketTimeoutException / HttpTimeoutException |
| INTERNAL_ERROR | 500 | 其他未识别 RuntimeException |
| UNKNOWN | 500 | 未识别受检异常 |

**对外口径**：响应体只有 `{category, message, traceId}`，message 固定为类别的
安全文案，绝不包含异常类名、消息或堆栈；原始异常只进服务端日志（带 traceId）。
span 首次失败结论为准，避免后续覆盖导致归因漂移。

**延迟归因**：span 耗时 ≥ `trace.slow-threshold-ms` 标记 `SLOW`，否则 `NORMAL`，
并输出慢操作日志（含 traceId、span 名、耗时、阈值）。

## 存储：容量上限与时间过期如何配合

`BoundedTraceStore` 同时受两层回收约束，规则明确、**先到先算**：

1. **按时间过期**：以 trace 的**结束时间** `endEpochMs` 计，
   `endEpochMs + trace.retention-ms <= now` 即过期（边界含到期那一刻）。
   `trace.retention-ms=0` 表示关闭按时间过期。
2. **按容量 FIFO**：写满 `trace.max-traces` 后顶掉最早写入的。

每次 `save` 在**同一把锁**内按固定顺序处理：**先清过期，再做容量检查**。
即一条记录若在写入点恰好既过期又"该被顶掉"，离开原因记为 `EXPIRED`（先到先算）；
容量顶掉记为 `EVICTED`。过期回收除了在写入时顺带执行，也会在 `lookup`/`query` 时
顺带触发，另有可配置的后台扫描。所有增删与回收登记同锁，落库对象是 trace 关闭时
一次性生成的不可变快照（span 列表防御式拷贝），并发查询要么拿到完整记录要么得到
明确的"没有"，不存在半截记录。

配置（`application.properties`）：

```properties
trace.max-traces=1024        # 本地存储容量上限
trace.slow-threshold-ms=500  # 慢操作阈值
trace.retention-ms=3600000   # 保留时长（毫秒，按结束时间）；0 关闭按时间过期
trace.sweep-interval-ms=30000 # 后台过期扫描间隔（毫秒）；0 不启动后台扫描
```

> 后台扫描仅当 `retention-ms>0` 且 `sweep-interval-ms>0` 时注册；
> 关掉后过期回收仍会在写入/查询时顺带完成，只是不主动清。

开销：追踪仅为 ThreadLocal 读写 + 结束时的不可变对象组装，无 I/O；
存储锁只在落库/回收/查询拷贝瞬间持有，查询在拷贝后于锁外过滤排序。

## 验证方法

```bash
mvn test
```

- `TraceRetentionTest`：过期边界（end+ttl 先到先算）、过期三态（EXPIRED/EVICTED/
  NEVER_RECORDED）可区分、过期与容量同时触发时原因不串、同 id 重写撤销旧登记、
  主动 purge、以及 8 读 4 写并发清理下读不到半截记录。
- `TraceQueryTest`：时间窗（含边界）+ 成败 + 失败类别多条件筛选、
  结束时间倒序与 traceId 稳定次序、limit 默认值/封顶、游标分页不重不漏且可拼接成全量、
  坏游标从头开始、后台持续写入时同一条件结果不抖动且条目完整。
- `TraceAttributionTest`：成功链路最慢子环、失败链路整链类别与最慢环、
  同根因走同步/异步/CompletableFuture 包装三类路径归类一致、只有根 span 时回退、并列取更早，
  以及**外层本身命中具体类别（CONFLICT）但深处是更具体的 TIMEOUT 根因时，
  同步与异步路径都归 TIMEOUT**。
- `ErrorClassifierTest`：类别可区分、异步包装与**任意 cause 链包装**结论一致、
  **外层具体类别被深处更具体根因压过**（CONFLICT/VALIDATION 包超时或连接失败等）、
  同根因多路径（直接抛/异步/转译/多层包装）只归一类、链上无更具体根因时维持外层、
  兜底类别成环与**具体类别互相引用成环**都安全终止、安全文案不泄露内部信息。
- `TraceQueryIntegrationTest`：端到端验证列表多条件筛选与归因字段、410/404 区分、游标翻页，
  以及**查询请求自身不落追踪（无 `X-Trace-Id`、连续重复查询响应体与结果集合不漂移、
  同类别记录被同一条件一次查全）**。
- `TracerConcurrencyTest`（回归）：32 线程并发建链不串链、异步子操作归属、失败不静默丢失。
- `TraceIntegrationTest` / `BoundedTraceStoreTest`（回归）：端到端追踪与原有容量 FIFO、并发写入。

手动验证过期三态（可选）：

```bash
# 用极短保留时长启动，请求后等待超过保留时长再查详情，观察 410/EXPIRED 与 404/NEVER_RECORDED
mvn spring-boot:run -Dspring-boot.run.arguments=--trace.retention-ms=3000,--trace.sweep-interval-ms=0
```
