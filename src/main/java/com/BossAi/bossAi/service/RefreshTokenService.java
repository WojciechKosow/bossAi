package com.BossAi.bossAi.service;

import com.BossAi.bossAi.entity.RefreshToken;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.RefreshTokenRepository;
import com.BossAi.bossAi.security.RequestContextUtil;
import com.BossAi.bossAi.security.SecurityEventService;
import com.BossAi.bossAi.security.SecurityEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RequestContextUtil requestContextUtil;
    private final SecurityEventService securityEventService;

    @Transactional
    public String createRefreshToken(User user, boolean rememberMe) {

        UUID tokenId = UUID.randomUUID();
        String secret = UUID.randomUUID().toString();

        String rawToken = tokenId + "." + secret;

        RefreshToken token = RefreshToken.builder()
                .id(tokenId)
                .user(user)
                .tokenHash(passwordEncoder.encode(secret))
                .expiresAt(LocalDateTime.now().plusDays(rememberMe ? 30 : 1))
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .createdByIp(requestContextUtil.getClientIp())
                .userAgent(requestContextUtil.getUserAgent())
                .build();

        refreshTokenRepository.save(token);

        return rawToken;
    }

    @Transactional
    public RefreshToken validateRefreshToken(String rawToken) {

        if (rawToken == null || !rawToken.contains(".")) {
            throw new RuntimeException("Invalid refresh token format");
        }

        String[] parts = rawToken.split("\\.");
        if (parts.length != 2) {
            throw new RuntimeException("Invalid refresh token format");
        }

        UUID tokenId;

        try {
            tokenId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid refresh token");
        }


        String secret = parts[1];

        RefreshToken token = refreshTokenRepository.findById(tokenId)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        if (token.isRevoked()) {
            throw new RuntimeException("Refresh token revoked");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Refresh token expired");
        }

        if (!passwordEncoder.matches(secret, token.getTokenHash())) {
            throw new RuntimeException("Invalid refresh token");
        }

        return token;
    }

    @Transactional
    public RefreshTokenRotationResult rotateToken(RefreshToken oldToken) {

        int updated = refreshTokenRepository.revokeIfNotRevoked(oldToken.getId());

        if (updated == 0) {

            revokeAllUserTokens(oldToken.getUser());

            securityEventService.log(
                    SecurityEventType.REFRESH_TOKEN_REUSE,
                    oldToken.getUser().getEmail(),
                    requestContextUtil.getClientIp(),
                    requestContextUtil.getUserAgent()
            );

            throw new RuntimeException("Refresh token reuse detected. All sessions revoked.");
        }

        UUID newTokenId = UUID.randomUUID();
        String newSecret = UUID.randomUUID().toString();
        String rawToken = newTokenId + "." + newSecret;

        RefreshToken newToken = RefreshToken.builder()
                .id(newTokenId)
                .user(oldToken.getUser())
                .tokenHash(passwordEncoder.encode(newSecret))
                .expiresAt(oldToken.getExpiresAt())
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .createdByIp(requestContextUtil.getClientIp())
                .userAgent(requestContextUtil.getUserAgent())
                .build();

        refreshTokenRepository.save(newToken);

        return new RefreshTokenRotationResult(rawToken, newToken.getExpiresAt());
    }



    @Transactional
    public void revokeToken(String rawToken) {

        RefreshToken token = validateRefreshToken(rawToken);
        token.setRevoked(true);
        refreshTokenRepository.save(token);
    }

    @Transactional
    public void revokeAllUserTokens(User user) {

        List<RefreshToken> tokens =
                refreshTokenRepository.findByUserAndRevokedFalse(user);

        for (RefreshToken token : tokens) {
            token.setRevoked(true);
        }

        refreshTokenRepository.saveAll(tokens);
    }
}
