package com.github.xingjianluo.localrequesttracerjava.tests.web;

import com.github.xingjianluo.localrequesttracerjava.trace.BoundedTraceStore;
import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.model.FindStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.SpanStatus;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 不启动 Web 容器的留存集成验证：可控时钟下
 * 200 命中 → 410 口径(EXPIRED) → 404 口径(UNKNOWN) 的状态流转，
 * 以及容量淘汰的 EVICTED 口径。避免短 TTL 配置污染共享的 Spring 上下文。
 * （HTTP 层 410/404 映射由 {@link TraceQueryIntegrationTest} 覆盖 404，
 * 状态到 HTTP 的映射是控制器直连逻辑。）
 */
class TraceRetentionIntegrationTest {

    private static TraceSnapshot ok(String id, long start) {
        return new TraceSnapshot(id, start, start + 10, 10, SpanStatus.SUCCESS, List.of());
    }

    @Test
    void statusTransitionsFoundExpiredUnknownAndEvicted() {
        AtomicLong now = new AtomicLong(1_000L);
        BoundedTraceStore store = new BoundedTraceStore(2, 100L, now::get);
        Tracer tracer = new Tracer(store, new ErrorClassifier(), 500);

        store.save(ok("t", now.get()));
        assertEquals(FindStatus.FOUND, tracer.findDetailed("t").status());

        // 超过 TTL（结束时间 start+10 起算 100ms）
        now.set(1_111L);
        assertEquals(FindStatus.EXPIRED, tracer.findDetailed("t").status(),
                "Tracer 透传的详情查询必须给出过期口径");

        // 容量淘汰另一条：EVICTED
        now.set(2_000L);
        store.save(ok("a", now.get()));
        store.save(ok("b", now.get()));
        store.save(ok("c", now.get()));
        assertEquals(FindStatus.EVICTED, store.findDetailed("a").status());

        // 从未记录
        assertEquals(FindStatus.UNKNOWN, tracer.findDetailed("never").status());
    }
}
