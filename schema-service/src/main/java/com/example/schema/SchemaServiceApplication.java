package com.example.schema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * D7 (platform-k8s-gitops 2026-04-15):
 * @EnableDiscoveryClient bu serviste zaten yoktu; pom.xml'den
 * spring-cloud-starter-netflix-eureka-client dep'i çıkarıldı.
 * K8s ortamında Eureka yok, svc DNS (<svc>.<ns>.svc.cluster.local)
 * ile doğrudan routing yapılır.
 */
@SpringBootApplication
@EnableCaching
public class SchemaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchemaServiceApplication.class, args);
    }
}
