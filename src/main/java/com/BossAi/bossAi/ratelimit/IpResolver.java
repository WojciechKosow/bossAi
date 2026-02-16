package com.BossAi.bossAi.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class IpResolver {

    public String resolveClientIp(HttpServletRequest request) {
        String cfHeader = request.getHeader("CF-Connecting-IP");

        if (isValid(cfHeader)) {
            return cfHeader;
        }

        String xForwardedFor  = request.getHeader("X-Forwarded-For");
        if (isValid(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (isValid(xRealIp)) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    private boolean isValid(String header) {
        return header != null && !header.isBlank() && !"unknown".equalsIgnoreCase(header);
    }
}
