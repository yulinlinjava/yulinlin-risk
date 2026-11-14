package com.yulinlin.risk.sdk;

import jakarta.servlet.http.HttpServletRequest;

/** Resolve identity from trusted authentication state. Return null to skip anonymous requests. */
@FunctionalInterface
public interface RiskUserResolver {
    String resolve(HttpServletRequest request);
}
