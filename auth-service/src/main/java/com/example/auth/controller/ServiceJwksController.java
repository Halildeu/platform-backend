package com.example.auth.controller;

import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/oauth2")
@Profile({"local", "dev"})
public class ServiceJwksController {

    private final JWKSet jwkSet;

    public ServiceJwksController(JWKSet jwkSet) {
        this.jwkSet = jwkSet;
    }

    @GetMapping("/jwks")
    public Map<String, Object> keys() {
        return jwkSet.toJSONObject();
    }
}
