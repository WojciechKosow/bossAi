package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class UserCleanUpService {

    private final UserRepository userRepository;

    @Scheduled(cron = "0 0 2 * * ?")
    public void removeUnverifiedAccounts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<User> usersToDelete  = userRepository.findAll().stream()
                .filter(u -> !u.isEmailVerified() && u.getCreatedAt().isBefore(cutoff))
                .toList();
        if (!usersToDelete.isEmpty()) {
            userRepository.deleteAll(usersToDelete);
        }
    }

}
