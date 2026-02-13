package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.UserToken;
import com.BossAi.bossAi.repository.UserTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UserTokenCleanUpService {

    private final UserTokenRepository userTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<UserToken> tokensToDelete = userTokenRepository.findAll().stream()
                .filter(t -> t.isUsed() && t.getCreatedAt().isBefore(cutoff) && t.getExpiresAt().isBefore(LocalDateTime.now()))
                .toList();
        if (!tokensToDelete.isEmpty()) {
            userTokenRepository.deleteAll(tokensToDelete);
        }
    }
}
