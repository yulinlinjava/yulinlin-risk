package com.yulinlin.riskapp;

import com.yulinlin.security.spring.LoginAuthenticatorAuthenticationProvider;
import com.yulinlin.security.spring.SpringSessionSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebSecurity implements WebMvcConfigurer {
    @Bean AuthenticationManager adminAuthenticationManager(LoginAuthenticatorAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean SecurityFilterChain adminSecurity(HttpSecurity http, AuthenticationManager authentication,
                                            SpringSessionSupport sessions) throws Exception {
        http.securityMatcher("/admin", "/admin/**", "/login", "/logout", "/setup", "/auth.css")
                .authenticationManager(authentication)
                .authorizeHttpRequests(auth -> auth.requestMatchers("/login", "/setup", "/auth.css")
                        .permitAll().anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .formLogin(AbstractHttpConfigurer::disable)
                // MVC invokes the framework's logout(); retain native handlers for Servlet request.logout().
                .logout(logout -> logout.logoutRequestMatcher(request -> false))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")));
        sessions.configure(http);
        return http.build();
    }

    @Bean PasswordEncoder adminPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/admin/index.html");
        registry.addRedirectViewController("/admin", "/admin/index.html");
        registry.addRedirectViewController("/admin/", "/admin/index.html");
    }
}
