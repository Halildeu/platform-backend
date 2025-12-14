package com.example.variant.theme.service;

import java.util.UUID;

public class ThemeNotFoundException extends RuntimeException {

    public ThemeNotFoundException(UUID id) {
        super("Theme not found: " + id);
    }
}

