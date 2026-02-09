package com.BossAi.bossAi.controller;

import com.BossAi.bossAi.request.*;
import com.BossAi.bossAi.response.AuthResponse;
import com.BossAi.bossAi.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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
    public ResponseEntity<String> verifyAccount(@RequestParam UUID tokenId, @RequestParam String token) {
        userService.verifyAccount(tokenId, token);
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
    public ResponseEntity<String> resetPassword(@RequestParam UUID tokenId, @RequestParam String token, @RequestBody PasswordResetRequest request) {
        userService.resetPassword(tokenId, token, request);
        return ResponseEntity.ok("Successfully changed password");
    }

    @PostMapping("request-email-change")
    public ResponseEntity<String> requestEmailChange(@RequestBody EmailChangeRequest request) {
        userService.requestEmailChange(request);
        return ResponseEntity.ok("Email change message has been sent to to your email");
    }

    @PostMapping("change-email")
    public ResponseEntity<String> confirmEmailChangeRequest(@RequestParam String token) {
        userService.requestEmailChangeConfirmation(token);
        return ResponseEntity.ok("A confirmation link has been sent to your new email");
    }

    @PostMapping("/change-email-confirmation")
    public ResponseEntity<String> confirmEmailChange(@RequestParam String token, @RequestBody EmailChangeConfirmationRequest request) {
        userService.confirmEmailChange(token, request.getPassword());
        return ResponseEntity.ok("Successfully changed your email");
    }
}
