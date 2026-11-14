package com.yulinlin.riskapp;

import com.yulinlin.security.risk.RiskProperties;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(excludeName = {
        // Register the existing pool with the ORM instead of opening a second H2 database.
        "com.yulinlin.jdbc.h2.H2AutoConfiguration",
        "com.yulinlin.data.core.http.HttpAutoConfiguration",
        "com.yulinlin.security.risk.RiskAutoConfiguration"
})
@EnableScheduling
public class RiskApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }

    @Bean Clock riskClock() {
        return Clock.systemUTC();
    }

    @Bean RiskProperties detectionRules() {
        var rules = new RiskProperties();
        rules.setBehaviors(Map.of(Inputs.BEHAVIOR, new RiskProperties.Behavior()));
        rules.validate();
        return rules;
    }
}
