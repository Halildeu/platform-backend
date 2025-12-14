package com.example.apigateway;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.time.Duration; // YENİ IMPORT

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@Testcontainers
@Disabled("Disabled on ARM64 / CI due to docker/compose emulation slowness; enable on x86 runners")
public class E2ETests {

    @Container
    private static final DockerComposeContainer<?> ENVIRONMENT = new DockerComposeContainer<>(new File("../docker-compose.yml"))
            .withExposedService("permission-service", 8084, Wait.forHttp("/actuator/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)))
            .withExposedService("auth-service", 8088, Wait.forHttp("/actuator/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)))
            .withExposedService("user-service", 8089, Wait.forHttp("/actuator/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)))
            .withExposedService("api-gateway", 8080, Wait.forHttp("/actuator/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    public String getGatewayUrl() {
        // Servis adını docker-compose.yml'deki gibi doğrudan kullanıyoruz.
        String host = ENVIRONMENT.getServiceHost("api-gateway", 8080);
        int port = ENVIRONMENT.getServicePort("api-gateway", 8080);
        return "http://" + host + ":" + port;
    }

    @Test
    void fullUserRegistrationAndLoginFlow() {
        String baseUrl = getGatewayUrl();
        String newUserEmail = "e2e-user-" + System.currentTimeMillis() + "@example.com";
        String password = "password123";

        Long companyId = 1L;

        // Adım 1: Yönetici olarak yeni kullanıcı kaydet
        String adminToken = getAdminToken(baseUrl, companyId);

        given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Company-Id", companyId)
            .body(String.format("""
                {
                    "name": "E2E User",
                    "email": "%s",
                    "password": "%s"
                }
                """, newUserEmail, password))
        .when()
            .post(baseUrl + "/api/users/register")
        .then()
            .statusCode(201);
            
        System.out.println(">>> Adım 1: Yönetici olarak kullanıcı kaydı başarılı.");

        // Adım 2: Giriş yap ve token al
        String userToken = given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                    "email": "%s",
                    "password": "%s",
                    "companyId": %d
                }
                """, newUserEmail, password, companyId))
        .when()
            .post(baseUrl + "/api/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("permissions", hasItem("VIEW_USERS"))
            .extract().path("token");

        System.out.println(">>> Adım 2: Kullanıcı girişi ve token alımı başarılı.");

        // Adım 3: Alınan token ile korumalı endpoint'i çağır
        given()
            .header("Authorization", "Bearer " + userToken)
            .header("X-Company-Id", companyId)
        .when()
            .get(baseUrl + "/api/users/me/authorities")
        .then()
            .statusCode(200)
            .body("username", equalTo(newUserEmail))
            .body("authorities", hasItem("ROLE_USER"));

        System.out.println(">>> Adım 3: Alınan token ile korumalı endpoint'e erişim başarılı.");
        // Adım 4: Yönetici token'ı ile kullanıcı listesini çek
        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Company-Id", companyId)
        .when()
            .get(baseUrl + "/api/users/all")
        .then()
            .statusCode(200)
            .body("$", not(empty()))
            .body("email", hasItem("admin@example.com"));

        System.out.println(">>> Adım 4: Yönetici kullanıcıları listeleyebildi.");
        System.out.println(">>> E2E Testi Başarıyla Tamamlandı! 🎉");
    }

    private String getAdminToken(String baseUrl, Long companyId) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                        "email": "admin@example.com",
                        "password": "admin1234",
                        "companyId": %d
                    }
                    """, companyId))
            .when()
                .post(baseUrl + "/api/auth/login")
            .then()
                .statusCode(200)
                .body("token", notNullValue())
                .extract().path("token");
    }
}
