package com.BossAi.bossAi.service;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCleanUpService {

    private final UserRepository userRepository;
    private final BetaConfig betaConfig;

    @Scheduled(cron = "0 0 2 * * ?")
    public void removeUnverifiedAccounts() {
        if (betaConfig.isBetaMode()) {
            log.debug("[UserCleanUpService] Beta mode — skipping unverified account deletion");
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<User> usersToDelete = userRepository.findAll().stream()
                .filter(u -> !u.isEnabled() && u.getCreatedAt().isBefore(cutoff))
                .toList();
        if (!usersToDelete.isEmpty()) {
            userRepository.deleteAll(usersToDelete);
        }
    }
}
