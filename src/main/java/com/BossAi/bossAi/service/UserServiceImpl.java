package com.BossAi.bossAi.service;

import com.BossAi.bossAi.dto.UserDTO;
import com.BossAi.bossAi.entity.Plan;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.entity.VerificationToken;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.repository.VerificationTokenRepository;
import com.BossAi.bossAi.request.LoginRequest;
import com.BossAi.bossAi.request.RegisterRequest;
import com.BossAi.bossAi.response.AuthResponse;
import com.BossAi.bossAi.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final MailService mailService;
    private final VerificationTokenRepository verificationTokenRepository;

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
