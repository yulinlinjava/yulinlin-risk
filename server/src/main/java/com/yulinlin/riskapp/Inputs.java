package com.yulinlin.riskapp;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class Inputs {
    static final String BEHAVIOR = "video-play-url";
    static final long RETENTION = Duration.ofDays(1).toMillis();

    private Inputs() {}

    static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || value.indexOf('\0') >= 0) {
            throw bad(field + " 不能为空或超过 " + maxLength + " 字符");
        }
        return value;
    }

    static void behavior(String value) {
        if (!BEHAVIOR.equals(value)) throw bad("当前仅支持 " + BEHAVIOR);
    }

    static int offset(int page, int pageSize, int maxPageSize) {
        if (page < 1 || pageSize < 1 || pageSize > maxPageSize
                || ((long) page - 1) * pageSize > Integer.MAX_VALUE) {
            throw bad("分页参数超出范围");
        }
        return (page - 1) * pageSize;
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
