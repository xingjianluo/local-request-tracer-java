package com.github.xingjianluo.localrequesttracerjava.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.xingjianluo.localrequesttracerjava.trace.TraceContext;

import java.io.IOException;

/**
 * 每个 HTTP 请求开启一条追踪，响应头回写 traceId。
 */
@Component
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.startTrace();
        response.setHeader(TRACE_HEADER, traceId);
        TraceContext.startSpan(request.getMethod() + " " + request.getRequestURI());
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            TraceContext.endSpan(failure);
            TraceContext.endTrace();
        }
    }
}
