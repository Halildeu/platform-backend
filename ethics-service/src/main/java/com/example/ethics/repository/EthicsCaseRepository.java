package com.example.ethics.repository;
import com.example.ethics.model.EthicsCase; import java.time.Instant; import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EthicsCaseRepository extends JpaRepository<EthicsCase,UUID>{
    List<EthicsCase> findAllByOrgIdOrderByUpdatedAtDesc(UUID orgId);
    Optional<EthicsCase> findByIdAndOrgId(UUID id,UUID orgId);

    /**
     * ES-301A — stamp the art. 9(1)(b) acknowledgement, once, without disturbing the
     * operator's optimistic lock.
     *
     * <p>Written as one conditional statement rather than read-modify-write for two
     * reasons. It is atomic, so two concurrent first replies cannot both decide they
     * were first; and the row count tells the caller whether <em>this</em> call did the
     * stamping, which is what decides whether an audit entry is due — otherwise that
     * decision is a guess made from a possibly stale read.
     *
     * <p>It deliberately leaves {@code version} alone. Acknowledgement is a fact the
     * service records, not an edit an operator made, and bumping the version would
     * invalidate an ETag someone is already holding: reply to a reporter, then try to
     * close the case, and the close fails with a conflict that describes nothing real.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EthicsCase c set c.acknowledgedAt = :when, c.updatedAt = :when "
            + "where c.id = :caseId and c.acknowledgedAt is null")
    int markAcknowledged(@Param("caseId") UUID caseId, @Param("when") Instant when);
}
