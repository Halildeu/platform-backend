package com.example.variant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class VariantServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(VariantServiceApplication.class, args);
    }
}
