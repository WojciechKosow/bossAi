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
    private final IpResolver ipResolver;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain
    ) throws ServletException, IOException {
        String ip = ipResolver.resolveClientIp(request);
        String path = request.getRequestURI();

        if (path.equals("/api/auth/register")) {
            rateLimitService.checkRateLimit(ip + ":register", RateLimitType.REGISTER);
        }

        if (path.equals("/api/auth/login")) {
            rateLimitService.checkRateLimit(ip + ":login", RateLimitType.LOGIN);
        }

        if (path.equals("/api/auth/forgot-password")) {
            rateLimitService.checkRateLimit(ip + ":forgot", RateLimitType.FORGOT_PASSWORD);
        }

        if (path.equals("/api/auth/resend-verification-email")) {
            rateLimitService.checkRateLimit(ip + ":resend", RateLimitType.RESEND_VERIFICATION);
        }

        if (path.contains("/api/generations/image")) {
            rateLimitService.checkRateLimit(ip + ":gen_img", RateLimitType.GENERATE_IMAGE);

            if (request.getUserPrincipal() != null) {
                String user = request.getUserPrincipal().getName();

                rateLimitService.checkRateLimit(user + ":gen_img", RateLimitType.GENERATE_IMAGE);
            }
        }

        if (path.contains("/api/generations/video")) {
            rateLimitService.checkRateLimit(ip + ":gen_vid", RateLimitType.GENERATE_VIDEO);

            if (request.getUserPrincipal() != null) {
                String user = request.getUserPrincipal().getName();
                rateLimitService.checkRateLimit(user + ":gen_vid", RateLimitType.GENERATE_VIDEO);
            }
        }

        filterChain.doFilter(request, response);
    }
}
