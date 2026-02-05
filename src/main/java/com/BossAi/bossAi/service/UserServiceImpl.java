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
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailChangeTokenRepository emailChangeTokenRepository;
    private final UserPlanRepository userPlanRepository;
    private final PlanDefinitionRepository planDefinitionRepository;

    @Override
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email is already in use");
        }

        User user = new User();

        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
//        user.setPlan(PlanType.FREE);

        userRepository.save(user);

        String rawToken = UUID.randomUUID().toString();
        String hashedToken = passwordEncoder.encode(rawToken);
        VerificationToken verificationToken  = VerificationToken.builder()
                .token(hashedToken)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        verificationTokenRepository.save(verificationToken);
        mailService.sendVerificationEmail(user.getEmail(), rawToken);

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
    public void verifyAccount(String token) {

        VerificationToken verificationToken = verificationTokenRepository.findAll().stream()
                .filter(t -> passwordEncoder.matches(token, t.getToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        if (verificationToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("The token has expired");
        }

        User user = verificationToken.getUser();
        user.setEnabled(true);
        userRepository.save(user);

        assignFreePlan(user);
        verificationTokenRepository.delete(verificationToken);
    }

    @Override
    public void resendVerificationEmail(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEnabled()) {
            throw new RuntimeException("Account has been already verified");
        }

        Optional<VerificationToken> oldToken = verificationTokenRepository.findByUser(user);

        oldToken.ifPresent(verificationTokenRepository::delete);

        String rawToken = UUID.randomUUID().toString();
        String hashedToken = passwordEncoder.encode(rawToken);
        VerificationToken verificationToken = VerificationToken.builder()
                .token(hashedToken)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        verificationTokenRepository.save(verificationToken);
        mailService.sendVerificationEmail(email, rawToken);
    }

    @Override
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Optional<PasswordResetToken> oldToken = passwordResetTokenRepository.findByUser(user);
        oldToken.ifPresent(passwordResetTokenRepository::delete);

        String rawToken = UUID.randomUUID().toString();
        String hashedToken = passwordEncoder.encode(rawToken);
        PasswordResetToken passwordResetToken = PasswordResetToken.builder()
                .token(hashedToken)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        passwordResetTokenRepository.save(passwordResetToken);
        mailService.sendPasswordResetEmail(email, rawToken);
    }

    @Override
    public void resetPassword(String token, PasswordResetRequest request) {

        PasswordResetToken passwordResetToken = passwordResetTokenRepository.findAll().stream()
                .filter(t -> passwordEncoder.matches(token, t.getToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        User user = passwordResetToken.getUser();

        if (passwordResetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid token");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        passwordResetTokenRepository.delete(passwordResetToken);
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
        mailService.sendEmailChangeEmail(user.getEmail(), rawToken);
    }

    @Override
    public void requestEmailChangeConfirmation(String token) {
        EmailChangeToken emailChangeToken = emailChangeTokenRepository.findAll().stream()
                .filter(t -> passwordEncoder.matches(token, t.getToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid token"));
        mailService.sendEmailChangeConfirmation(emailChangeToken.getEmail(), token);
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
