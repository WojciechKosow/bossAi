package com.BossAi.bossAi.security;

import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

public class CustomUserDetailsServiceTest {

    private UserRepository userRepository;
    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        customUserDetailsService = new CustomUserDetailsService(userRepository);
    }

    @Test
    void loadUserByUsername_Should_ReturnUserDetails_WhenUserExists() {
        User user = new User();
        UUID id = UUID.randomUUID();
        user.setId(id);
        user.setEmail("example@email.com");
        user.setDisplayName("username");
        user.setPassword("encodedPassword");
        user.setCreatedAt(LocalDateTime.now());

        when(userRepository.findByEmail("example@email.com")).thenReturn(Optional.of(user));
        UserDetails userDetails = customUserDetailsService.loadUserByUsername("example@email.com");

        assertNotNull(userDetails);
        assertEquals("example@email.com", userDetails.getUsername());
        assertEquals("encodedPassword", userDetails.getPassword());
    }

    @Test
    void loadUserByUsername_ShouldThrowException_WhenUserDoesNotExist() {
        when(userRepository.findByEmail("test@email.com")).thenReturn(Optional.empty());
//        UsernameNotFoundException exception = assertThrows(UsernameNotFoundException.class,
//                () -> customUserDetailsService.loadUserByUsername("test@email.com"));
//        assertEquals("User not found", exception.getMessage());
        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> customUserDetailsService.loadUserByUsername("test@email.com")
        );
        assertEquals("User not found", exception.getMessage());
    }
}
