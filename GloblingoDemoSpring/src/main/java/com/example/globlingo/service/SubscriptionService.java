package com.example.globlingo.service;

import com.example.globlingo.model.SubscribedChannel;
import com.example.globlingo.repository.SubscribedChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscribedChannelRepository subscribedChannelRepository;

    public SubscribedChannel subscribe(String channelId, String channelName, String thumbnail) {
        Optional<SubscribedChannel> existing = subscribedChannelRepository.findByChannelId(channelId);

        if (existing.isPresent()) {
            log.info("Channel {} already subscribed", channelId);
            return existing.get();
        }

        SubscribedChannel channel = new SubscribedChannel();
        channel.setChannelId(channelId);
        channel.setChannelName(channelName);
        channel.setThumbnail(thumbnail);

        return subscribedChannelRepository.save(channel);
    }

    public void unsubscribe(String channelId) {
        if (subscribedChannelRepository.existsByChannelId(channelId)) {
            subscribedChannelRepository.deleteByChannelId(channelId);
            log.info("Unsubscribed from channel {}", channelId);
        }
    }

    public boolean isSubscribed(String channelId) {
        return subscribedChannelRepository.existsByChannelId(channelId);
    }

    public List<SubscribedChannel> getAllSubscriptions() {
        return subscribedChannelRepository.findAllByOrderBySubscribedAtDesc();
    }
}
