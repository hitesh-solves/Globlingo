package com.example.globlingo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import com.example.globlingo.model.PendingRegistration;
import com.example.globlingo.repository.PendingRegistrationRepository;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.example.globlingo.model.AppUser;
import com.example.globlingo.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GloblingoApplicationTests.MailTestConfiguration.class)
class GloblingoApplicationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JavaMailSender mailSender;
    @Autowired private PendingRegistrationRepository pendingRegistrations;

    @TestConfiguration
    static class MailTestConfiguration {
        @Bean
        @Primary
        JavaMailSender testMailSender() {
            return Mockito.mock(JavaMailSender.class);
        }
    }

    @Test
    void contextLoads() { }

    @Test
    void privateEndpointsRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/saved")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/subscriptions")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationHashesPasswordAndDuplicateEmailReturnsConflict() throws Exception {
        String email = "signup-" + UUID.randomUUID() + "@example.test";
        register(email);
        AppUser user = users.findByEmail(email.toLowerCase()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNotEquals("Correct-Horse-29", user.getPasswordHash());
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches("Correct-Horse-29", user.getPasswordHash()));

        clearInvocations(mailSender);
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"Correct-Horse-29\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists. Please log in."));
        verify(mailSender, Mockito.never()).send(Mockito.any(SimpleMailMessage.class));
    }

    @Test
    void duplicateUnverifiedRegistrationReusesPendingOtpAndDoesNotResendBeforeCooldown() throws Exception {
        String email = "pending-duplicate-" + UUID.randomUUID() + "@example.test";
        startVerification(email);
        clearInvocations(mailSender);
        mockMvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-29\"}"))
                .andExpect(status().isAccepted());
        org.junit.jupiter.api.Assertions.assertTrue(pendingRegistrations.findById(email).isPresent());
        verify(mailSender, Mockito.never()).send(Mockito.any(SimpleMailMessage.class));
    }

    @Test
    void loginCreatesSessionAndInvalidCredentialsAreRejected() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@example.test";
        register(email);
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-29\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));

        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"incorrect-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void corsPreflightAllowsLiveServerPort5501AndVideoKeyErrorsAreActionable() throws Exception {
        mockMvc.perform(options("/api/auth/register")
                        .header("Origin", "http://localhost:5501")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "http://localhost:5501"));

        mockMvc.perform(get("/api/videos/search").queryParam("query", "Spanish")
                        .queryParam("language", "es").queryParam("maxResults", "12"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("YOUTUBE_API_KEY")));
    }

    @Test
    void savedVideosArePersistedAndIsolatedByAccount() throws Exception {
        MockHttpSession alice = register("alice-" + UUID.randomUUID() + "@example.test");
        MockHttpSession bob = register("bob-" + UUID.randomUUID() + "@example.test");
        String videoId = "video-" + UUID.randomUUID();
        String longDescription = "x".repeat(1000);
        String video = """
                {"id":"%s","title":"Hola","creator":"Globlingo Demo",
                "thumbnail":"https://example.test/thumb.jpg","language":"es",
                "category":"Education","duration":60,"description":"%s"}
                """.formatted(videoId, longDescription);

        mockMvc.perform(post("/api/saved").session(alice).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(video))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/api/saved").session(alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(videoId))
                .andExpect(jsonPath("$.data[0].description").value(longDescription));
        mockMvc.perform(get("/api/saved").session(bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(delete("/api/saved/{id}", videoId).session(bob).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/saved/{id}/status", videoId).session(alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void subscriptionsArePrivateToTheOwningAccount() throws Exception {
        MockHttpSession alice = register("channel-owner-" + UUID.randomUUID() + "@example.test");
        MockHttpSession bob = register("channel-other-" + UUID.randomUUID() + "@example.test");
        String channelId = "UC" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/subscriptions").session(alice).with(csrf())
                        .queryParam("channelId", channelId).queryParam("channelName", "Example"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/subscriptions").session(bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void invalidSearchAndUnconfiguredTranslationAreReportedAccurately() throws Exception {
        mockMvc.perform(get("/api/videos/search").queryParam("query", " ")
                        .queryParam("language", "es").queryParam("maxResults", "12"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/api/translate").queryParam("word", "hola"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void searchRejectsUnsupportedSortModesAndMalformedPageTokens() throws Exception {
        mockMvc.perform(get("/api/videos/search").queryParam("query", "Spanish lesson")
                        .queryParam("language", "es").queryParam("order", "trending"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/videos/search").queryParam("query", "Spanish lesson")
                        .queryParam("language", "es").queryParam("pageToken", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shortVideoSearchValidatesInputAndReportsMissingYouTubeConfiguration() throws Exception {
        mockMvc.perform(get("/api/videos/shorts/search").queryParam("query", " ")
                        .queryParam("language", "es"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/api/videos/shorts/search").queryParam("query", "Spanish comedy shorts")
                        .queryParam("language", "es"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("YOUTUBE_API_KEY")));
    }

    @Test
    void aiTutorReportsMissingProviderConfigurationClearly() throws Exception {
        // Grammar correction now runs on Gemini, so the missing-key message names GEMINI_API_KEY.
        mockMvc.perform(post("/api/ai-tutor/correct").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sentence\":\"I am go yesterday\",\"targetLanguage\":\"English\",\"explanationLanguage\":\"English\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("GEMINI_API_KEY")));
    }

    @Test
    void wordMeaningReturnsPerServiceStatusesWithoutAnyProviderConfigured() throws Exception {
        // With no GEMINI_API_KEY the explanation is unavailable, but the endpoint still
        // answers with a structured result so the popup can show each service honestly.
        mockMvc.perform(post("/api/ai-tutor/word-meaning").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedWord\":\"exhausted\",\"subtitleSentence\":\"I am exhausted\","
                                + "\"learningLanguage\":\"English\",\"explanationLanguage\":\"Hindi\","
                                + "\"learningLanguageCode\":\"en\",\"explanationLanguageCode\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.word").value("exhausted"))
                .andExpect(jsonPath("$.explanationStatus").value("unavailable"))
                .andExpect(jsonPath("$.translationStatus").exists())
                .andExpect(jsonPath("$.dictionaryStatus").exists());
    }

    @Test
    void verificationRejectsInvalidAndExpiredCodesWithoutCreatingAccounts() throws Exception {
        String email = "otp-" + UUID.randomUUID() + "@example.test";
        String code = startVerification(email);
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/auth/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"code\":\"" + wrongCode + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/auth/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertFalse(users.existsByEmail(email));
    }

    @Test
    void expiredCodeIsRejectedAndResendHonorsCooldown() throws Exception {
        String email = "expired-" + UUID.randomUUID() + "@example.test";
        String code = startVerification(email);
        PendingRegistration pending = pendingRegistrations.findById(email).orElseThrow();
        pending.setExpiresAt(Instant.now().minusSeconds(1));
        pendingRegistrations.save(pending);
        mockMvc.perform(post("/api/auth/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/resend-verification").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        verify(mailSender, times(1)).send(Mockito.any(SimpleMailMessage.class));
    }

    private MockHttpSession register(String email) throws Exception {
        String code = startVerification(email);
        mockMvc.perform(post("/api/auth/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Email verified. Please log in."));
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-29\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private String startVerification(String email) throws Exception {
        clearInvocations(mailSender);
        mockMvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-29\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("If this address can be registered")));
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9]{6})")
                .matcher(message.getValue().getText());
        org.junit.jupiter.api.Assertions.assertTrue(matcher.find(), "The mocked email should contain a six-digit code");
        return matcher.group(1);
    }
}
