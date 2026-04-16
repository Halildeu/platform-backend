package com.example.variant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * D7 (platform-k8s-gitops 2026-04-15):
 * @EnableDiscoveryClient kaldırıldı — K8s ortamında Eureka yok,
 * svc DNS (<svc>.<ns>.svc.cluster.local) ile doğrudan routing.
 * pom.xml'den spring-cloud-starter-netflix-eureka-client dep'i de çıkarıldı.
 */
@SpringBootApplication
public class VariantServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(VariantServiceApplication.class, args);
    }
}
