package com.example.variant.theme.repository;

import com.example.variant.theme.domain.Theme;
import com.example.variant.theme.domain.ThemeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ThemeRepository extends JpaRepository<Theme, UUID> {

    List<Theme> findByType(ThemeType type);

    List<Theme> findByTypeAndOwnerUserId(ThemeType type, String ownerUserId);

    long countByTypeAndActiveFlagTrue(ThemeType type);

    List<Theme> findByTypeAndVisibility(ThemeType type, String visibility);

    Optional<Theme> findFirstByTypeAndVisibility(ThemeType type, String visibility);
}
