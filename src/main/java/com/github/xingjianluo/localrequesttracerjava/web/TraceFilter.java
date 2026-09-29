package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 每个 HTTP 请求开启一条追踪链（SERVER 根 span）。
 *
 * <p>规则：
 * <ul>
 *   <li>traceId 由服务端生成，不信任外部传入，避免跨系统串链；</li>
 *   <li>响应头回写 X-Trace-Id，请求属性与 MDC 同步放置，便于日志关联；</li>
 *   <li>下游抛出的异常先标记当前 span 失败再原样上抛，
 *       由全局异常处理器统一产出对外错误体；</li>
 *   <li>finally 中关闭 trace 并清理 MDC，线程复用不会残留上下文；</li>
 *   <li>追踪查询接口（/api/traces，列表与详情）不开链落库，
 *       查询动作不混进被统计的追踪结果（见 {@link #shouldNotFilter}）。</li>
 * </ul>
 */
@Component
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    private static final String MDC_KEY = "traceId";

    /**
     * 追踪查询接口自身的路径前缀。查询动作是"读取统计结果"，不属于被统计的业务请求：
     * 若也为其开链落库，同一条件每查一次就多冒出一条新记录，重复查询结果不断漂移，
     * 因此这类请求直接放行，不开链、不落库、不写 MDC。
     */
    private static final String QUERY_API_PREFIX = "/api/traces";

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private final Tracer tracer;

    public TraceFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isQueryApi(request);
    }

    /** 兼容 servlet 前缀/上下文路径：用请求 URI 后缀匹配查询接口前缀。 */
    private static boolean isQueryApi(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals(QUERY_API_PREFIX) || uri.startsWith(QUERY_API_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String name = request.getMethod() + " " + request.getRequestURI();
        Tracer.TraceHandle handle = tracer.beginTrace(name, "SERVER");
        String traceId = handle.traceId();
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException | Error e) {
            tracer.markCurrentFailed(unwrap(e));
            throw e;
        } finally {
            handle.close();
            MDC.remove(MDC_KEY);
            log.debug("trace finished traceId={} uri={}", traceId, request.getRequestURI());
        }
    }

    /** ServletException 常包装真实异常，归因时剥一层。 */
    private Throwable unwrap(Throwable e) {
        if (e instanceof ServletException && e.getCause() != null) {
            return e.getCause();
        }
        return e;
    }
}
