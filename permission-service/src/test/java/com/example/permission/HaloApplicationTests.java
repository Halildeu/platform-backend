package com.example.permission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = PermissionServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:permtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.sql.init.mode=never",
                "eureka.client.enabled=false"
        }
)
class HaloApplicationTests {

    @Test
    void contextLoads() {
    }
}
