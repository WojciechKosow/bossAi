package com.BossAi.bossAi.service;

import java.util.UUID;

public interface MailService {
    void sendVerificationEmail(String to, UUID tokenId, String token);
    void sendPasswordResetEmail(String to, UUID tokenId, String token);
    void sendEmailChangeEmail(String to, UUID tokenId, String token);
    void sendEmailChangeConfirmation(String to, UUID tokenId, String token);
}
