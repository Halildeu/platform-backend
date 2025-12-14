package com.example.variant.repository;

import com.example.variant.model.VariantVisibility;
import com.example.variant.model.VariantVisibility.VisibilityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VariantVisibilityRepository extends JpaRepository<VariantVisibility, Long> {

    @Query("select v from VariantVisibility v join fetch v.variant gv " +
            "where gv.gridId = :gridId and v.visibilityType = :type and v.refId is null")
    List<VariantVisibility> findGlobalByGrid(@Param("gridId") String gridId,
                                             @Param("type") VisibilityType type);

    @Query("select v from VariantVisibility v join fetch v.variant gv " +
            "where gv.gridId = :gridId and v.visibilityType = :type and v.refId = :refId")
    List<VariantVisibility> findByGridAndTypeAndRef(@Param("gridId") String gridId,
                                                    @Param("type") VisibilityType type,
                                                    @Param("refId") String refId);

    @Query("select v from VariantVisibility v join fetch v.variant gv where gv.id = :variantId")
    List<VariantVisibility> findAllByVariantId(@Param("variantId") UUID variantId);
}
