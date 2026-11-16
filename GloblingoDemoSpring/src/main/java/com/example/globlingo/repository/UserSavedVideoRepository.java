package com.example.globlingo.repository;

import com.example.globlingo.model.UserSavedVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserSavedVideoRepository extends JpaRepository<UserSavedVideo, Long> {
    Optional<UserSavedVideo> findByUserIdAndVideoId(Long userId, String videoId);
    List<UserSavedVideo> findAllByUserIdOrderBySavedAtDesc(Long userId);
    boolean existsByUserIdAndVideoId(Long userId, String videoId);
    void deleteByUserIdAndVideoId(Long userId, String videoId);
}
