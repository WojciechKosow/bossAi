package com.BossAi.bossAi.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SecurityEventService {

    public void log(SecurityEventType type,
                    String userIdentifier,
                    String ip,
                    String userAgent) {

        log.warn(
                "SECURITY_EVENT | type={} | user={} | ip={} | agent={}",
                type,
                userIdentifier,
                ip,
                userAgent
        );
    }
}
