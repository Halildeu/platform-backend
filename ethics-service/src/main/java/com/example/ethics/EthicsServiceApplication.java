package com.example.ethics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EthicsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EthicsServiceApplication.class, args);
    }
}
