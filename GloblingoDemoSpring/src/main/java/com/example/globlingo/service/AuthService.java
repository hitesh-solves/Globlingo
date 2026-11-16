package com.example.globlingo.service;

import com.example.globlingo.model.AppUser;
import com.example.globlingo.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService implements org.springframework.security.core.userdetails.UserDetailsService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public String normalizeEmail(String email) {
        String normalizedEmail = normalizeEmailValue(email);
        return normalizedEmail;
    }

    public void validateRegistrationPassword(String password) {
        validatePassword(password);
    }

    @Transactional
    public AppUser createVerifiedAccount(String email, String passwordHash) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateAccountException();
        }

        AppUser user = new AppUser();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordHash);
        return userRepository.save(user);
    }

    public String encodePassword(String password) {
        validatePassword(password);
        return passwordEncoder.encode(password);
    }

    public AppUser requireUser(String email) {
        return userRepository.findByEmail(normalizeEmailValue(email))
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        AppUser user = userRepository.findByEmail(normalizeEmailValue(email))
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("Account not found"));
        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles("USER")
                .build();
    }

    private String normalizeEmailValue(String email) {
        if (email == null) throw new IllegalArgumentException("Email is required");
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be at least 12 characters and no more than 72 UTF-8 bytes");
        }
    }
}
