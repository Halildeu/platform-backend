package com.example.variant.theme.repository;

import com.example.variant.theme.domain.UserThemeSelection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserThemeSelectionRepository extends JpaRepository<UserThemeSelection, String> {
}

