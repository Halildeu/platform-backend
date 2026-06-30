package com.example.gpcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gp-core-service — Faz 26 Governed Process &amp; Work Platform (Epic 26A).
 *
 * <p>Wave 1 = enforcement kernel: a single {@code ReadGateway} choke-point in
 * front of every read path (graph / search / RAG / evidence / export), backed by
 * an {@code AuthorizationDecisionService} that combines OpenFGA relationship
 * checks (data-plane policy enforcement point, ADR-0035) with a deny-overrides
 * ABAC layer (classification / legal-hold / policy-tags).
 *
 * <p>Isolation (ADR-0033, HARD): independent product, separate OpenFGA store
 * ({@code gp.openfga}, v2 model), separate package {@code com.example.gpcore},
 * separate board (Project #7). Does NOT mix with Faz 1-25.
 */
@SpringBootApplication
public class GpCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpCoreApplication.class, args);
    }
}
