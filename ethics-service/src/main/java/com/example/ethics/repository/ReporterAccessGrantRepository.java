package com.example.ethics.repository;
import com.example.ethics.model.ReporterAccessGrant; import jakarta.persistence.LockModeType; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface ReporterAccessGrantRepository extends JpaRepository<ReporterAccessGrant,UUID>{
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select grant from ReporterAccessGrant grant where grant.receiptId = :receiptId")
    Optional<ReporterAccessGrant> findLockedByReceiptId(@Param("receiptId") UUID receiptId);
}
