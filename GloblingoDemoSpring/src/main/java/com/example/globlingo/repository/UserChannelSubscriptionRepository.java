package com.example.globlingo.repository;

import com.example.globlingo.model.UserChannelSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserChannelSubscriptionRepository extends JpaRepository<UserChannelSubscription, Long> {
    Optional<UserChannelSubscription> findByUserIdAndChannelId(Long userId, String channelId);
    List<UserChannelSubscription> findAllByUserIdOrderBySubscribedAtDesc(Long userId);
    boolean existsByUserIdAndChannelId(Long userId, String channelId);
    void deleteByUserIdAndChannelId(Long userId, String channelId);
}
