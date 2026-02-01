package com.BossAi.bossAi.service;

import com.BossAi.bossAi.request.LoginRequest;
import com.BossAi.bossAi.request.RegisterRequest;
import com.BossAi.bossAi.response.AuthResponse;

public interface UserService {

    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    void verifyAccount(String token);
    void resendVerificationEmail(String email);

}
