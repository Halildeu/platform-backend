package com.example.ethics.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * ES-403 — the products this cell can sell, and what each one carries.
 *
 * <p>Two rules the acceptance names, both load-bearing:
 *
 * <p><strong>Entitlement never stands in for authorization.</strong> This class answers "did
 * the organisation buy this", and nothing here answers "may this person do it". The second
 * question belongs to the authorization model. Keeping them apart is what stops a billing
 * lapse from being reported to a handler as a permission denial.
 *
 * <p><strong>Public intake never asks.</strong> A reporter must be able to file even if the
 * catalog, the subscription store or the billing system is unavailable. In an ordinary
 * product an entitlement outage costs a feature; here it would close the channel someone is
 * trying to use to report wrongdoing, which is the failure the whole product exists to
 * prevent. {@code CatalogBoundaryTest} asserts the intake path holds no reference to this
 * package.
 */
@Component
public class EthicsProductCatalog {

    /** Everything the cell can sell today. */
    private static final List<ProductDefinition> PRODUCTS = List.of(
            new ProductDefinition("etik-speak-core", Set.of(
                    EthicsCapability.EVIDENCE_ATTACHMENTS,
                    EthicsCapability.SLA_NOTIFICATIONS)),
            new ProductDefinition("etik-speak-plus", Set.of(
                    EthicsCapability.EVIDENCE_ATTACHMENTS,
                    EthicsCapability.SLA_NOTIFICATIONS,
                    EthicsCapability.MULTI_HOST_INTAKE,
                    EthicsCapability.DATA_EXPORT)),
            // SUBJECT_REVEAL sits alone on purpose. It is the capability whose misuse is
            // least recoverable, so it is bought deliberately rather than arriving inside a
            // bundle someone chose for other reasons.
            new ProductDefinition("etik-speak-subject-reveal", Set.of(
                    EthicsCapability.SUBJECT_REVEAL)));

    private final Map<String, ProductDefinition> byId = PRODUCTS.stream()
            .collect(Collectors.toUnmodifiableMap(ProductDefinition::id, Function.identity()));

    public Optional<ProductDefinition> find(String productId) {
        return productId == null ? Optional.empty() : Optional.ofNullable(byId.get(productId));
    }

    public List<ProductDefinition> all() {
        return PRODUCTS;
    }

    /**
     * Whether the given products together carry the capability.
     *
     * <p>Fail-closed by construction: an unknown product id contributes nothing rather than
     * being treated as permissive, and an empty subscription list answers false. A catalog
     * that answered "true" when it could not resolve a product would hand out the capability
     * that is hardest to take back.
     */
    public boolean carries(Set<String> productIds, EthicsCapability capability) {
        if (productIds == null || capability == null) return false;
        return productIds.stream()
                .map(this::find)
                .flatMap(Optional::stream)
                .anyMatch(p -> p.carries(capability));
    }
}
