package com.example.variant.repository;

import com.example.variant.model.GridVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GridVariantRepository extends JpaRepository<GridVariant, UUID> {

    @Query("""
            select v from GridVariant v
            where v.gridId = :gridId
              and v.userId = :userId
            order by v.sortOrder asc, v.createdAt asc
            """)
    List<GridVariant> findPersonalVariants(@Param("userId") Long userId, @Param("gridId") String gridId);

    @Query("""
            select v from GridVariant v
            where v.gridId = :gridId
              and (v.isGlobal = true or v.userId = :userId)
            order by v.sortOrder asc, v.createdAt asc
            """)
    List<GridVariant> findVisibleVariants(@Param("userId") Long userId, @Param("gridId") String gridId);

    @Query("""
            select v from GridVariant v
            where v.gridId = :gridId
            order by v.sortOrder asc, v.createdAt asc
            """)
    List<GridVariant> findAllByGridIdOrdered(@Param("gridId") String gridId);

    Optional<GridVariant> findByIdAndUserId(UUID id, Long userId);

    @Query("""
            select coalesce(max(v.sortOrder), -1) from GridVariant v
            where v.gridId = :gridId
              and v.isGlobal = false
              and v.userId = :userId
            """)
    Integer findMaxSortOrderForUser(@Param("userId") Long userId, @Param("gridId") String gridId);

    @Query("""
            select coalesce(max(v.sortOrder), -1) from GridVariant v
            where v.gridId = :gridId
              and v.isGlobal = true
            """)
    Integer findMaxSortOrderForGlobal(@Param("gridId") String gridId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update GridVariant v
            set v.isDefault = false
            where v.userId = :userId
              and v.gridId = :gridId
              and (:excludeId is null or v.id <> :excludeId)
            """)
    void clearDefaultForGrid(@Param("userId") Long userId,
                             @Param("gridId") String gridId,
                             @Param("excludeId") UUID excludeId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update GridVariant v
            set v.isGlobalDefault = false
            where v.isGlobal = true
              and v.gridId = :gridId
              and (:excludeId is null or v.id <> :excludeId)
            """)
    void clearGlobalDefaultForGrid(@Param("gridId") String gridId,
                                   @Param("excludeId") UUID excludeId);
}
