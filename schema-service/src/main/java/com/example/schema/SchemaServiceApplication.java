package com.example.schema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class SchemaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchemaServiceApplication.class, args);
    }
}
