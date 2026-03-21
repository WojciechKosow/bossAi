package com.BossAi.bossAi.security;

import com.BossAi.bossAi.entity.AuthProvider;
import com.BossAi.bossAi.entity.User;
import com.BossAi.bossAi.repository.UserRepository;
import com.BossAi.bossAi.service.AssignPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final AssignPlanService assignPlanService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {

        OAuth2User oAuth2User = super.loadUser(request);
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String email = (String) attributes.get("email");
        String name = (String) attributes.get("name");
        String sub = (String) attributes.get("sub");
        String picture = (String) attributes.get("picture");

        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_found"),
                    "Email not provided by Google. Ensure your Google account has a verified email."
            );
        }

        if (sub == null || sub.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_provider_id"),
                    "Provider ID (sub) missing from Google response."
            );
        }

//        User user = userRepository.findByEmail(email)
//                .map(existing -> {
//
//                    if (email == null) {
//                        throw new OAuth2AuthenticationException("Email not provided by Google");
//                    }
//
//                    if (existing.getProvider() == AuthProvider.LOCAL) {
//                        existing.setProvider(AuthProvider.GOOGLE);
//                        existing.setProviderId(sub);
//                    }
//
//                    if (existing.getProvider() == AuthProvider.GOOGLE) {
//                        if (!sub.equals(existing.getProviderId())) {
//                            throw new OAuth2AuthenticationException("Invalid provider ID");
//                        }
//                    }
//
//                    existing.setDisplayName(name);
//                    existing.setAvatarImage(picture);
//                    return existing;
//                })
//                .orElseGet(() -> {
//                    User newUser = new User();
//                    newUser.setEmail(email);
//                    newUser.setDisplayName(name);
//                    newUser.setAvatarImage(picture);
//                    newUser.setProvider(AuthProvider.GOOGLE);
//                    newUser.setProviderId(sub);
//                    newUser.setEnabled(true);
//                    newUser.setPassword("");
//                    User savedUser = userRepository.save(newUser);
//                    assignPlanService.assignFreePlan(newUser);
//                    return savedUser;
//                });


        User user = userRepository.findByEmail(email.toLowerCase())
                .map(existing -> linkOrValidate(existing, sub, name, picture))
                .orElseGet(() -> registerNewGoogleUser(email, name, sub, picture));
        userRepository.save(user);

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                "email"
        );
    }

    private User linkOrValidate(User existing, String sub, String name, String picture) {
        if (existing.getProvider() == AuthProvider.LOCAL) {
            if (!existing.isEnabled()) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("account_not_verified"),
                        "An unverified account exists with this email. " +
                                "Please verify your email first, then sign in with Google to link your accounts."

                );
            }

            existing.setProvider(AuthProvider.GOOGLE);
            existing.setProviderId(sub);
        } else if (existing.getProvider() == AuthProvider.GOOGLE) {
            if (!sub.equals(existing.getProviderId())) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("provider_id_mismatch"),
                        "Google account ID does not match the registered account."
                );
            }
        }

        existing.setDisplayName(name);
        existing.setAvatarImage(picture);
        return existing;
    }

    private User registerNewGoogleUser(String email, String name, String sub, String picture) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setAvatarImage(picture);
        user.setProvider(AuthProvider.GOOGLE);
        user.setProviderId(sub);
        user.setEnabled(true);
        user.setPassword("");

        User savedUser = userRepository.save(user);
        assignPlanService.assignFreePlan(savedUser);
        return savedUser;
    }
}
