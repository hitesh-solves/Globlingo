package com.example.globlingo.controller;

import com.example.globlingo.model.SubscribedChannel;
import com.example.globlingo.model.UserChannelSubscription;
import com.example.globlingo.service.CurrentUserService;
import com.example.globlingo.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final UserSubscriptionService subscriptionService;
    private final CurrentUserService currentUserService;

    @PostMapping
    public ResponseEntity<ApiResponse> subscribe(
            @RequestParam String channelId,
            @RequestParam String channelName,
            @RequestParam(required = false) String thumbnail,
            Authentication authentication) {

        log.info("Subscribe request: channelId={}, channelName={}", channelId, channelName);

        try {
            UserChannelSubscription channel = subscriptionService.subscribe(
                    currentUserService.requireUser(authentication.getName()), channelId, channelName, thumbnail);
            return ResponseEntity.ok(new ApiResponse(true, "Channel saved to your Globlingo subscriptions", toDto(channel)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, ex.getMessage(), null));
        }
    }

    @DeleteMapping("/{channelId}")
    public ResponseEntity<ApiResponse> unsubscribe(@PathVariable String channelId, Authentication authentication) {
        log.info("Unsubscribe request: channelId={}", channelId);

        subscriptionService.unsubscribe(currentUserService.requireUser(authentication.getName()), channelId);

        return ResponseEntity.ok(new ApiResponse(true, "Channel unsubscribed successfully", null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse> getAllSubscriptions(Authentication authentication) {
        log.info("Get all subscriptions request");

        List<ChannelDTO> channels = subscriptionService
                .getSubscriptions(currentUserService.requireUser(authentication.getName()))
                .stream().map(this::toDto).toList();

        return ResponseEntity.ok(new ApiResponse(true, "Subscriptions retrieved", channels));
    }

    @GetMapping("/{channelId}/status")
    public ResponseEntity<ApiResponse> checkSubscriptionStatus(@PathVariable String channelId,
                                                                Authentication authentication) {
        log.info("Check subscription status request: channelId={}", channelId);

        boolean isSubscribed = subscriptionService.isSubscribed(
                currentUserService.requireUser(authentication.getName()), channelId);

        return ResponseEntity.ok(new ApiResponse(true, "Status checked", isSubscribed));
    }

    record ApiResponse(boolean success, String message, Object data) {}

    private ChannelDTO toDto(UserChannelSubscription subscription) {
        return new ChannelDTO(subscription.getChannelId(), subscription.getChannelName(),
                subscription.getThumbnail(), subscription.getSubscribedAt());
    }

    record ChannelDTO(String channelId, String channelName, String thumbnail, java.time.LocalDateTime subscribedAt) {}
}
