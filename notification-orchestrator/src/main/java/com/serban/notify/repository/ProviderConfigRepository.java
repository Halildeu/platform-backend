package com.serban.notify.repository;

import com.serban.notify.domain.ProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderConfigRepository extends JpaRepository<ProviderConfig, Long> {

    @Query("SELECT pc FROM ProviderConfig pc WHERE pc.providerKey = :providerKey " +
           "AND pc.environment = :env AND pc.active = true")
    Optional<ProviderConfig> findActiveByProviderAndEnv(
        @Param("providerKey") String providerKey,
        @Param("env") String environment
    );

    @Query("SELECT pc FROM ProviderConfig pc WHERE pc.channel = :channel " +
           "AND pc.environment = :env AND pc.active = true " +
           "ORDER BY pc.priority ASC")
    List<ProviderConfig> findActiveByChannelOrderByPriority(
        @Param("channel") String channel,
        @Param("env") String environment
    );
}
