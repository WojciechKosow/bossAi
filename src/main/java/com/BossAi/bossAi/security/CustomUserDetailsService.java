package com.BossAi.bossAi.security;

import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmail(username)
                .map(CustomUserDetails::new)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public User loadUserEntityById(String userId) {
        return userRepository.findById(UUID.fromString(userId))
                .orElseThrow();
    }
}
