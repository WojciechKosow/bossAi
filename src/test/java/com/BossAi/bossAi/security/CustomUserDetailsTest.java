package com.BossAi.bossAi.security;


import com.BossAi.bossAi.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;


public class CustomUserDetailsTest {

    private User user;
    private CustomUserDetails customUserDetails;

    @BeforeEach
    void setUp() {
        user = new User();
        UUID id = UUID.randomUUID();
        user.setId(id);
        user.setEmail("example@email.com");
        user.setPassword("encodedPassword");
        user.setCreatedAt(LocalDateTime.now());
        user.setEnabled(true);
        customUserDetails = new CustomUserDetails(user);
    }

    @Test
    void isEnabled_ShouldReturnUserEnabledStatus() {
        assertTrue(customUserDetails.isEnabled());

        user.setEnabled(false);
        customUserDetails = new CustomUserDetails(user);

        assertFalse(customUserDetails.isEnabled());
    }

    @Test
    void getPassword_ShouldReturnStoredPassword() {
        assertEquals("encodedPassword", customUserDetails.getPassword());
    }

    @Test
    void getUsername_ShouldReturnUserEmail() {
        assertEquals("example@email.com", customUserDetails.getUsername());
    }
}
