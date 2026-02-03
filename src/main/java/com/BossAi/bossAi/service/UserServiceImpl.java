package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.UserDTO;
import com.BossAi.bossAi.entity.*;
import com.BossAi.bossAi.repository.EmailChangeTokenRepository;
import com.BossAi.bossAi.repository.PasswordResetTokenRepository;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.repository.VerificationTokenRepository;
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

    @Override
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email is already in use");
        }

        User user = new User();

        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPlan(Plan.FREE);

        userRepository.save(user);

        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken  = VerificationToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        verificationTokenRepository.save(verificationToken);
        mailService.sendVerificationEmail(user.getEmail(), token);

        return new AuthResponse(null, mapToDTO(user));
    }

    @Override
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("invalid password or email"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("invalid password or email");
        }

        if (!user.isEmailVerified()) {
            throw new RuntimeException("Your account hasn't been activated yet");
        }

        String token = jwtProvider.generateToken(user.getEmail());
        return new AuthResponse(token, mapToDTO(user));
    }

    @Override
    public void verifyAccount(String token) {
        VerificationToken verificationToken = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        if (verificationToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("The token has expired");
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);
        verificationTokenRepository.delete(verificationToken);
    }

    @Override
    public void resendVerificationEmail(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEmailVerified()) {
            throw new RuntimeException("Account has been already verified");
        }

        Optional<VerificationToken> oldToken = verificationTokenRepository.findByUser(user);

        oldToken.ifPresent(verificationTokenRepository::delete);

        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken = VerificationToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        verificationTokenRepository.save(verificationToken);
        mailService.sendVerificationEmail(email, token);
    }

    @Override
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Optional<PasswordResetToken> oldToken = passwordResetTokenRepository.findByUser(user);
        oldToken.ifPresent(passwordResetTokenRepository::delete);

        String token = UUID.randomUUID().toString();
        PasswordResetToken passwordResetToken = PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        passwordResetTokenRepository.save(passwordResetToken);
        mailService.sendPasswordResetEmail(email, token);
    }

    @Override
    public void resetPassword(String token, PasswordResetRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        PasswordResetToken passwordResetToken = passwordResetTokenRepository
                .findByToken(token).orElseThrow(() -> new RuntimeException("Invalid token"));

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

        String token = UUID.randomUUID().toString();
        EmailChangeToken emailChangeToken = EmailChangeToken.builder()
                .token(token)
                .user(user)
                .email(request.getNewEmail())
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        emailChangeTokenRepository.save(emailChangeToken);
        mailService.sendEmailChangeEmail(user.getEmail(), token);
    }

    @Override
    public void requestEmailChangeConfirmation(String token) {
        EmailChangeToken emailChangeToken = emailChangeTokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid token"));
        mailService.sendEmailChangeConfirmation(emailChangeToken.getEmail(), emailChangeToken.getToken());
    }

    @Override
    public void confirmEmailChange(String token, String password) {
        EmailChangeToken emailChangeToken = emailChangeTokenRepository.findByToken(token)
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

    private UserDTO mapToDTO(User user) {
        return new UserDTO(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.isEnabled()
        );
    }
}
