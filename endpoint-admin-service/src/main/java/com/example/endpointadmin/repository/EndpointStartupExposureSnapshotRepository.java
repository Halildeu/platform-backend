package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointStartupExposureSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BE — read/append access for startup-exposure snapshots (Faz 22.5,
 * AG-040-be). Mirrors AG-039-be {@code EndpointServicesSnapshotRepository}.
 * All reads JPQL (NEVER native) — schema-qualified by Hibernate from
 * entity mapping, immune to BE-022Q lower(bytea) class regression.
 */
@Repository
public interface EndpointStartupExposureSnapshotRepository
        extends JpaRepository<EndpointStartupExposureSnapshot, UUID>,
        EndpointStartupExposureSnapshotRepositoryCustom {

    Optional<EndpointStartupExposureSnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    Optional<EndpointStartupExposureSnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    Optional<EndpointStartupExposureSnapshot>
            findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, String payloadHashSha256);

    Page<EndpointStartupExposureSnapshot>
            findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, Pageable pageable);

    Page<EndpointStartupExposureSnapshot>
            findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId, Pageable pageable);

    @Query("""
            select s
            from EndpointStartupExposureSnapshot s
            where s.tenantId = :tenantId
              and s.deviceId = :deviceId
              and s.payloadHashSha256 = cast(:payloadHash as string)
            order by s.collectedAt desc, s.createdAt desc, s.id desc
            """)
    List<EndpointStartupExposureSnapshot> findByTenantDeviceAndPayloadHash(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("payloadHash") String payloadHash,
            Pageable pageable);
}
