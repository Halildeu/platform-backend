package com.example.variant.theme.service;

import java.time.Instant;
import java.util.Map;

public class ResolvedTheme {

    private String themeId;
    private String type;
    private String version;
    private String appearance;
    private String surfaceTone;
    private Instant updatedAt;
    private ThemeAxesSnapshot axes;
    private Map<String, String> tokens;

    public String getThemeId() {
        return themeId;
    }

    public void setThemeId(String themeId) {
        this.themeId = themeId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    public String getSurfaceTone() {
        return surfaceTone;
    }

    public void setSurfaceTone(String surfaceTone) {
        this.surfaceTone = surfaceTone;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public ThemeAxesSnapshot getAxes() {
        return axes;
    }

    public void setAxes(ThemeAxesSnapshot axes) {
        this.axes = axes;
    }

    public Map<String, String> getTokens() {
        return tokens;
    }

    public void setTokens(Map<String, String> tokens) {
        this.tokens = tokens;
    }

    public static class ThemeAxesSnapshot {
        private String accent;
        private String density;
        private String radius;
        private String elevation;
        private String motion;

        public String getAccent() {
            return accent;
        }

        public void setAccent(String accent) {
            this.accent = accent;
        }

        public String getDensity() {
            return density;
        }

        public void setDensity(String density) {
            this.density = density;
        }

        public String getRadius() {
            return radius;
        }

        public void setRadius(String radius) {
            this.radius = radius;
        }

        public String getElevation() {
            return elevation;
        }

        public void setElevation(String elevation) {
            this.elevation = elevation;
        }

        public String getMotion() {
            return motion;
        }

        public void setMotion(String motion) {
            this.motion = motion;
        }
    }
}
