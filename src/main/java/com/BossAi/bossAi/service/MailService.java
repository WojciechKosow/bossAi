package com.BossAi.bossAi.service;

public interface MailService {
    void sendVerificationEmail(String to, String token);
    void sendPasswordResetEmail(String to, String token);
    void sendEmailChangeEmail(String to, String token);
    void sendEmailChangeConfirmation(String to, String token);
}
