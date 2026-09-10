package com.example.ethics.repository;
import com.example.ethics.model.EthicsCase; import java.time.Instant; import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EthicsCaseRepository extends JpaRepository<EthicsCase,UUID>{
    List<EthicsCase> findAllByOrgIdOrderByUpdatedAtDesc(UUID orgId);

    /**
     * Every organisation that actually holds a case.
     *
     * <p>Derived from the data rather than from configuration on purpose. The cell's tenant
     * list also exists as a host→org map, but a tenant whose host entry is missing or stale
     * still has real cases and real legal deadlines; reading the map would let that tenant
     * fall out of the sweep silently. Measured on the live cell: 139 cases under one
     * organisation and 28 under a second.
     */
    @Query("select distinct c.orgId from EthicsCase c")
    List<UUID> findDistinctOrgIds();
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

    /**
     * ES-2 (#3271): the last-day net's worklist — open cases whose reporter has never
     * been written to and whose seventh day has begun. CLOSED is excluded because the
     * message path refuses closed cases anyway; the net must not fight that guard.
     */
    @Query("select c.id from EthicsCase c"
            + " where c.acknowledgedAt is null and c.status <> 'CLOSED'"
            + " and c.createdAt <= :cutoff order by c.createdAt")
    java.util.List<UUID> findUnacknowledgedOpenBefore(@Param("cutoff") Instant cutoff);

    /**
     * ES-301 (#882): every case that still owes something — an acknowledgement, or the
     * reporter's feedback. Ids only; the escalation sweeper re-reads each row under a lock
     * before deciding anything, because what it read here may already be stale.
     */
    @Query("select c.id from EthicsCase c"
            + " where c.acknowledgedAt is null or c.closedAt is null order by c.createdAt")
    java.util.List<UUID> findWithUnmetObligations();

    /**
     * The row, locked for the rest of the transaction — or an immediate refusal.
     *
     * <p>The escalation sweeper decides "still unacknowledged" and "still open" from this
     * read. Taking the row lock makes that decision and the acknowledgement/closure writes
     * serialise on the same row: whichever commits first, the other sees its result rather
     * than a snapshot taken a moment before.
     *
     * <p>Lock timeout 0 is {@code FOR UPDATE NOWAIT} on PostgreSQL: a row someone else holds
     * raises {@link org.springframework.dao.PessimisticLockingFailureException} at once
     * instead of queueing behind them. The sweeper defers that case to its next cycle. A
     * wait with no bound would let one held row stall every case behind it.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select c from EthicsCase c where c.id = :caseId")
    Optional<EthicsCase> lockById(@Param("caseId") UUID caseId);
}
