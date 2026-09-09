package com.example.schema.config;

import com.example.commonauth.scope.AuthzVersionProvider;
import com.example.schema.config.AuthzMeClient.AuthzMeResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

/**
 * "May this caller use the REPORT module?" — the API-side twin of the shell's
 * {@code requiredModule="REPORT"} route guard (gitops#3605 / #3608).
 *
 * <p>The decision is exactly the frontend's {@code hasModule("REPORT")} applied to
 * permission-service's {@code /authz/me} projection: super admin allows;
 * {@code modules.REPORT} of {@code VIEW} or {@code MANAGE} allows; when the
 * modules map is empty the legacy {@code allowedModules} list is consulted;
 * everything else denies. Whatever permission-service projects — after canonical
 * identity resolution, the REPORT module invariant and deny-wins — the menu and
 * the API therefore agree.
 *
 * <p>Fail-closed: a refused token, an unreachable permission-service, a non-200
 * or an unparsable body all deny with the reason recorded. Decisions are
 * memoised per token for a short TTL and additionally invalidated when the
 * platform authorization revision moves, so a revoke is honoured within the
 * revision-provider memo window rather than the TTL.
 */
public class ReportModuleAccessGate {

    public static final String MODULE = "REPORT";
    private static final Set<String> USABLE_LEVELS = Set.of("VIEW", "MANAGE");
    private static final Logger log = LoggerFactory.getLogger(ReportModuleAccessGate.class);

    public record Decision(boolean allowed, String reason) {
        static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    private record Cached(Decision decision, long revision) {
    }

    private final AuthzMeClient client;
    private final AuthzVersionProvider revision;
    private final boolean enabled;
    private final boolean devPassThrough;
    private final Cache<String, Cached> cache;

    public ReportModuleAccessGate(AuthzMeClient client, AuthzVersionProvider revision,
                                  boolean enabled, boolean devPassThrough, Duration cacheTtl) {
        this.client = client;
        this.revision = revision;
        this.enabled = enabled;
        this.devPassThrough = devPassThrough;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl == null || cacheTtl.isNegative() ? Duration.ofSeconds(10) : cacheTtl)
                .maximumSize(10_000)
                .build();
    }

    public Decision decide(String bearerToken) {
        if (!enabled) {
            return devPassThrough ? Decision.allow("gate_disabled_dev") : Decision.deny("gate_disabled");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            return Decision.deny("no_token");
        }
        long currentRevision = revision.getCurrentVersion();
        String key = tokenKey(bearerToken);
        Cached cached = cache.getIfPresent(key);
        if (cached != null && cached.revision() == currentRevision) {
            return cached.decision();
        }
        Decision fresh = evaluate(client.fetch(bearerToken));
        cache.put(key, new Cached(fresh, currentRevision));
        return fresh;
    }

    static Decision evaluate(AuthzMeResult me) {
        switch (me.kind()) {
            case REJECTED -> {
                return Decision.deny("authz_me_" + me.detail());
            }
            case UNAVAILABLE -> {
                log.warn("authz/me unavailable ({}) — denying REPORT module access", me.detail());
                return Decision.deny("authz_unavailable:" + me.detail());
            }
            default -> {
                // fall through to the projection below
            }
        }
        if (me.superAdmin()) {
            return Decision.allow("super_admin");
        }
        if (!me.modules().isEmpty()) {
            String level = me.modules().get(MODULE);
            return level != null && USABLE_LEVELS.contains(level)
                    ? Decision.allow("module_" + level.toLowerCase(java.util.Locale.ROOT))
                    : Decision.deny(level == null ? "no_report_module" : "report_module_" + level.toLowerCase(java.util.Locale.ROOT));
        }
        return me.allowedModules().contains(MODULE)
                ? Decision.allow("legacy_allowed_modules")
                : Decision.deny("no_report_module");
    }

    /** The raw token never becomes a map key; a digest is enough to identify it. */
    static String tokenKey(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Test hook. */
    void invalidateAll() {
        cache.invalidateAll();
    }
}
