package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.request.EmailVerificationRequest;
import com.BossAi.bossAi.request.LoginRequest;
import com.BossAi.bossAi.request.PasswordResetRequest;
import com.BossAi.bossAi.request.RegisterRequest;
import com.BossAi.bossAi.response.AuthResponse;
import com.BossAi.bossAi.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        AuthResponse res = userService.register(request);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        AuthResponse res = userService.login(request);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyAccount(@RequestParam String token) {
        userService.verifyAccount(token);
        return ResponseEntity.ok("Your account has been activated.");
    }

    @PostMapping("resend-verification-email")
    public ResponseEntity<String> resendVerificationEmail(@RequestBody EmailVerificationRequest request) {
        userService.resendVerificationEmail(request.getEmail());
        return ResponseEntity.ok("Verification code has been resend to your email");
    }

    @PostMapping("forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody EmailVerificationRequest request) {
        userService.forgotPassword(request.getEmail());
        return ResponseEntity.ok("Password reset link has been sent to your account");
    }

    @PostMapping("reset-password")
    public ResponseEntity<String> resetPassword(@RequestParam String token, @RequestBody PasswordResetRequest request) {
        userService.resetPassword(token, request);
        return ResponseEntity.ok("");
    }
}
