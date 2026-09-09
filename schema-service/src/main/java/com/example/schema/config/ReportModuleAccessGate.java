package com.example.schema.config;

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
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;

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
 * <p>Fail-closed: a refused token, an unreachable permission-service, a non-200,
 * an unparsable or identity-less body all deny with the reason recorded.
 *
 * <p>Memo: a decision is reused for the same token only while the platform
 * authorization revision it was computed under is still current. The revision
 * is read (with the caller's bearer — the endpoint is authenticated) and
 * remembered for a short window; if it cannot be read, the memo is bypassed and
 * a fresh {@code /authz/me} decides — a stale revision is never a licence to
 * keep saying yes. A revoke is therefore honoured within the revision window.
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
    private final boolean enabled;
    private final boolean devPassThrough;
    private final Cache<String, Cached> cache;
    private final long revisionMemoNanos;
    private final LongSupplier nanoTime;

    private final Object revisionLock = new Object();
    private long memoRevision;
    private long memoAtNanos;
    private boolean memoValid;

    public ReportModuleAccessGate(AuthzMeClient client, boolean enabled, boolean devPassThrough,
                                  Duration cacheTtl, Duration revisionMemo) {
        this(client, enabled, devPassThrough, cacheTtl, revisionMemo, System::nanoTime);
    }

    ReportModuleAccessGate(AuthzMeClient client, boolean enabled, boolean devPassThrough,
                           Duration cacheTtl, Duration revisionMemo, LongSupplier nanoTime) {
        this.client = client;
        this.enabled = enabled;
        this.devPassThrough = devPassThrough;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl == null || cacheTtl.isNegative() ? Duration.ofSeconds(10) : cacheTtl)
                .maximumSize(10_000)
                .build();
        this.revisionMemoNanos = (revisionMemo == null || revisionMemo.isNegative()
                ? Duration.ofSeconds(5) : revisionMemo).toNanos();
        this.nanoTime = nanoTime;
    }

    public Decision decide(String bearerToken) {
        if (!enabled) {
            return devPassThrough ? Decision.allow("gate_disabled_dev") : Decision.deny("gate_disabled");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            return Decision.deny("no_token");
        }
        String key = tokenKey(bearerToken);
        OptionalLong revision = currentRevision(bearerToken);
        if (revision.isPresent()) {
            Cached cached = cache.getIfPresent(key);
            if (cached != null && cached.revision() == revision.getAsLong()) {
                return cached.decision();
            }
        }
        Decision fresh = evaluate(client.fetch(bearerToken));
        if (revision.isPresent()) {
            cache.put(key, new Cached(fresh, revision.getAsLong()));
        } else {
            cache.invalidate(key);
        }
        return fresh;
    }

    /** Memoised revision; a failed read is never substituted with the old value. */
    private OptionalLong currentRevision(String bearerToken) {
        long now = nanoTime.getAsLong();
        synchronized (revisionLock) {
            if (memoValid && now - memoAtNanos < revisionMemoNanos) {
                return OptionalLong.of(memoRevision);
            }
        }
        OptionalLong fetched = client.fetchVersion(bearerToken);
        synchronized (revisionLock) {
            if (fetched.isPresent()) {
                memoRevision = fetched.getAsLong();
                memoAtNanos = now;
                memoValid = true;
            } else {
                memoValid = false;
            }
        }
        return fetched;
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
            if (level != null && USABLE_LEVELS.contains(level)) {
                return Decision.allow("module_" + level.toLowerCase(Locale.ROOT));
            }
            return Decision.deny(level == null ? "no_report_module" : "report_module_" + level.toLowerCase(Locale.ROOT));
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
        synchronized (revisionLock) {
            memoValid = false;
        }
    }
}
