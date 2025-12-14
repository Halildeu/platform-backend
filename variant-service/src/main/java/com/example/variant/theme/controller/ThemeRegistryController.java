package com.example.variant.theme.controller;

import com.example.variant.theme.domain.ThemeRegistryEntry;
import com.example.variant.theme.repository.ThemeRegistryEntryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/theme-registry")
public class ThemeRegistryController {

    private final ThemeRegistryEntryRepository registryRepository;

    public ThemeRegistryController(ThemeRegistryEntryRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    @GetMapping
    public List<ThemeRegistryEntry> listRegistry() {
        return registryRepository.findAll();
    }
}

