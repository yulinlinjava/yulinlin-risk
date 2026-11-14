package com.yulinlin.risk.sdk;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@ConditionalOnClass({WebMvcConfigurer.class, HttpServletRequest.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "yulinlin.risk", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RiskClientProperties.class)
public class RiskAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    RiskTransport riskTransport(RiskClientProperties properties) {
        return new HttpRiskTransport(properties);
    }

    @Bean(initMethod = "start", destroyMethod = "close") @ConditionalOnMissingBean
    RiskClient riskClient(RiskClientProperties properties, RiskTransport transport) {
        return new RiskClient(properties, transport);
    }

    @Bean @ConditionalOnMissingBean
    RiskUserResolver riskUserResolver() {
        return request -> request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
    }

    @Bean @ConditionalOnMissingBean
    RiskResourceResolver riskResourceResolver() {
        return RiskReportInterceptor::defaultResource;
    }

    @Bean
    WebMvcConfigurer riskWebMvcConfigurer(RiskClient client, RiskUserResolver users, RiskResourceResolver resources) {
        var interceptor = new RiskReportInterceptor(client, users, resources);
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
