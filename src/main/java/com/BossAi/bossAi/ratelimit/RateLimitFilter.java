package com.BossAi.bossAi.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        String path = request.getRequestURI();

        if (path.contains("/api/auth/register")) {
            rateLimitService.checkRateLimit(ip + ":register", RateLimitType.REGISTER);
        }

        if (path.contains("/api/auth/login")) {
            rateLimitService.checkRateLimit(ip + ":login", RateLimitType.LOGIN);
        }

        if (path.contains("/api/auth/forgot-password")) {
            rateLimitService.checkRateLimit(ip + ":forgot", RateLimitType.FORGOT_PASSWORD);
        }

        if (path.contains("/api/auth/resend-verification-email")) {
            rateLimitService.checkRateLimit(ip + ":resend", RateLimitType.RESEND_VERIFICATION);
        }

        filterChain.doFilter(request, response);
    }
}
