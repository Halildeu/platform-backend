package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHardwareInventoryNetworkInterface;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * BE-022 — NIC-per-snapshot read access. In practice the snapshot's
 * {@code @OneToMany List<EndpointHardwareInventoryNetworkInterface>}
 * collection is the canonical read path; this repository exists for
 * tenant-scoped admin queries and tests that need direct row access.
 */
@Repository
public interface EndpointHardwareInventoryNetworkInterfaceRepository
        extends JpaRepository<EndpointHardwareInventoryNetworkInterface, UUID> {
}
