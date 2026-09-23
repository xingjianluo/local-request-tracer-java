package com.github.xingjianluo.localrequesttracerjava.tests.trace;

import com.github.xingjianluo.localrequesttracerjava.trace.ErrorCategory;
import com.github.xingjianluo.localrequesttracerjava.trace.ErrorClassifier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 原始异常归一化：类别可区分、包装后结论一致、不静默丢失。
 */
class ErrorClassifierTest {

    private static final Logger log = LoggerFactory.getLogger(ErrorClassifierTest.class);

    @Test
    void categoriesAreDistinguishable() {
        assertCategory(ErrorCategory.TIMEOUT, new TimeoutException("t"));
        assertCategory(ErrorCategory.IO, new IOException("io"));
        assertCategory(ErrorCategory.VALIDATION, new IllegalArgumentException("bad"));
        assertCategory(ErrorCategory.CONCURRENCY, new RejectedExecutionException("rejected"));
        assertCategory(ErrorCategory.RESOURCE, new IllegalStateException("state"));
        assertCategory(ErrorCategory.UNKNOWN, new RuntimeException("???"));
        assertCategory(ErrorCategory.NONE, null);

        // 判定依据：不同原始异常必须落入不同类别
        assertNotEquals(ErrorClassifier.classify(new TimeoutException("t")),
                ErrorClassifier.classify(new IOException("io")));
        log.info("判定依据=TIMEOUT({}) != IO({})，类别可区分",
                ErrorClassifier.classify(new TimeoutException("t")),
                ErrorClassifier.classify(new IOException("io")));
    }

    @Test
    void wrappedExceptionsClassifyConsistently() {
        // 同一类失败，无论是否被不同入口包装，结论必须一致
        IOException root = new IOException("disk gone");
        ErrorCategory bare = ErrorClassifier.classify(root);
        ErrorCategory wrapped = ErrorClassifier.classify(new RuntimeException("entry A", root));
        ErrorCategory doubleWrapped = ErrorClassifier.classify(
                new IllegalStateException("entry B", new RuntimeException(root)));
        log.info("判定依据=bare({}) == wrapped({}) == doubleWrapped({})", bare, wrapped, doubleWrapped);
        assertEquals(ErrorCategory.IO, bare);
        assertEquals(bare, wrapped);
        assertEquals(bare, doubleWrapped);
    }

    private void assertCategory(ErrorCategory expected, Throwable t) {
        ErrorCategory actual = ErrorClassifier.classify(t);
        log.info("error={} category={} 判定依据=预期 {}", t == null ? "null" : t.getClass().getSimpleName(), actual, expected);
        assertEquals(expected, actual);
    }
}
