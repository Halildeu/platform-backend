package com.example.gpcore.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Structural enforcement of the single Read Gateway choke-point (ADR-0035 §1;
 * Codex 019f1913 #4). These rules make the boundary machine-checked rather than
 * a social convention: future waves (UI, report, RAG adapters) cannot quietly
 * bypass the gateway to read raw content.
 */
@AnalyzeClasses(packages = "com.example.gpcore", importOptions = ImportOption.DoNotIncludeTests.class)
class ChokePointArchTest {

    /** The authz layer makes DECISIONS; it must never depend on a raw content port. */
    @ArchTest
    static final ArchRule authz_does_not_depend_on_content_ports = noClasses()
            .that().resideInAPackage("..authz..")
            .should().dependOnClassesThat().resideInAPackage("..port.content..")
            .because("authz produces decisions only and must not resolve content (no read bypass via authz)");

    /** Raw content ports may be injected ONLY by the gateway implementation and Spring config. */
    @ArchTest
    static final ArchRule content_ports_only_used_by_gateway_or_config = noClasses()
            .that().resideOutsideOfPackages("..gateway..", "..config..", "..port.content..")
            .should().dependOnClassesThat().resideInAPackage("..port.content..")
            .because("everything else must read through the ReadGateway, not the raw content ports");

    /** Domain stays pure — no dependency on gateway/authz/ports/config (keeps it AGE-migratable, ORM-free). */
    @ArchTest
    static final ArchRule domain_is_pure = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..authz..", "..gateway..", "..port..", "..config..")
            .because("domain is a pure value layer");
}
