package com.BossAi.bossAi.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String mailFrom;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.backend-url}")
    private String backendUrl;

    private void send(String to, String subject, String content) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(mailFrom, "ToucanAI");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
        } catch (Exception e) {
            // Swallowed by GlobalExceptionHandler into a generic 400 with no
            // detail, so the real Postmark/SMTP rejection reason (bad token,
            // unverified sender, trial-mode recipient restriction, etc.) is
            // otherwise invisible — log it here so it shows up in Railway logs.
            log.error("Failed to send email to {} (from={}): {}", to, mailFrom, e.getMessage(), e);
            throw new RuntimeException("error: cannot send an email", e);
        }
    }

    @Override
    public void sendVerificationEmail(String to, UUID tokenId, String token) {
        String subject = "Verify your email to activate your account";
        String confirmationUrl = frontendUrl + "/verify?tokenId=" + tokenId + "&token=" + token;
        String content = """
                 <div style="font-family: Arial, sans-serif; background-color: #f5f6fa; padding: 40px;">
                            <table align="center" width="600" style="background: #ffffff; border-radius: 8px; padding: 40px;">
                                <tr>
                                    <td style="text-align: center;">
                                        <img src="https://dummyimage.com/120x40/000/fff&text=ToucanAI"\s
                                             alt="ToucanAI" style="margin-bottom: 20px;">
                                    </td>
                                </tr>
                
                                <tr>
                                    <td style="font-size: 18px; color: #333333; text-align: center; padding-bottom: 10px;">
                                        Hi!
                                    </td>
                                </tr>
                
                                <tr>
                                    <td style="font-size: 15px; color: #555555; text-align: center;">
                                        Thanks for signing up for <strong>ToucanAI</strong>.<br>
                                        Before you get started, we just need to confirm your email address.
                                    </td>
                                </tr>
                
                                <tr>
                                    <td style="text-align: center; padding: 30px 0;">
                                        <a href="%s"\s
                                           style="background-color: #4CAF50; color: white; padding: 14px 28px;\s
                                                  text-decoration: none; border-radius: 6px; font-size: 16px;">
                                            Verify Email
                                        </a>
                                    </td>
                                </tr>
                
                                <tr>
                                    <td style="font-size: 13px; color: #777777; text-align: center; padding-top: 20px;">
                                        If the button doesn't work, copy and paste this link into your browser:<br>
                                        <a href="%s" style="color: #4CAF50;">%s</a>
                                    </td>
                                </tr>
                
                                <tr>
                                    <td style="font-size: 12px; color: #aaaaaa; text-align: center; padding-top: 40px;">
                                        © 2026 ToucanAI — All rights reserved.
                                    </td>
                                </tr>
                            </table>
                        </div>
                """.formatted(confirmationUrl, confirmationUrl, confirmationUrl);
        send(to, subject, content);
    }

    @Override
    public void sendPasswordResetEmail(String to, UUID tokenId, String token) {
        String subject = "Reset your password";
        String passwordResetUrl = frontendUrl + "/reset-password?tokenId=" + tokenId + "&token=" + token;
        String content = """
                <div style="font-family: Arial, sans-serif; background-color: #f5f6fa; padding: 40px;">
                    <table align="center" width="600" style="background: #ffffff; border-radius: 8px; padding: 40px;">
                        <tr>
                            <td style="text-align: center;">
                                <img src="https://dummyimage.com/120x40/000/fff&text=ToucanAI"
                                     alt="ToucanAI" style="margin-bottom: 20px;">
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 18px; color: #333333; text-align: center; padding-bottom: 10px;">
                                Reset your password
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 15px; color: #555555; text-align: center;">
                                We received a request to reset the password for your <strong>ToucanAI</strong> account.<br>
                                If you made this request, click the button below to set a new password.
                            </td>
                        </tr>
                
                        <tr>
                            <td style="text-align: center; padding: 30px 0;">
                                <a href="%s"
                                   style="background-color: #4CAF50; color: white; padding: 14px 28px;
                                          text-decoration: none; border-radius: 6px; font-size: 16px;">
                                    Reset Password
                                </a>
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 13px; color: #777777; text-align: center; padding-top: 20px;">
                                If the button doesn't work, copy and paste this link into your browser:<br>
                                <a href="%s" style="color: #4CAF50;">%s</a>
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 12px; color: #aaaaaa; text-align: center; padding-top: 40px;">
                                If you didn't request a password reset, you can safely ignore this email.<br>
                                © 2026 ToucanAI — All rights reserved.
                            </td>
                        </tr>
                    </table>
                </div>
                """.formatted(passwordResetUrl, passwordResetUrl, passwordResetUrl);
        send(to, subject, content);
    }

    @Override
    public void sendEmailChangeEmail(String to, UUID tokenId, String token) {
        String subject = "Change your email";
        String emailChangeUrl = backendUrl + "/api/auth/change-email?tokenId=" + tokenId + "&token=" + token;
        String content = """
                    <div style="font-family: Arial, sans-serif; background-color: #f5f6fa; padding: 40px;">
                        <table align="center" width="600" style="background: #ffffff; border-radius: 8px; padding: 40px;">
                            <tr>
                                <td style="text-align: center;">
                                    <img src="https://dummyimage.com/120x40/000/fff&text=ToucanAI"
                                         alt="ToucanAI" style="margin-bottom: 20px;">
                                </td>
                            </tr>
                
                            <tr>
                                <td style="font-size: 18px; color: #333333; text-align: center; padding-bottom: 10px;">
                                    Confirm your email change
                                </td>
                            </tr>
                
                            <tr>
                                <td style="font-size: 15px; color: #555555; text-align: center;">
                                    You requested to change the email address associated with your <strong>ToucanAI</strong> account.<br>
                                    To complete this process, please confirm the change by clicking the button below.
                                </td>
                            </tr>
                
                            <tr>
                                <td style="text-align: center; padding: 30px 0;">
                                    <a href="%s"
                                       style="background-color: #4CAF50; color: white; padding: 14px 28px;
                                              text-decoration: none; border-radius: 6px; font-size: 16px;">
                                        Confirm Email Change
                                    </a>
                                </td>
                            </tr>
                
                            <tr>
                                <td style="font-size: 13px; color: #777777; text-align: center; padding-top: 20px;">
                                    If the button doesn't work, copy and paste this link into your browser:<br>
                                    <a href="%s" style="color: #4CAF50;">%s</a>
                                </td>
                            </tr>
                
                            <tr>
                                <td style="font-size: 12px; color: #aaaaaa; text-align: center; padding-top: 40px;">
                                    If you didn't request this change, you can safely ignore this email.<br>
                                    © 2024 ToucanAI — All rights reserved.
                                </td>
                            </tr>
                        </table>
                    </div>
                """.formatted(emailChangeUrl, emailChangeUrl, emailChangeUrl);
        send(to, subject, content);
    }

    @Override
    public void sendEmailChangeConfirmation(String to, UUID tokenId, String token) {
        String subject = "Confirm your new email";
        String emailChangeConfirmationUrl = backendUrl + "/api/auth/change-email-confirmation?tokenId=" + tokenId + "&token=" + token;
        String content = """
                <div style="font-family: Arial, sans-serif; background-color: #f5f6fa; padding: 40px;">
                    <table align="center" width="600" style="background: #ffffff; border-radius: 8px; padding: 40px;">
                        <tr>
                            <td style="text-align: center;">
                                <img src="https://dummyimage.com/120x40/000/fff&text=ToucanAI"
                                     alt="ToucanAI" style="margin-bottom: 20px;">
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 18px; color: #333333; text-align: center; padding-bottom: 10px;">
                                Confirm your new email address
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 15px; color: #555555; text-align: center;">
                                You recently requested to update the email address associated with your <strong>ToucanAI</strong> account.<br>
                                To finish the process, please confirm that this is your new email address.
                            </td>
                        </tr>
                
                        <tr>
                            <td style="text-align: center; padding: 30px 0;">
                                <a href="%s"
                                   style="background-color: #4CAF50; color: white; padding: 14px 28px;
                                          text-decoration: none; border-radius: 6px; font-size: 16px;">
                                    Confirm New Email
                                </a>
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 13px; color: #777777; text-align: center; padding-top: 20px;">
                                If the button doesn't work, copy and paste this link into your browser:<br>
                                <a href="%s" style="color: #4CAF50;">%s</a>
                            </td>
                        </tr>
                
                        <tr>
                            <td style="font-size: 12px; color: #aaaaaa; text-align: center; padding-top: 40px;">
                                If you didn't request this change, you can safely ignore this email.<br>
                                © 2024 ToucanAI — All rights reserved.
                            </td>
                        </tr>
                    </table>
                </div>
                """.formatted(emailChangeConfirmationUrl, emailChangeConfirmationUrl, emailChangeConfirmationUrl);
        send(to, subject, content);
    }
}
