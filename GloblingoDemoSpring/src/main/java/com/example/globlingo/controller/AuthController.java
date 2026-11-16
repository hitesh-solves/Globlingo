package com.example.globlingo.controller;

import com.example.globlingo.model.AppUser;
import com.example.globlingo.service.AuthService;
import com.example.globlingo.service.DuplicateAccountException;
import com.example.globlingo.service.EmailVerificationService;
import org.springframework.mail.MailException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CookieCsrfTokenRepository csrfTokenRepository;

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getToken(), csrfToken.getHeaderName());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @RequestBody(required = false) AuthRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (body == null) return badRequest("Email and password are required");
        try {
            EmailVerificationService.RegistrationStatus status = emailVerificationService.startRegistration(body.email(), body.password());
            if (status == EmailVerificationService.RegistrationStatus.EXISTING_ACCOUNT) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(new AuthResponse(false,
                        "An account with this email already exists. Please log in.", new UserProfile(null, body.email().trim())));
            }
            return ResponseEntity.accepted().body(new AuthResponse(true,
                    "If this address can be registered, a verification code has been sent.",
                    new UserProfile(null, body.email().trim())));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, ex.getMessage(), null));
        } catch (MailException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, "The verification email could not be delivered. Check the email provider configuration and retry.", null));
        } catch (DuplicateAccountException ex) {
            return ResponseEntity.accepted().body(new AuthResponse(true,
                    "If this address can be registered, a verification code has been sent.",
                    new UserProfile(null, body.email().trim())));
        } catch (DataIntegrityViolationException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new AuthResponse(false, "An account with this email already exists", null));
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, "Account storage is temporarily unavailable. Please try again.", null));
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(false, "Could not sign in after account creation", null));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verifyRegistration(@RequestBody(required = false) VerificationRequest body) {
        if (body == null || body.email() == null || body.code() == null) {
            return badRequest("Email and verification code are required");
        }
        try {
            AppUser user = emailVerificationService.verify(body.email(), body.code()).orElse(null);
            if (user == null) return badRequest("The verification code is invalid, expired, or has reached its attempt limit.");
            return ResponseEntity.ok(new AuthResponse(true, "Email verified. Please log in.", profile(user)));
        } catch (org.springframework.dao.DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, "Account storage is temporarily unavailable. Please try again.", null));
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<AuthResponse> resendVerification(@RequestBody(required = false) VerificationEmailRequest body) {
        if (body == null || body.email() == null) return badRequest("Email is required");
        try {
            emailVerificationService.resend(body.email());
            return ResponseEntity.accepted().body(new AuthResponse(true,
                    "If verification is pending, a new code has been sent when allowed.", null));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, ex.getMessage(), null));
        } catch (MailException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, "The verification email could not be delivered. Check the email provider configuration and retry.", null));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @RequestBody(required = false) AuthRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (body == null || body.email() == null || body.password() == null) {
            return badRequest("Email and password are required");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
            establishSession(authentication, request, response);
            AppUser user = authService.requireUser(authentication.getName());
            return ResponseEntity.ok(new AuthResponse(true, "Signed in", profile(user)));
        } catch (InternalAuthenticationServiceException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, "Account storage is temporarily unavailable. Please try again.", null));
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(false, "Invalid email or password", null));
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new AuthResponse(false, "Account storage is temporarily unavailable. Please try again.", null));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(false, "Invalid email or password", null));
        }
    }

    @GetMapping("/me")
    public AuthResponse currentUser(Authentication authentication) {
        return new AuthResponse(true, "Signed in", profile(authService.requireUser(authentication.getName())));
    }

    @PostMapping("/logout")
    public AuthResponse logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        csrfTokenRepository.saveToken(null, request, response);
        return new AuthResponse(true, "Signed out", null);
    }

    private void establishSession(Authentication authentication,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.getContextHolderStrategy().setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private ResponseEntity<AuthResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(new AuthResponse(false, message, null));
    }

    private UserProfile profile(AppUser user) {
        return new UserProfile(user.getId(), user.getEmail());
    }

    public record AuthRequest(String email, String password) {}
    public record VerificationRequest(String email, String code) {}
    public record VerificationEmailRequest(String email) {}
    public record UserProfile(Long id, String email) {}
    public record AuthResponse(boolean success, String message, UserProfile data) {}
    public record CsrfResponse(String token, String headerName) {}
}
