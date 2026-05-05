package com.serban.notify.repository;

import com.serban.notify.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    /**
     * Codex 019df86f post-impl bulgu #4 absorb: önceki @Query `Optional` döndü
     * ama 2 active version'da NonUniqueResultException riski vardı.
     * Spring Data derived query `findFirst...DESC` LIMIT 1 garanti.
     */
    Optional<NotificationTemplate> findFirstByTemplateIdAndLocaleAndActiveTrueOrderByVersionDesc(
        String templateId, String locale
    );

    /** Convenience alias kept for read-side service layer. */
    default Optional<NotificationTemplate> findActiveByTemplateIdAndLocale(
            String templateId, String locale) {
        return findFirstByTemplateIdAndLocaleAndActiveTrueOrderByVersionDesc(templateId, locale);
    }

    Optional<NotificationTemplate> findByTemplateIdAndVersionAndLocale(
        String templateId, Integer version, String locale
    );
}
