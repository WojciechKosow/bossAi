package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.AuthProvider;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.exceptions.EmailAlreadyExistsException;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.AssignPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Beta-only endpoint for manual user creation.
 * Requires X-Admin-Key header matching app.admin-key in application.properties.
 * Creates user as enabled (no email verification needed).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AssignPlanService assignPlanService;

    @Value("${app.admin-key:}")
    private String adminKey;

    @PostMapping("/users")
    @Transactional
    public ResponseEntity<?> createBetaUser(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey,
            @RequestBody CreateBetaUserRequest request
    ) {
        if (adminKey.isBlank() || !adminKey.equals(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing X-Admin-Key header"));
        }

        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }
        if (request.password() == null || request.password().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "password must be at least 8 characters"));
        }

        String email = request.email().toLowerCase().trim();

        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Email already in use: " + email));
        }

        User user = new User();
        user.setEmail(email);
        user.setDisplayName(request.displayName() != null ? request.displayName() : email);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setProvider(AuthProvider.LOCAL);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        userRepository.save(user);

        assignPlanService.assignFreePlan(user);
        assignPlanService.assignPlan(user, com.BossAi.bossAi.entity.PlanType.PRO, null);

        log.info("[AdminUserController] Beta user created: {}", email);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "email", email,
                "displayName", user.getDisplayName(),
                "plan", "PRO",
                "message", "User created and activated. Share credentials directly."
        ));
    }

    public record CreateBetaUserRequest(String email, String password, String displayName) {}
}
