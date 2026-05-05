package com.serban.notify.repository;

import com.serban.notify.domain.ProviderConfigHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProviderConfigHistoryRepository extends JpaRepository<ProviderConfigHistory, Long> {

    List<ProviderConfigHistory> findByProviderKeyAndEnvironmentOrderByDeactivatedAtDesc(
        String providerKey, String environment
    );
}
