package com.yulinlin.risk.sdk;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.method.HandlerMethod;

@FunctionalInterface
public interface RiskResourceResolver {
    String resolve(HttpServletRequest request, HandlerMethod handler, RiskReport annotation);
}
