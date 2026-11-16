package com.example.globlingo.service;

import com.example.globlingo.model.AppUser;
import com.example.globlingo.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final AppUserRepository userRepository;

    @Transactional(readOnly = true)
    public AppUser requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated account no longer exists"));
    }
}
