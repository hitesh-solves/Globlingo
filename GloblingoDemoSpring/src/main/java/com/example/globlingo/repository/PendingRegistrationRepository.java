package com.example.globlingo.repository;

import com.example.globlingo.model.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, String> { }
