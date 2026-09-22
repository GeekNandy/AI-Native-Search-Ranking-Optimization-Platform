package com.alpas.ainativesearchrankingoptimizationplatform.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class PlatformConfiguration {
    private final String adminToken;
    PlatformConfiguration(@Value("${platform.admin-token:}") String adminToken) { this.adminToken = adminToken; }
    @Bean Clock platformClock() { return Clock.systemUTC(); }

    @Bean FilterRegistrationBean<OncePerRequestFilter> administrativeAccess() {
        // A servlet filter covers both MVC controllers and Actuator's separate handler mapping.
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                       FilterChain chain) throws ServletException, IOException {
                String supplied = request.getHeader("X-Admin-Token");
                if (adminToken.length() < 32 || supplied == null || supplied.length() > 256
                        || !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
                                supplied.getBytes(StandardCharsets.UTF_8))) {
                    response.setStatus(403);
                    response.setContentType("application/problem+json");
                    response.getWriter().write("""
                            {"type":"about:blank","title":"Forbidden","status":403,
                             "detail":"Administrative access is disabled or the token is invalid."}
                            """);
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.addUrlPatterns("/api/v1/admin/*", "/actuator/prometheus");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
