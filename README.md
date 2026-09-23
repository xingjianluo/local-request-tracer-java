# local-request-tracer-java

本地请求追踪与延迟归因能力：一次请求形成一条完整追踪链，跨线程/异步子操作归属同一追踪；
失败与慢操作被分类归因；追踪数据本地有界保存并可按标识查询。不依赖任何外部服务。

## 追踪上下文传播规则

- **入口**：`TraceFilter`（每个 HTTP 请求）调用 `TraceContext.startTrace()` 生成 `traceId`，
  开启根 span（`METHOD + URI`），响应头回写 `X-Trace-Id`，请求结束在 `finally` 中
  `endSpan` + `endTrace`，保证线程不残留上下文。
- **线程内**：`TraceContext` 基于 `ThreadLocal` 保存 `traceId` 与 span 栈，
  `startSpan`/`endSpan` 成对出现，子 span 自动记录 `parentSpanId`。
- **跨线程/异步**：提交任务时用 `TraceExecutors.tracePropagating(executor)` 或
  `TraceExecutors.wrap(runnable)` 包装。快照在**提交线程**抓取（不可变），
  在**执行线程**安装，任务结束后通过 `Scope.close()` 还原执行线程原有上下文——
  线程池线程不会被污染，并发请求之间互不串链。
- **降级**：无上下文时所有 `TraceContext` 操作为 no-op，追踪开销不拖垮正常请求。

## 错误分类口径

原始异常由 `ErrorClassifier` 归一化为稳定类别，**从根因向表层**沿 cause 链匹配，
包装异常不会掩盖真正原因，同一类失败在不同入口下结论一致：

| 类别 | 判定依据 |
|---|---|
| `TIMEOUT` | `java.util.concurrent.TimeoutException` |
| `IO` | `java.io.IOException` |
| `VALIDATION` | `IllegalArgumentException` |
| `CONCURRENCY` | `RejectedExecutionException` / `CancellationException` |
| `RESOURCE` | `IllegalStateException` |
| `UNKNOWN` | 以上均不匹配 |
| `NONE` | 无异常 |

对外结果（`GlobalExceptionHandler`、`/traces/{id}` 查询）只暴露类别与 `traceId`，
不暴露堆栈、异常类名或敏感细节。慢操作（耗时 ≥ `trace.slow-threshold-ms`）在 span 上标记 `slow=true`。

## 存储上限与淘汰

- 实现：`InMemoryTraceStore`，access-order `LinkedHashMap` + 同步块。
- 容量：`trace.store-capacity`（默认 1024 条追踪），超限**确定性地淘汰最久未访问**的追踪。
- 完整性：`SpanRecord` 为不可变对象，单次 `addSpan` 原子可见；
  高并发写入下不会出现半截记录或跨追踪混合。

## 配置

```properties
trace.store-capacity=1024      # 本地保存的最大追踪条数
trace.slow-threshold-millis=500 # 慢操作阈值（毫秒）
```

## 接口

| 接口 | 说明 |
|---|---|
| `GET /demo/ok` | 同步正常路径 |
| `GET /demo/async` | 异步子操作（线程池），子操作归属同一追踪 |
| `GET /demo/fail?kind=validation\|timeout\|io` | 失败路径，返回归一化类别 |
| `GET /demo/slow?millis=800` | 慢操作路径 |
| `GET /traces/{traceId}` | 按追踪标识查询全部 span（耗时、归属、类别、slow 标记） |

## 验证方法

```bash
mvn test
```

测试以并发与异常路径为主，日志中打印 `traceId` 与判定依据：

- `TraceContextConcurrencyTest`：32 线程并发各自成链，验证不串链、span 归属正确。
- `TraceExecutorsTest`：异步子操作 traceId 与父一致、`parentSpanId` 链接正确、
  池线程任务后无上下文残留。
- `ErrorClassifierTest`：类别可区分、包装异常与根因结论一致、不静默丢失。
- `InMemoryTraceStoreTest`：容量上限、LRU 淘汰确定性、并发写入无半截记录/跨追踪混合。
- `TraceWebIntegrationTest`：响应头 `X-Trace-Id`、异步归属、错误类别与响应体一致、
  响应不泄露内部细节、慢操作被标记。

手工验证：

```bash
mvn spring-boot:run
curl -i localhost:8080/demo/async          # 取响应头 X-Trace-Id
curl localhost:8080/traces/<traceId>       # 查询该次请求完整追踪链
```
