package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHardwareInventoryDisk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * BE-022 — disks-per-snapshot read access. In practice the
 * snapshot's {@code @OneToMany List<EndpointHardwareInventoryDisk>}
 * collection is the canonical read path; this repository exists for
 * tenant-scoped admin queries and tests that need direct row access.
 */
@Repository
public interface EndpointHardwareInventoryDiskRepository
        extends JpaRepository<EndpointHardwareInventoryDisk, UUID> {
}
