package com.example.ethics.api;

import com.example.ethics.catalog.EthicsCapability;
import com.example.ethics.catalog.EthicsEntitlements;
import com.example.ethics.security.StaffContext;
import com.example.ethics.security.StaffContextResolver;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ES-403 — what this organisation holds (#885).
 *
 * <p>Read-only and scoped to the caller's own organisation, which is taken from the token
 * rather than from a parameter: an organisation id in the request would be a way to ask about
 * someone else's commercial state, and there is no reason for that question to exist here.
 *
 * <p><strong>No write endpoint, deliberately.</strong> A subscription is a commercial fact
 * granted by the vendor, not a setting the customer changes. Exposing a write here — even
 * behind the manager role — would let an organisation grant itself
 * {@link EthicsCapability#SUBJECT_REVEAL}, the capability the catalog deliberately refuses to
 * bundle because its misuse is least recoverable. Grants are made out of band, audited into
 * the append-only ledger, and take effect within one cache TTL.
 *
 * <p>Note for the reader: {@code EthicsEntitlements} answers what was bought, while
 * {@code EthicsEntitlementVerifier} — despite the name — answers whether this person still
 * holds ETHIC=MANAGE, which is authorization. The two must not be swapped; the first decides
 * whether a feature exists for the organisation, the second whether the caller may act.
 */
@RestController
@RequestMapping("/api/v1/ethics/entitlements")
public class EntitlementsController {

    private final EthicsEntitlements entitlements;
    private final StaffContextResolver context;

    public EntitlementsController(EthicsEntitlements entitlements, StaffContextResolver context) {
        this.entitlements = entitlements;
        this.context = context;
    }

    @GetMapping
    ResponseEntity<EntitlementsResponse> mine() {
        StaffContext staff = context.required();
        EthicsEntitlements.Holding holding = entitlements.holding(staff.orgId());

        List<String> products = holding.productIds().stream().sorted().toList();
        List<String> capabilities = holding.capabilities().stream()
                .map(Enum::name)
                .sorted(Comparator.naturalOrder())
                .toList();

        return ResponseEntity.ok()
                // The answer is cached in process for a TTL and can change the moment a grant
                // is made; a caching layer holding its own copy would extend that window
                // without anyone choosing to.
                .cacheControl(CacheControl.noStore())
                .body(new EntitlementsResponse(
                        products,
                        capabilities,
                        holding.authoritative() ? "HELD" : "UNKNOWN"));
    }

    /**
     * @param resolution {@code HELD} when the answer was established, {@code UNKNOWN} when the
     *     subscription store could not be read — empty lists then mean "could not be
     *     determined", not "nothing bought". Named for confidence only: the cause and the
     *     health of any dependency stay out of the response.
     */
    public record EntitlementsResponse(
            List<String> products, List<String> capabilities, String resolution) {}
}
