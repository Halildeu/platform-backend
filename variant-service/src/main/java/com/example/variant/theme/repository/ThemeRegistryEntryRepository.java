package com.example.variant.theme.repository;

import com.example.variant.theme.domain.ThemeRegistryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ThemeRegistryEntryRepository extends JpaRepository<ThemeRegistryEntry, UUID> {

    Optional<ThemeRegistryEntry> findByKey(String key);
}

