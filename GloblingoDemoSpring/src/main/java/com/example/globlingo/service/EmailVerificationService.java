package com.example.globlingo.service;

import com.example.globlingo.model.AppUser;
import com.example.globlingo.model.PendingRegistration;
import com.example.globlingo.repository.AppUserRepository;
import com.example.globlingo.repository.PendingRegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {
    private static final Duration OTP_LIFETIME = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;
    private static final Pattern OTP_PATTERN = Pattern.compile("^[0-9]{6}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PendingRegistrationRepository pendingRepository;
    private final AppUserRepository userRepository;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.email.from:}")
    private String mailFrom;

    @Transactional
    public RegistrationStatus startRegistration(String email, String password) {
        String normalizedEmail = authService.normalizeEmail(email);
        authService.validateRegistrationPassword(password);
        if (userRepository.existsByEmail(normalizedEmail)) return RegistrationStatus.EXISTING_ACCOUNT;
        requireEmailConfigured();

        Instant now = Instant.now();
        Optional<PendingRegistration> existing = pendingRepository.findById(normalizedEmail);
        if (existing.isPresent() && !cooldownElapsed(existing.get(), now)) return RegistrationStatus.PENDING_VERIFICATION;

        PendingRegistration pending = existing.orElseGet(PendingRegistration::new);
        pending.setEmail(normalizedEmail);
        pending.setPasswordHash(authService.encodePassword(password));
        issueCode(pending, now);
        return RegistrationStatus.PENDING_VERIFICATION;
    }

    @Transactional
    public void resend(String email) {
        requireEmailConfigured();
        String normalizedEmail = authService.normalizeEmail(email);
        Optional<PendingRegistration> result = pendingRepository.findById(normalizedEmail);
        if (result.isEmpty()) return;
        PendingRegistration pending = result.get();
        Instant now = Instant.now();
        if (cooldownElapsed(pending, now)) issueCode(pending, now);
    }

    @Transactional
    public Optional<AppUser> verify(String email, String code) {
        String normalizedEmail;
        try {
            normalizedEmail = authService.normalizeEmail(email);
        } catch (IllegalArgumentException invalidEmail) {
            return Optional.empty();
        }
        if (code == null || !OTP_PATTERN.matcher(code).matches()) return Optional.empty();

        Optional<PendingRegistration> result = pendingRepository.findById(normalizedEmail);
        if (result.isEmpty()) return Optional.empty();
        PendingRegistration pending = result.get();
        Instant now = Instant.now();
        if (pending.getExpiresAt().isBefore(now) || pending.getAttempts() >= MAX_ATTEMPTS) {
            return Optional.empty();
        }
        if (!passwordEncoder.matches(code, pending.getOtpHash())) {
            pending.setAttempts(pending.getAttempts() + 1);
            pendingRepository.save(pending);
            return Optional.empty();
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            pendingRepository.delete(pending);
            return Optional.empty();
        }

        AppUser user = authService.createVerifiedAccount(normalizedEmail, pending.getPasswordHash());
        pendingRepository.delete(pending);
        return Optional.of(user);
    }

    private void issueCode(PendingRegistration pending, Instant now) {
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        pending.setOtpHash(passwordEncoder.encode(code));
        pending.setExpiresAt(now.plus(OTP_LIFETIME));
        pending.setLastSentAt(now);
        pending.setAttempts(0);
        pendingRepository.saveAndFlush(pending);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom.trim());
        message.setTo(pending.getEmail());
        message.setSubject("Your Globlingo verification code");
        message.setText("Your Globlingo verification code is " + code
                + ". It expires in 10 minutes. If you did not request this code, you can ignore this email.");
        mailSender.send(message);
    }

    private boolean cooldownElapsed(PendingRegistration pending, Instant now) {
        return pending.getLastSentAt() == null
                || !pending.getLastSentAt().plus(RESEND_COOLDOWN).isAfter(now);
    }

    private void requireEmailConfigured() {
        if (mailHost == null || mailHost.isBlank() || mailFrom == null || mailFrom.isBlank()) {
            throw new IllegalStateException("Email verification is not configured. Set MAIL_HOST and MAIL_FROM on the backend.");
        }
    }

    public enum RegistrationStatus { EXISTING_ACCOUNT, PENDING_VERIFICATION }
}
