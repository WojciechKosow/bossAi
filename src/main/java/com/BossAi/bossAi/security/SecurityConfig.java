package com.BossAi.bossAi.security;

import com.BossAi.bossAi.config.BetaConfig;
import com.BossAi.bossAi.ratelimit.RateLimitFilter;
import com.BossAi.bossAi.ratelimit.RateLimitService;
import jakarta.servlet.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final BetaConfig betaConfig;

    // Comma-separated allowed CORS origins. Set app.cors.allowed-origins
    // (env APP_CORS_ALLOWED_ORIGINS) to your production frontend origin(s) —
    // EXACT origins, no trailing slash and no path, e.g.
    //   https://app.example.com,https://www.example.com
    @org.springframework.beans.factory.annotation.Value(
            "${app.cors.allowed-origins:http://localhost:5173,http://localhost:1420}")
    private java.util.List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/test/**",
                                "/api/auth/**",
                                "/oauth2/**",
                                "/internal/**",
                                // Stripe webhook: authenticated by signature
                                // verification (see StripeWebhookService), not a
                                // JWT — Stripe calls it server-to-server.
                                "/api/payments/webhook",
                                // UUID-keyed asset blobs: equivalent to a signed
                                // URL since asset IDs are 122-bit random UUIDs.
                                // Lets <img>/<video> tags load previews without
                                // an Authorization header.
                                "/api/assets/file/**",
                                // Rendered-video blobs, UUID-keyed (RenderJob id)
                                // like the asset blobs above — same signed-URL logic.
                                "/api/renders/*/file",
                                // SSE progress stream. UUID-keyed like the asset
                                // blobs. Must be permitAll: the SseEmitter times
                                // out on long renders and Tomcat re-dispatches the
                                // request through the security chain asynchronously
                                // — that dispatch is NOT re-authenticated (stateless
                                // JWT), so an authenticated matcher throws
                                // AuthorizationDeniedException on every timeout.
                                "/api/generations/*/progress"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        if (!betaConfig.isBetaMode()) {
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(authEndpoint -> authEndpoint
                            .baseUri("/oauth2/authorization")
                    )
                    .redirectionEndpoint(redirection -> redirection
                            .baseUri("/oauth2/callback/*")
                    )
                    .userInfoEndpoint(userInfo -> userInfo
                            .userService(customOAuth2UserService)
                    )
                    .successHandler(oAuth2SuccessHandler)
            );
        }

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);
        // EXACT origins only (no trailing slash) — a trailing slash never matches
        // the browser's Origin header and silently blocks every request.
        config.setAllowedOrigins(allowedOrigins);

        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type"
        ));

        config.setExposedHeaders(List.of(
                "Authorization"
        ));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
