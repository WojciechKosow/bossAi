package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.entity.RefreshToken;
import com.BossAi.bossAi.request.*;
import com.BossAi.bossAi.response.AuthResponse;
import com.BossAi.bossAi.security.JwtProvider;
import com.BossAi.bossAi.service.RefreshTokenService;
import com.BossAi.bossAi.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return ResponseEntity.ok("\"If registration is possible, you will receive further instructions.\"\n");
    }

//    @PostMapping("/login")
//    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
//        AuthResponse res = userService.login(request);
//        return ResponseEntity.ok(res);
//    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {

        AuthResponse res = userService.login(request);

        ResponseCookie cookie = ResponseCookie.from("refreshToken", res.getRefreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofDays(request.isRememberMe() ? 30 : 1))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(res.getToken(), null, res.getUser()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue("refreshToken") String rawRefreshToken) {

        refreshTokenService.revokeToken(rawRefreshToken);

        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyAccount(@RequestParam UUID tokenId, @RequestParam String token) {
        userService.verifyAccount(tokenId, token);
        return ResponseEntity.ok("Your account has been activated.");
    }

    @PostMapping("resend-verification-email")
    public ResponseEntity<String> resendVerificationEmail(@Valid @RequestBody EmailVerificationRequest request) {
        userService.resendVerificationEmail(request.getEmail());
        return ResponseEntity.ok("Verification code has been resend to your email");
    }

    @PostMapping("forgot-password")
    public ResponseEntity<String> forgotPassword(@Valid @RequestBody EmailVerificationRequest request) {
        userService.forgotPassword(request.getEmail());
        return ResponseEntity.ok("Password reset link has been sent to your account");
    }

    @PostMapping("reset-password")
    public ResponseEntity<String> resetPassword(@RequestParam UUID tokenId, @RequestParam String token, @Valid @RequestBody PasswordResetRequest request) {
        userService.resetPassword(tokenId, token, request);
        return ResponseEntity.ok("Successfully changed password");
    }

    @PostMapping("request-email-change")
    public ResponseEntity<String> requestEmailChange(@Valid @RequestBody EmailChangeRequest request) {
        userService.requestEmailChange(request);
        return ResponseEntity.ok("Email change message has been sent to to your email");
    }

    @PostMapping("change-email")
    public ResponseEntity<String> confirmEmailChangeRequest(@RequestParam UUID tokenId, @RequestParam String token) {
        userService.requestEmailChangeConfirmation(tokenId, token);
        return ResponseEntity.ok("A confirmation link has been sent to your new email");
    }

    @PostMapping("/change-email-confirmation")
    public ResponseEntity<String> confirmEmailChange(@RequestParam UUID tokenId, @RequestParam String token, @Valid @RequestBody EmailChangeConfirmationRequest request) {
        userService.confirmEmailChange(tokenId, token, request.getPassword());
        return ResponseEntity.ok("Successfully changed your email");
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue("refreshToken") String rawRefreshToken) {

        RefreshToken token = refreshTokenService.validateRefreshToken(rawRefreshToken);

        String newAccessToken = jwtProvider.generateToken(token.getUser().getEmail());
        String newRefreshToken = refreshTokenService.rotateToken(token);

        ResponseCookie cookie = ResponseCookie.from("refreshToken", newRefreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.between(LocalDateTime.now(), token.getExpiresAt()))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(newAccessToken, null, null));
    }
}
