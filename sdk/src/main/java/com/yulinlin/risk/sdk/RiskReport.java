package com.yulinlin.risk.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Opts a Spring MVC controller method into asynchronous behavior reporting. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RiskReport {
    String action();
    String resourceType() default "";
    /** A path-variable or query-parameter name; not an expression or request-body path. */
    String resource() default "";
}
