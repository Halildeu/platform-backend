package com.example.variant.repository;

import com.example.variant.model.GridVariantPreference;
import com.example.variant.model.PreferenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GridVariantPreferenceRepository extends JpaRepository<GridVariantPreference, UUID> {

    Optional<GridVariantPreference> findByUserIdAndVariant_Id(Long userId, UUID variantId);

    Optional<GridVariantPreference> findByUserIdAndGridIdAndPreferenceType(Long userId, String gridId, PreferenceType preferenceType);

    @Query("""
            select pref from GridVariantPreference pref
            where pref.userId = :userId
              and pref.gridId = :gridId
            """)
    List<GridVariantPreference> findAllByUserAndGrid(@Param("userId") Long userId,
                                                     @Param("gridId") String gridId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from GridVariantPreference pref where pref.variant.id = :variantId")
    void deleteAllByVariantId(@Param("variantId") UUID variantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from GridVariantPreference pref
            where pref.userId = :userId
              and pref.gridId = :gridId
              and pref.preferenceType = :preferenceType
            """)
    void deleteByUserAndGridAndType(@Param("userId") Long userId,
                                    @Param("gridId") String gridId,
                                    @Param("preferenceType") PreferenceType preferenceType);
}
