package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.UserDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.*;
import com.BossAi.bossAi.request.EmailChangeRequest;
import com.BossAi.bossAi.request.LoginRequest;
import com.BossAi.bossAi.request.PasswordResetRequest;
import com.BossAi.bossAi.request.RegisterRequest;
import com.BossAi.bossAi.response.AuthResponse;
import com.BossAi.bossAi.security.JwtProvider;
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

    @Override
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email is already in use");
        }

        User user = new User();

        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));


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
                .createdAt(LocalDateTime.now())
                .build();


        userTokenRepository.save(token);

        mailService.sendVerificationEmail(user.getEmail(), tokenId, rawToken);

        return new AuthResponse(null, mapToDTO(user));
    }

    @Override
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("invalid password or email"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("invalid password or email");
        }

        if (!user.isEnabled()) {
            throw new RuntimeException("Your account hasn't been activated yet");
        }

        String token = jwtProvider.generateToken(user.getEmail());
        return new AuthResponse(token, mapToDTO(user));
    }

    @Override
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

        userRepository.save(user);
        userTokenRepository.save(token);
    }

    @Override
    public void resendVerificationEmail(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEnabled()) {
            throw new RuntimeException("Account has been already verified");
        }

        Optional<UserToken> oldToken = userTokenRepository.findByUser(user);

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
                .createdAt(LocalDateTime.now())
                .build();


        userTokenRepository.save(token);
        mailService.sendVerificationEmail(email, tokenId, rawToken);
    }

    @Override
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));


        Optional<UserToken> oldToken = userTokenRepository.findByUser(user);
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
                .createdAt(LocalDateTime.now())
                .build();

        userTokenRepository.save(token);
        mailService.sendPasswordResetEmail(email, tokenId, rawToken);
    }

    @Override
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

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        token.setUsed(true);
        userTokenRepository.save(token);
    }

    @Override
    public void requestEmailChange(EmailChangeRequest request) {

        if (userRepository.existsByEmail(request.getNewEmail())) {
            throw new RuntimeException("Email is already in use");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid Password");
        }

        Optional<EmailChangeToken> oldEmailChangeToken = emailChangeTokenRepository.findByUser(user);
        oldEmailChangeToken.ifPresent(emailChangeTokenRepository::delete);

        String rawToken = UUID.randomUUID().toString();
        String hashedToken = passwordEncoder.encode(rawToken);
        EmailChangeToken emailChangeToken = EmailChangeToken.builder()
                .token(hashedToken)
                .user(user)
                .email(request.getNewEmail())
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        emailChangeTokenRepository.save(emailChangeToken);
//        mailService.sendEmailChangeEmail(user.getEmail(), rawToken);
    }

    @Override
    public void requestEmailChangeConfirmation(String token) {
        EmailChangeToken emailChangeToken = emailChangeTokenRepository.findAll().stream()
                .filter(t -> passwordEncoder.matches(token, t.getToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid token"));
//        mailService.sendEmailChangeConfirmation(emailChangeToken.getEmail(), token);
    }

    @Override
    public void confirmEmailChange(String token, String password) {
        EmailChangeToken emailChangeToken = emailChangeTokenRepository.findAll().stream()
                .filter(t -> passwordEncoder.matches(token, t.getToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        User user = emailChangeToken.getUser();

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Invalid password");
        }

        if (emailChangeToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid token");
        }

        user.setEmail(emailChangeToken.getEmail());
        userRepository.save(user);
        emailChangeTokenRepository.delete(emailChangeToken);
    }

    @Transactional
    private void assignFreePlan(User user) {
        if (userPlanRepository.existsByUserAndPlanType(user, PlanType.FREE)) {
            return;
        }

        PlanDefinition planDefinition = planDefinitionRepository.findById(PlanType.FREE)
                .orElseThrow();

        UserPlan userPlan = new UserPlan();
        userPlan.setUser(user);
        userPlan.setPlanType(PlanType.FREE);
        userPlan.setImagesTotal(planDefinition.getImagesLimit());
        userPlan.setVideosTotal(planDefinition.getVideosLimit());
        userPlan.setImagesUsed(0);
        userPlan.setVideosUsed(0);
        userPlan.setActivatedAt(LocalDateTime.now());
        userPlan.setExpiresAt(LocalDateTime.now().plusDays(planDefinition.getDurationDays()));
        userPlan.setActive(true);

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
