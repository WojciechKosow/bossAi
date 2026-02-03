package com.BossAi.bossAi.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    private final JavaMailSender mailSender;


    @Override
    public void sendVerificationEmail(String to, String token) {
        String subject = "Verify your email to activate your account";
        String confirmationUrl = "http://localhost:8080/api/auth/verify?token=" + token;
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("error: cannot send an email", e);
        }
    }

    @Override
    public void sendPasswordResetEmail(String to, String token) {
        String subject = "Reset your password";
        String passwordResetUrl = "http://localhost:8080/api/auth/reset-password?token=" + token;
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("error: cannot send message", e);
        }
    }

    @Override
    public void sendEmailChangeEmail(String to, String token) {
        String subject = "Change your email";
        String emailChangeUrl = "http://localhost:8080/api/auth/change-email?token=" + token;
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Error: cannot send email", e);
        }
    }

    @Override
    public void sendEmailChangeConfirmation(String to, String token) {
        String subject = "Confirm your new email";
        String emailChangeConfirmationUrl = "http://localhost:8080/api/auth/change-email-confirmation?token=" + token;
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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Error: cannot send email", e);
        }
    }
}
