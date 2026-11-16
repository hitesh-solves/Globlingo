package com.example.globlingo.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${app.security.session-cookie-secure:false}")
    private boolean secureCookies;

    @Value("${app.security.csrf-cookie-samesite:Lax}")
    private String csrfCookieSameSite;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .sameSite(csrfCookieSameSite)
                .secure(secureCookies));
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CookieCsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(java.util.List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/h2-console/**"))
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/api/videos/**", "/api/translate/**", "/api/ai-tutor/**",
                                "/api/auth/csrf", "/api/auth/register", "/api/auth/verify",
                                "/api/auth/resend-verification", "/api/auth/login",
                                "/h2-console/**").permitAll()
                        .requestMatchers("/api/saved/**", "/api/subscriptions/**", "/api/auth/me",
                                "/api/auth/logout").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeUnauthorized(response)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .build();
    }

    private static void writeUnauthorized(HttpServletResponse response) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"Sign in is required\",\"data\":null}");
    }

    private static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           Supplier<CsrfToken> csrfToken) {
            super.handle(request, response, csrfToken);
            csrfToken.get();
        }
    }
}
