package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.UserDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.exceptions.EmailAlreadyExistsException;
import com.BossAi.bossAi.repository.*;
import com.BossAi.bossAi.request.EmailChangeRequest;
import com.BossAi.bossAi.request.LoginRequest;
import com.BossAi.bossAi.request.PasswordResetRequest;
import com.BossAi.bossAi.request.RegisterRequest;
import com.BossAi.bossAi.response.AuthResponse;
import com.BossAi.bossAi.security.JwtProvider;
import com.BossAi.bossAi.security.RequestContextUtil;
import com.BossAi.bossAi.security.SecurityEventService;
import com.BossAi.bossAi.security.SecurityEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final MailService mailService;
    private final EmailChangeTokenRepository emailChangeTokenRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanDefinitionRepository planDefinitionRepository;
    private final UserTokenRepository userTokenRepository;
    private final SecurityEventService securityEventService;
    private final RequestContextUtil requestContextUtil;
    private final RefreshTokenService refreshTokenService;
    private final UserWalletRepository userWalletRepository;
    private final AssignPlanService assignPlanService;

    @Override
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException("Email is already in use");
        }

        User user = new User();

        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail().toLowerCase());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setProvider(AuthProvider.LOCAL);


        userRepository.save(user);


        UUID tokenId = UUID.randomUUID();
        String rawToken = UUID.randomUUID().toString();

        UserToken token = UserToken.builder()
                .id(tokenId)
                .user(user)
                .type(TokenType.EMAIL_VERIFICATION)
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .used(false)
                .payload(null)
                .createdAt(LocalDateTime.now())
                .build();


        userTokenRepository.save(token);

        mailService.sendVerificationEmail(user.getEmail(), tokenId, rawToken);

        return new AuthResponse(null, null,  mapToDTO(user));
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> new RuntimeException("Invalid password or email"));

        if (user.getProvider() == AuthProvider.GOOGLE) {
            throw new RuntimeException("Use Google login");
        }

        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {

            securityEventService.log(
                    SecurityEventType.ACCOUNT_LOCKED,
                    user.getEmail().toLowerCase(),
                    requestContextUtil.getClientIp(),
                    requestContextUtil.getUserAgent()
            );

            throw new RuntimeException("Account is temporarily locked. try again later.");
        }

        if (user.getCredentialsUpdatedAt() == null) {
            user.setCredentialsUpdatedAt(LocalDateTime.now());
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {

            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);

            if (user.getFailedLoginAttempts() >= 5) {

                user.setLockUntil(LocalDateTime.now().plusMinutes(5));
                user.setFailedLoginAttempts(0);

                securityEventService.log(
                        SecurityEventType.ACCOUNT_LOCKED,
                        user.getEmail().toLowerCase(),
                        requestContextUtil.getClientIp(),
                        requestContextUtil.getUserAgent()
                );
            }


            securityEventService.log(
                    SecurityEventType.FAILED_LOGIN,
                    user.getEmail().toLowerCase(),
                    requestContextUtil.getClientIp(),
                    requestContextUtil.getUserAgent()
            );

            userRepository.save(user);
            throw new RuntimeException("Invalid password or email");
        }

        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        userRepository.save(user);

        if (!user.isEnabled()) {
            throw new RuntimeException("Invalid password or email");
        }

        String accessToken = jwtProvider.generateToken(user.getEmail());
        String refreshToken = refreshTokenService.createRefreshToken(user, request.isRememberMe());
        return new AuthResponse(accessToken, refreshToken, mapToDTO(user));
    }

    @Override
    @Transactional
    public void verifyAccount(UUID tokenId, String rawToken) {

        UserToken token = userTokenRepository.findById(tokenId)
                .orElseThrow(() -> new RuntimeException("Invalid token"));


        if (token.isUsed()) {
            throw new RuntimeException("Token already used");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token expired");
        }

        if (!passwordEncoder.matches(rawToken, token.getTokenHash())) {
            throw new RuntimeException("Invalid token");
        }

        if (!token.getType().equals(TokenType.EMAIL_VERIFICATION)) {
            throw new RuntimeException("Invalid token");
        }

        User user = token.getUser();
        user.setEnabled(true);

        token.setUsed(true);

        assignPlanService.assignCreatorPlan(user);

        userRepository.save(user);
        userTokenRepository.save(token);
    }

    @Override
    public void resendVerificationEmail(String email) {

        Optional<User> userOptional = userRepository.findByEmail(email);

        if (userOptional.isEmpty()) {
            return;
        }

        User user = userOptional.get();

//        User user = userRepository.findByEmail(email)
//                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEnabled()) {
            return;
        }

        Optional<UserToken> oldToken = userTokenRepository.findByUserAndTypeAndUsedFalse(user, TokenType.EMAIL_VERIFICATION);

        oldToken.ifPresent(userTokenRepository::delete);

        UUID tokenId = UUID.randomUUID();
        String rawToken = UUID.randomUUID().toString();

        UserToken token = UserToken.builder()
                .id(tokenId)
                .user(user)
                .type(TokenType.EMAIL_VERIFICATION)
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .used(false)
                .payload(null)
                .createdAt(LocalDateTime.now())
                .build();


        userTokenRepository.save(token);
        mailService.sendVerificationEmail(email, tokenId, rawToken);
    }

    @Override
    public void forgotPassword(String email) {

        Optional<User> userOptional = userRepository.findByEmail(email);

        if (userOptional.isEmpty()) {
            return;
        }

        User user = userOptional.get();

//        User user = userRepository.findByEmail(email)
//                .orElseThrow(() -> new RuntimeException("User not found"));


        Optional<UserToken> oldToken = userTokenRepository.findByUserAndTypeAndUsedFalse(user, TokenType.PASSWORD_RESET);
        oldToken.ifPresent(userTokenRepository::delete);

        String rawToken = UUID.randomUUID().toString();
        UUID tokenId = UUID.randomUUID();

        UserToken token = UserToken.builder()
                .id(tokenId)
                .user(user)
                .type(TokenType.PASSWORD_RESET)
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .used(false)
                .payload(null)
                .createdAt(LocalDateTime.now())
                .build();

        userTokenRepository.save(token);
        mailService.sendPasswordResetEmail(email, tokenId, rawToken);

        securityEventService.log(
                SecurityEventType.PASSWORD_RESET_REQUEST,
                user.getEmail(),
                requestContextUtil.getClientIp(),
                requestContextUtil.getUserAgent()
        );
    }

    @Override
    @Transactional
    public void resetPassword(UUID tokenId, String rawToken, PasswordResetRequest request) {

        UserToken token = userTokenRepository.findById(tokenId)
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        if (token.isUsed()) {
            throw new RuntimeException("Token already used");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid token");
        }

        if (!passwordEncoder.matches(rawToken, token.getTokenHash())) {
            throw new RuntimeException("Invalid token");
        }

        if (!token.getType().equals(TokenType.PASSWORD_RESET)) {
            throw new RuntimeException("Invalid token");
        }

        User user = token.getUser();

        user.setCredentialsUpdatedAt(LocalDateTime.now());
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);


        refreshTokenService.revokeAllUserTokens(user);

        token.setUsed(true);
        userTokenRepository.save(token);

        securityEventService.log(
                SecurityEventType.PASSWORD_CHANGED,
                user.getEmail(),
                requestContextUtil.getClientIp(),
                requestContextUtil.getUserAgent()
        );
    }

    @Override
    public void requestEmailChange(EmailChangeRequest request) {

        if (userRepository.existsByEmail(request.getNewEmail())) {
            throw new EmailAlreadyExistsException("Email is already in use");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        Optional<User> userOptional = userRepository.findByEmail(email);

        if (userOptional.isEmpty()) {
            return;
        }

        User user = userOptional.get();

//        User user = userRepository.findByEmail(email)
//                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid Password");
        }

        Optional<UserToken> oldToken = userTokenRepository.findByUserAndTypeAndUsedFalse(user, TokenType.EMAIL_CHANGE_REQUEST);
        oldToken.ifPresent(userTokenRepository::delete);

        String rawToken = UUID.randomUUID().toString();
        UUID tokenId = UUID.randomUUID();


        UserToken token = UserToken.builder()
                .id(tokenId)
                .user(user)
                .type(TokenType.EMAIL_CHANGE_REQUEST)
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .used(false)
                .payload(request.getNewEmail())
                .createdAt(LocalDateTime.now())
                .build();

        userTokenRepository.save(token);
        mailService.sendEmailChangeEmail(user.getEmail(), tokenId, rawToken);

        securityEventService.log(
                SecurityEventType.EMAIL_CHANGE_REQUEST,
                user.getEmail(),
                requestContextUtil.getClientIp(),
                requestContextUtil.getUserAgent()
        );
    }

    @Override
    public void requestEmailChangeConfirmation(UUID tokenId, String rawToken) {

        UserToken token = userTokenRepository.findById(tokenId)
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        if (token.isUsed()) {
            throw new RuntimeException("Token already used");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid token");
        }

        if (!passwordEncoder.matches(rawToken, token.getTokenHash())) {
            throw new RuntimeException("Invalid token");
        }

        if (!token.getType().equals(TokenType.EMAIL_CHANGE_REQUEST)) {
            throw new RuntimeException("Invalid token");
        }

        if (userRepository.existsByEmail(token.getPayload())) {
            throw new EmailAlreadyExistsException("Email is already in use");
        }

        User user = token.getUser();

        String rawConfirmationToken = UUID.randomUUID().toString();
        UUID confirmationTokenId = UUID.randomUUID();

        UserToken confirmationToken = UserToken.builder()
                .id(confirmationTokenId)
                .user(user)
                .type(TokenType.EMAIL_CHANGE_CONFIRMATION)
                .tokenHash(passwordEncoder.encode(rawConfirmationToken))
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .used(false)
                .payload(token.getPayload())
                .createdAt(LocalDateTime.now())
                .build();

        userTokenRepository.save(confirmationToken);

        mailService.sendEmailChangeConfirmation(token.getPayload(), confirmationTokenId, rawConfirmationToken);
        token.setUsed(true);
        userTokenRepository.save(token);
    }

    @Override
    @Transactional
    public void confirmEmailChange(UUID tokenId, String rawToken, String password) {

        UserToken token = userTokenRepository.findById(tokenId)
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        if (token.isUsed()) {
            throw new RuntimeException("Token already used");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid token");
        }

        if (!passwordEncoder.matches(rawToken, token.getTokenHash())) {
            throw new RuntimeException("Invalid token");
        }

        if (!token.getType().equals(TokenType.EMAIL_CHANGE_CONFIRMATION)) {
            throw new RuntimeException("Invalid token");
        }

        if (token.getPayload() == null) {
            throw new RuntimeException("Invalid token");
        }

        if (userRepository.existsByEmail(token.getPayload())) {
            throw new EmailAlreadyExistsException("Email is already in use");
        }

        User user = token.getUser();

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Invalid password");
        }

        user.setEmail(token.getPayload());
        user.setCredentialsUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        token.setUsed(true);
        userTokenRepository.save(token);

        refreshTokenService.revokeAllUserTokens(user);

        securityEventService.log(
                SecurityEventType.EMAIL_CHANGED,
                user.getEmail(),
                requestContextUtil.getClientIp(),
                requestContextUtil.getUserAgent()
        );
    }

    @Transactional
    private void assignFreePlan(User user) {
        if (userPlanRepository.existsByUserAndPlanType(user, PlanType.FREE)) {
            return;
        }

        UserWallet userWallet = new UserWallet();

        PlanDefinition planDefinition = planDefinitionRepository.findById(PlanType.FREE)
                .orElseThrow();


        UserPlan userPlan = new UserPlan();
        userPlan.setUser(user);
        userPlan.setPlanType(PlanType.FREE);
        userPlan.setCreditsTotal(planDefinition.getMonthlyCreditsTotal());
        userPlan.setActivatedAt(LocalDateTime.now());
        userPlan.setExpiresAt(LocalDateTime.now().plusDays(planDefinition.getDurationDays()));
        userPlan.setActive(true);

        userWallet.setUserId(user.getId());
        userWallet.setCreditsBalance(0);

        userWalletRepository.save(userWallet);
        userPlanRepository.save(userPlan);
    }

    private UserDTO mapToDTO(User user) {
        return new UserDTO(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.isEnabled()
        );
    }
}
