package com.example.auth.serviceauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "security.service-jwt")
public class ServiceJwtKeyProperties {

    private String privateKey;
    private String publicKey;
    private String keyId = "service-jwt-key";

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        if (StringUtils.hasText(keyId)) {
            this.keyId = keyId;
        }
    }
}
