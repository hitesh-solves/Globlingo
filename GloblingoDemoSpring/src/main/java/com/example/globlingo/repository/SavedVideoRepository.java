package com.example.globlingo.repository;

import com.example.globlingo.model.SavedVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedVideoRepository extends JpaRepository<SavedVideo, Long> {

    Optional<SavedVideo> findByVideoId(String videoId);

    List<SavedVideo> findAllByOrderBySavedAtDesc();

    boolean existsByVideoId(String videoId);

    void deleteByVideoId(String videoId);
}
