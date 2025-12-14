package com.example.variant.theme.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public class ThemeAxes {

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
