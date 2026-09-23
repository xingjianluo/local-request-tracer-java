package com.github.xingjianluo.localrequesttracerjava.web;

import com.github.xingjianluo.localrequesttracerjava.trace.Tracer;
import com.github.xingjianluo.localrequesttracerjava.trace.model.TraceSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 按追踪标识查询本地保存的完整追踪链。 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final Tracer tracer;

    public TraceController(Tracer tracer) {
        this.tracer = tracer;
    }

    @GetMapping("/{traceId}")
    public ResponseEntity<TraceSnapshot> get(@PathVariable String traceId) {
        return tracer.find(traceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
