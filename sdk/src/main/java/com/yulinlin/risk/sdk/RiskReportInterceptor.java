package com.yulinlin.risk.sdk;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Captures only opted-in MVC requests. Async redispatch keeps the original observation. */
public final class RiskReportInterceptor implements AsyncHandlerInterceptor {
    private static final String OBSERVATION = RiskReportInterceptor.class.getName() + ".observation";
    private final Map<Method, Optional<RiskReport>> metadata = new ConcurrentHashMap<>();
    private final RiskClient client;
    private final RiskUserResolver users;
    private final RiskResourceResolver resources;

    public RiskReportInterceptor(RiskClient client, RiskUserResolver users, RiskResourceResolver resources) {
        this.client = client;
        this.users = users;
        this.resources = resources;
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || request.getAttribute(OBSERVATION) != null) return true;
        request.setAttribute(OBSERVATION, Boolean.FALSE);
        try {
            var annotation = metadata.computeIfAbsent(method.getMethod(), key ->
                    Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(key, RiskReport.class)));
            if (annotation.isEmpty()) return true;
            String userId = users.resolve(request);
            if (userId == null || userId.isBlank()) return true;
            var report = annotation.get();
            String resource = resources.resolve(request, method, report);
            if (resource != null && resource.isBlank()) resource = null;
            String type = resource == null ? null : (report.resourceType().isBlank() ? report.action() : report.resourceType());
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern == null) { client.captureFailed(); return true; }
            request.setAttribute(OBSERVATION, new Observation(UUID.randomUUID().toString(), userId, report.action(),
                    type, resource, pattern.toString(), request.getMethod(), System.currentTimeMillis(), request.getRemoteAddr()));
        } catch (RuntimeException e) {
            client.captureFailed();
        }
        return true;
    }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(request.getAttribute(OBSERVATION) instanceof Observation event) || !event.completed.compareAndSet(false, true)) return;
        try {
            int status = ex != null && response.getStatus() < 400 ? 500 : response.getStatus();
            client.tryReport(new RiskEvent(event.id, event.user, event.action, event.type, event.resource,
                    event.route, event.method, event.startedAt, 0, status, event.ip));
        } catch (RuntimeException ignored) {
            client.captureFailed();
        }
    }

    /** Reads path variables first, then request parameters; request bodies are never consumed. */
    public static String defaultResource(HttpServletRequest request, HandlerMethod method, RiskReport report) {
        if (report.resource().isBlank()) return null;
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map && map.get(report.resource()) != null) return map.get(report.resource()).toString();
        // getParameter can parse form bodies, so only inspect the raw query string here.
        String query = request.getQueryString();
        if (query == null) return null;
        for (String field : query.split("&")) {
            int equals = field.indexOf('=');
            String name = equals < 0 ? field : field.substring(0, equals);
            if (report.resource().equals(URLDecoder.decode(name, StandardCharsets.UTF_8))) {
                return equals < 0 ? "" : URLDecoder.decode(field.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static final class Observation {
        final String id, user, action, type, resource, route, method, ip;
        final long startedAt;
        final AtomicBoolean completed = new AtomicBoolean();
        Observation(String id, String user, String action, String type, String resource, String route, String method,
                    long startedAt, String ip) {
            this.id = id; this.user = user; this.action = action; this.type = type; this.resource = resource;
            this.route = route; this.method = method; this.startedAt = startedAt; this.ip = ip;
        }
    }
}
