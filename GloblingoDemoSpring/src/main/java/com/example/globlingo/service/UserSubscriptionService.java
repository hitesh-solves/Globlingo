package com.example.globlingo.service;

import com.example.globlingo.model.AppUser;
import com.example.globlingo.model.UserChannelSubscription;
import com.example.globlingo.repository.UserChannelSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserSubscriptionService {

    private final UserChannelSubscriptionRepository repository;

    @Transactional
    public UserChannelSubscription subscribe(AppUser user, String channelId, String channelName, String thumbnail) {
        if (channelId == null || channelId.isBlank() || channelId.length() > 128
                || channelName == null || channelName.isBlank() || channelName.length() > 255
                || (thumbnail != null && thumbnail.length() > 2048)) {
            throw new IllegalArgumentException("Channel details are missing or outside supported limits");
        }
        return repository.findByUserIdAndChannelId(user.getId(), channelId)
                .orElseGet(() -> {
                    UserChannelSubscription subscription = new UserChannelSubscription();
                    subscription.setUser(user);
                    subscription.setChannelId(channelId);
                    subscription.setChannelName(channelName);
                    subscription.setThumbnail(thumbnail);
                    return repository.save(subscription);
                });
    }

    @Transactional
    public void unsubscribe(AppUser user, String channelId) {
        repository.deleteByUserIdAndChannelId(user.getId(), channelId);
    }

    @Transactional(readOnly = true)
    public boolean isSubscribed(AppUser user, String channelId) {
        return repository.existsByUserIdAndChannelId(user.getId(), channelId);
    }

    @Transactional(readOnly = true)
    public List<UserChannelSubscription> getSubscriptions(AppUser user) {
        return repository.findAllByUserIdOrderBySubscribedAtDesc(user.getId());
    }
}
