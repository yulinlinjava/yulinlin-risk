package com.yulinlin.risk.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RiskAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RiskAutoConfiguration.class));

    @Test void disabledByDefaultAndEnabledConfigurationCreatesClientWithCustomIdentityResolver() {
        runner.run(context -> assertThat(context).doesNotHaveBean(RiskClient.class));
        RiskUserResolver custom = request -> "custom";
        runner.withPropertyValues("yulinlin.risk.enabled=true", "yulinlin.risk.base-url=http://localhost:8080",
                        "yulinlin.risk.api-key=test-key")
                .withBean(RiskTransport.class, RiskClientTest.FakeTransport::new)
                .withBean(RiskUserResolver.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(RiskClient.class);
                    assertThat(context.getBean(RiskUserResolver.class)).isSameAs(custom);
                    assertThat(context.getBean(RiskClient.class).tryReport(RiskClientTest.event("configured"))).isTrue();
                });
    }

    @Test void enabledWithoutCredentialsFailsAtStartup() {
        runner.withPropertyValues("yulinlin.risk.enabled=true", "yulinlin.risk.base-url=http://localhost:8080")
                .run(context -> assertThat(context).hasFailed());
    }
}
