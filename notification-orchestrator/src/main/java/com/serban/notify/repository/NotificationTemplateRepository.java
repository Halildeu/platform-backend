package com.serban.notify.repository;

import com.serban.notify.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    @Query("SELECT t FROM NotificationTemplate t WHERE t.templateId = :templateId " +
           "AND t.locale = :locale AND t.active = true " +
           "ORDER BY t.version DESC")
    Optional<NotificationTemplate> findActiveByTemplateIdAndLocale(
        @Param("templateId") String templateId,
        @Param("locale") String locale
    );

    Optional<NotificationTemplate> findByTemplateIdAndVersionAndLocale(
        String templateId, Integer version, String locale
    );
}
