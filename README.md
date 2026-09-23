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

## 存储上限与淘汰

- `BoundedTraceStore` 容量由 `trace.max-traces` 固定（默认 1024）。
- 写满后按 **FIFO** 淘汰最早写入的追踪，淘汰顺序与查询频率无关，行为确定。
- 插入与淘汰在同一把内部锁下完成，`size` 永不超过容量。
- 落库对象是 trace 关闭时一次性生成的**不可变快照**（`TraceSnapshot`，
  span 列表防御式拷贝），高并发写入下不会出现半截记录或跨追踪混合；
  查询接口只返回快照。
- 开销控制：追踪仅为 ThreadLocal 读写 + 结束时的不可变对象组装，
  无 I/O、无锁竞争热点（存储锁只在落库瞬间持有）；无上下文时直接降级。

配置（`application.properties`）：

```properties
trace.max-traces=1024        # 本地存储容量上限
trace.slow-threshold-ms=500  # 慢操作阈值
```

## 查询接口

```
GET /api/traces/{traceId}
```

返回 `TraceSnapshot`：traceId、起止时间、总耗时、整体状态（任一 span 失败即 FAILED）、
span 列表（含父子关系、耗时、线程、状态、错误类别、延迟归因）。不存在返回 404。

## 验证方法

```bash
mvn test
```

测试以并发与异常路径为主，日志均打印 traceId 与判定依据：

- `TracerConcurrencyTest`：32 线程并发建链，验证不串链（每 trace 恰好 1 根 + 5 子、
  父链不跨 trace）、异步子操作归属提交点 span、三种失败类别可区分且不静默丢失。
- `TraceIntegrationTest`：端到端验证 `/api/demo/ok` 同步+异步子操作同属一条 trace、
  慢操作归因 SLOW、失败入口返回归一化类别且响应体不含异常细节、未知 traceId 返回 404。
- `ErrorClassifierTest`：类别可区分、异步包装后结论一致、安全文案不泄露内部信息。
- `BoundedTraceStoreTest`：容量上限确定、FIFO 淘汰确定、16 线程 × 200 次并发写入后
  size 不超容量且记录完整不混合。
