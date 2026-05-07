package com.example.report.schema.consumer;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 8c — Schema existence check public API
 * ({@link com.example.report.schema.SchemaTruthLookupPolicy#RUNTIME_STRICT_EXISTENCE} only).
 *
 * <p>Spec §2.1 + capability matrix §2.1.2: bu service yalnızca runtime
 * fail-closed strict path kullanır — Phase 2 Program 2 (PR #92)
 * {@code TenantBoundaryGuard} consumer'ıdır.
 *
 * <p>Tier 1 fail-soft → exception propagate → caller (TenantBoundaryGuard)
 * 503 {@code schema_resolver_miss} üretir; Tier 2/3 fallback YASAK
 * (silent fallback Plan §1 v3 prensibi gereği yasak).
 */
@Component
public class SchemaExistsService {

    private static final Logger log = LoggerFactory.getLogger(SchemaExistsService.class);

    private final SchemaTruthService schemaTruthService;

    public SchemaExistsService(SchemaTruthService schemaTruthService) {
        this.schemaTruthService = schemaTruthService;
    }

    /**
     * Schema existence check — fail-closed runtime strict.
     *
     * <p>Caller {@code ctx.policy()} {@link com.example.report.schema.SchemaTruthLookupPolicy#RUNTIME_STRICT_EXISTENCE}
     * olmalı; aksi halde IllegalArgumentException (capability matrix violation).
     *
     * @param ctx          lookup context (policy=RUNTIME_STRICT_EXISTENCE)
     * @param schemaName   workcube schema adı
     * @return true if Tier 1 returned a snapshot for the schema
     * @throws RuntimeException Tier 1 fail-soft (caller fail-closed 503)
     */
    public boolean exists(SchemaTruthLookupContext ctx, String schemaName) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        if (ctx.policy() != com.example.report.schema.SchemaTruthLookupPolicy.RUNTIME_STRICT_EXISTENCE) {
            throw new IllegalArgumentException(
                    "SchemaExistsService requires RUNTIME_STRICT_EXISTENCE policy; got "
                            + ctx.policy());
        }
        log.debug("SchemaExistsService.exists strict: schema={} consumer={}",
                schemaName, ctx.consumer());
        Optional<SchemaSnapshot> snapshot = schemaTruthService.fetchSnapshot(ctx, schemaName);
        // Codex iter-1 §1 absorb: schema-service `/snapshot` bilinmeyen schema
        // için 404 üretmez; SchemaSnapshotService 0 row → boş `tables` ile 200
        // döner. `snapshot.isPresent()` tek başına false-positive; tables'ın
        // schema match'iyle dolu olması ZORUNLU.
        if (snapshot.isEmpty()) {
            return false;
        }
        return snapshot.get().tables().values().stream()
                .anyMatch(t -> schemaName.equals(t.schema()));
    }
}
