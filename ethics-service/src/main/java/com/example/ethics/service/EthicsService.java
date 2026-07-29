package com.example.ethics.service;

import com.example.ethics.api.EthicsDtos.*;
import com.example.ethics.config.EthicsProperties;
import com.example.ethics.directory.UserDirectoryClient;
import com.example.ethics.model.*;
import com.example.ethics.notification.NotificationOutboxPublisher;
import com.example.ethics.repository.*;
import com.example.ethics.model.CaseParticipant;
import com.example.ethics.security.StaffContext;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.ParticipantHandles;
import com.example.ethics.security.PublicTenantResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EthicsService {
    private final EthicsProperties properties;
    private final SecretHasher secrets;
    private final EthicsCaseRepository cases;
    private final EthicsReportRepository reports;
    private final ReporterAccessGrantRepository grants;
    private final EthicsMessageRepository messages;
    private final MailboxSessionRepository sessions;
    private final AuditOutboxRepository audit;
    private final IntakeIdempotencyRepository idempotency;
    private final EthicsAuthorization authorization;
    private final TransactionKeyLock transactionLocks;
    private final PublicIntakeSanitizer publicIntakeSanitizer;
    private final ObjectMapper auditMapper;
    private final PublicTenantResolver tenantResolver;
    private final NotificationOutboxPublisher notifications;
    private final CaseParticipantRepository participants;
    private final String dummyMailboxHash;

    private final ParticipantHandles handles;
    private final UserDirectoryClient directory;

    public EthicsService(EthicsProperties properties, SecretHasher secrets, EthicsCaseRepository cases,
            EthicsReportRepository reports, ReporterAccessGrantRepository grants, EthicsMessageRepository messages,
            MailboxSessionRepository sessions, AuditOutboxRepository audit, IntakeIdempotencyRepository idempotency,
            EthicsAuthorization authorization, TransactionKeyLock transactionLocks,
            PublicIntakeSanitizer publicIntakeSanitizer, ObjectMapper auditMapper,
            PublicTenantResolver tenantResolver,
            NotificationOutboxPublisher notifications,
            CaseParticipantRepository participants,
            ParticipantHandles handles,
            UserDirectoryClient directory) {
        this.handles=handles;
        this.directory=directory;
        this.properties=properties;this.secrets=secrets;this.cases=cases;this.reports=reports;this.grants=grants;
        this.messages=messages;this.sessions=sessions;this.audit=audit;this.idempotency=idempotency;
        this.authorization=authorization;
        this.transactionLocks=transactionLocks;
        this.publicIntakeSanitizer=publicIntakeSanitizer;
        this.auditMapper=auditMapper;
        this.tenantResolver=tenantResolver;
        this.notifications=notifications;
        this.participants=participants;
        // Missing receipts, wrong channels and locked grants must spend the
        // same PBKDF work as a wrong secret. This process-local value is never
        // persisted or exposed and exists solely to close the timing oracle.
        this.dummyMailboxHash=secrets.hash(secrets.newSecret(),properties.secretIterations());
    }

    @Transactional
    public CreateReportResponse createReport(String channel, String key, CreateReportRequest request) {
        if (key == null || key.isBlank() || key.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required.");
        publicIntakeSanitizer.validateReport(request);
        String normalizedChannel = normalizeChannel(channel);
        // Faz 35 ES multi-tenant — the owning org is resolved from the inbound
        // host (threaded here as `channel`); unmapped hosts fall back to the
        // default public-org-id, preserving single-tenant behaviour.
        UUID orgId = tenantResolver.resolve(channel);
        if (request.mode()!=ReportMode.ANONYMOUS) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"REPORT_MODE_NOT_ENABLED");
        transactionLocks.lock("intake\n"+orgId+"\n"+normalizedChannel+"\n"+key);
        String requestHash = secrets.sha256(canonicalField(request.mode().name())
                +canonicalField(request.category().name())
                +canonicalField(request.subject())
                +canonicalField(request.description())
                +canonicalField(request.locale())
                +canonicalField(request.noticeVersion())
                +canonicalField(secrets.sha256(request.accessSecret())));
        Optional<IntakeIdempotency> prior = idempotency.findByOrgIdAndChannelAndIdempotencyKey(orgId, normalizedChannel, key);
        if (prior.isPresent()) {
            if (!prior.get().getRequestHash().equals(requestHash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            // A replay never re-discloses the raw access secret. The user must retain the original receipt.
            return new CreateReportResponse(prior.get().getReceiptId(), prior.get().getCreatedAt(), "/mailbox", true);
        }
        Instant now=Instant.now(); UUID caseId=UUID.randomUUID(); UUID reportId=UUID.randomUUID(); UUID receiptId=UUID.randomUUID();
        cases.save(new EthicsCase(caseId,orgId,now));
        reports.save(new EthicsReport(reportId,caseId,request.mode().name(),request.category().name(),request.subject(),request.description(),request.locale(),request.noticeVersion(),now));
        grants.save(new ReporterAccessGrant(receiptId,caseId,normalizedChannel,secrets.hash(request.accessSecret(),properties.secretIterations()),now));
        audit.save(new AuditOutbox(UUID.randomUUID(),orgId,caseId,"ethics.report.created",
                encodeAuditPayload(Map.of(
                        "mode", request.mode().name(),
                        "category", request.category().name(),
                        "channel", normalizedChannel,
                        "noticeVersion", request.noticeVersion())),
                now));
        notifications.enqueue(orgId, NotificationOutboxPublisher.NEW_REPORT, now);
        idempotency.save(new IntakeIdempotency(UUID.randomUUID(),orgId,normalizedChannel,key,requestHash,receiptId,now));
        return new CreateReportResponse(receiptId,now,"/mailbox",false);
    }

    private static String canonicalField(String value) {
        // A decimal byte/character count plus ':' is unambiguous even when a
        // user-controlled field contains newlines or delimiter text.
        return value.length()+":"+value;
    }

    @Transactional(noRollbackFor=ResponseStatusException.class)
    public SessionGrant openMailbox(String channel, MailboxLoginRequest request) {
        String normalizedChannel=normalizeChannel(channel);
        ReporterAccessGrant grant=grants.findLockedByReceiptId(request.receiptId()).orElse(null);
        Instant now=Instant.now();
        boolean secretMatches=secrets.verify(request.accessSecret(),grant==null?dummyMailboxHash:grant.getSecretHash());
        if(grant==null) throw genericMailboxDeny();
        if(!grant.getChannel().equals(normalizedChannel)) throw genericMailboxDeny();
        if (grant.getLockedUntil()!=null && grant.getLockedUntil().isAfter(now)) throw genericMailboxDeny();
        if (!secretMatches) { grant.failed(now); throw genericMailboxDeny(); }
        grant.verified(); String token=secrets.newSecret(); Instant expires=now.plus(properties.mailboxSessionTtl());
        sessions.save(new MailboxSession(secrets.sha256(token),grant.getCaseId(),normalizedChannel,expires,now));
        return new SessionGrant(token,expires);
    }

    @Transactional(readOnly=true)
    public MailboxViewResponse reporterMailbox(String channel,String token) {
        UUID caseId=caseForSession(channel,token);
        EthicsCase item=cases.findById(caseId).orElseThrow(EthicsService::genericMailboxDeny);
        List<MessageResponse> visible=messages.findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(caseId,List.of("REPORTER_VISIBLE"))
                .stream().map(EthicsService::messageResponse).toList();
        return new MailboxViewResponse(reporterVisibleStatus(item.getStatus()),visible,
                item.getCreatedAt(),item.getAcknowledgedAt());
    }

    @Transactional
    public MessageResponse reporterReply(String channel,String token,String key,MessageRequest request) {
        publicIntakeSanitizer.validateMessage(request);
        UUID caseId=caseForSession(channel,token);
        // Faz 35 ES multi-tenant — a reply belongs to the case's own org, which
        // was fixed at intake time. Read it from the case (authoritative) rather
        // than re-resolving from the host, so an audit stamp can never drift if
        // the host→org map later changes.
        EthicsCase caseRow=cases.findById(caseId).orElseThrow(EthicsService::genericMailboxDeny);
        return createMessage(caseId,"REPORTER","REPORTER_VISIBLE",key,request.body(),caseRow.getOrgId(),"reporter",
                () -> ensureOpen(caseRow));
    }

    /**
     * The cases this staff member may see, with enough on each row to choose between them.
     *
     * <p>Everything here is resolved for the list rather than per case. The old shape asked
     * the policy engine three questions and the database two per row, so 138 cases cost 414
     * authorization round trips and 276 queries to produce 6 KB — nearly six seconds, growing
     * with the caseload rather than with the answer. The gate now costs three calls total and
     * the two lookups one query each.
     */
    @Transactional(readOnly=true)
    public List<CaseSummary> listCases(StaffContext staff) {
        var gate = authorization.gateFor(staff, "case_viewer");
        var visible = cases.findAllByOrgIdOrderByUpdatedAtDesc(staff.orgId()).stream()
                .filter(item -> gate.allows(item.getId()))
                .toList();
        if (visible.isEmpty()) return List.of();
        var ids = visible.stream().map(EthicsCase::getId).toList();
        Map<UUID,EthicsReport> reportByCase = reports.findAllByCaseIdIn(ids).stream()
                .collect(Collectors.toMap(EthicsReport::getCaseId, r -> r, (first,ignored) -> first));
        Map<UUID,Integer> participantsByCase = participants.countByCaseIdIn(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).intValue()));
        return visible.stream()
                .map(item -> summary(item, reportByCase.get(item.getId()),
                        participantsByCase.getOrDefault(item.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly=true)
    public CaseDetail caseDetail(StaffContext staff,UUID caseId) {
        EthicsCase item=requireCase(staff,caseId,"case_viewer"); EthicsReport report=reports.findByCaseId(caseId).orElseThrow();
        List<MessageResponse> all=messages.findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(caseId,List.of("REPORTER_VISIBLE","INTERNAL"))
                .stream().map(EthicsService::messageResponse).toList();
        var onCase=participants.findAllByCaseIdOrderByCreatedAtAsc(caseId);
        boolean named=!onCase.isEmpty();
        return new CaseDetail(item.getId(),item.getStatus(),named?null:item.getAssignedTo(),item.getVersion(),report.getMode(),report.getCategory(),report.getSubject(),report.getNarrative(),all,
                item.getAcknowledgedAt(),item.getOutcome(),item.getClosedAt(),
                item.getCreatedAt(),item.getUpdatedAt(),onCase.size());
    }

    /**
     * ES-301A — move a case through the lifecycle, or name who is on it.
     *
     * <p>Status used to be a string this method accepted from any of three values with no
     * rule about which order they could come in: a closed case could be sent back to
     * {@code NEW}, which discarded the fact that it had ever concluded along with the
     * finding it concluded on. {@link CaseLifecycle} now owns which moves exist, and
     * closing carries a finding or does not happen at all — the reason the live cell had
     * 160 cases and not one conclusion is that closing meant writing a word that recorded
     * nothing, so nobody did.
     */
    @Transactional
    public CaseSummary updateCase(StaffContext staff,UUID caseId,String ifMatch,UpdateCaseRequest request) {
        EthicsCase item=cases.findByIdAndOrgId(caseId,staff.orgId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Case not found."));
        if(request.status()!=null) authorization.require(staff,"case_handler",caseId);
        // ES-203 slice 2 — naming someone goes through POST /participants, which takes a
        // case-scoped handle the authorization plane can check. This path refuses the old
        // free-text knob outright: silently ignoring it would let a caller believe it had
        // assigned somebody while the case stayed unowned.
        if(request.assignedTo()!=null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_ASSIGNED_TO_RETIRED");
        if(request.status()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_UPDATE_EMPTY");
        long expected=parseVersion(ifMatch);
        if(item.getVersion()!=expected) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"CASE_VERSION_MISMATCH");
        String reopenReason=null;
        String closingMessage=null;
        if(request.status()!=null&&!request.status().isBlank()) {
            String from=item.getStatus();
            String to=CaseLifecycle.canonicalStatus(request.status());
            if(to==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_STATUS_INVALID");
            if(!CaseLifecycle.isTransitionAllowed(from,to))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"CASE_TRANSITION_NOT_ALLOWED");

            String outcome=null;
            if(CaseLifecycle.CLOSED.equals(to)) {
                if(request.outcome()==null||request.outcome().isBlank())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_OUTCOME_REQUIRED");
                outcome=CaseLifecycle.canonicalOutcome(request.outcome());
                if(outcome==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_OUTCOME_INVALID");
                // ES-301B — art. 9(1)(f) asks for feedback to the reporting person, not a
                // finding filed internally. Recording the outcome and telling the reporter are
                // one act here for the same reason acknowledgement is: kept apart, the service
                // could report having concluded a case whose reporter was told nothing.
                if(request.closingMessage()==null||request.closingMessage().isBlank())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_CLOSING_MESSAGE_REQUIRED");
                closingMessage=request.closingMessage();
            } else {
                if(request.closingMessage()!=null&&!request.closingMessage().isBlank())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_CLOSING_MESSAGE_NOT_APPLICABLE");
                if(request.outcome()!=null&&!request.outcome().isBlank())
                    // An outcome on a case that is not closing would be a finding nobody ever
                    // stands behind: it would sit on an open case and read as decided.
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_OUTCOME_NOT_APPLICABLE");
            }
            if(CaseLifecycle.isReopen(from,to)) {
                // Reopening discards a recorded conclusion. That needs a stated reason,
                // because the audit trail is otherwise left showing a finding that
                // vanished with nothing to explain it.
                if(request.reason()==null||request.reason().isBlank())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_REOPEN_REASON_REQUIRED");
                if(request.reason().length()>500)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_REOPEN_REASON_TOO_LONG");
                reopenReason=request.reason();
            }
            item.transitionTo(to,outcome,Instant.now());
        }
        cases.saveAndFlush(item);
        if(closingMessage!=null) {
            // After the case is saved, not before: writing the message stamps the
            // acknowledgement, and that statement clears the persistence context — which
            // would detach `item` mid-transition. Ordering inside the transaction is free;
            // both land together or neither does.
            //
            // The idempotency key comes from the version the caller already had to present,
            // so a retried close cannot leave the reporter reading the same message twice.
            createMessage(caseId,"STAFF","REPORTER_VISIBLE","closure-"+expected,
                    closingMessage,staff.orgId(),secrets.sha256(staff.subject()),()->{});
        }
        Map<String,Object> payload=new LinkedHashMap<>(Map.of(
                "status", item.getStatus(),
                // Was `assigned` = "the legacy label is non-empty", which recorded whether
                // someone had typed a word. What an auditor needs is whether a principal is
                // on the case, and that lives in the participant table.
                "participantCount", participants.findAllByCaseIdOrderByCreatedAtAsc(caseId).size(),
                "actorHash", secrets.sha256(staff.subject())));
        if(item.getOutcome()!=null) payload.put("outcome", item.getOutcome());
        if(reopenReason!=null) payload.put("reopenReason", reopenReason);
        audit.save(new AuditOutbox(UUID.randomUUID(),staff.orgId(),caseId,
                reopenReason!=null?"ethics.case.reopened":"ethics.case.updated",
                encodeAuditPayload(payload), Instant.now()));
        return summary(item);
    }

    /**
     * A staff message. When it is the first one the reporter can see, it is also the
     * acknowledgement EU 2019/1937 art. 9(1)(b) requires within seven days — so the
     * timestamp is taken here rather than exposed as something an operator can set.
     * Tying the record to the act means the service cannot report having acknowledged
     * a report whose reporter was never actually written to. Internal notes do not
     * count: the reporter never sees them.
     */
    @Transactional
    public MessageResponse staffReply(StaffContext staff,UUID caseId,String key,MessageRequest request,boolean internal) {
        requireCase(staff,caseId,"case_handler");
        return createMessage(caseId,"STAFF",internal?"INTERNAL":"REPORTER_VISIBLE",key,request.body(),staff.orgId(),secrets.sha256(staff.subject()),
                () -> ensureOpen(cases.findByIdAndOrgId(caseId,staff.orgId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Case not found."))));
    }

    /**
     * ES-203 — the subjects this org may assign to a case.
     *
     * <p>Gated on {@code case_triager} rather than {@code case_viewer}: seeing a case is
     * not a reason to be handed the list of everyone who works ethics here. The caller
     * who can assign is the caller who needs it.
     *
     * <p>An unreachable policy engine produces 503, not an empty list. "Nobody may be
     * assigned" and "we could not find out" are opposite facts, and a manager shown the
     * first while the second is true would conclude the team is empty.
     */
    @Transactional(readOnly=true)
    public List<AssignableStaffEntry> assignableStaff(StaffContext staff,UUID caseId) {
        requireCase(staff,caseId,"case_triager");
        var result=authorization.assignableStaff(staff.orgId());
        if(!result.available())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"ASSIGNABLE_STAFF_UNAVAILABLE");
        // ES-203/C — this is a decision surface, so the directory is fail-closed: a manager
        // choosing between unnamed rows is exactly the wrong-person assignment ES-203 exists
        // to prevent. The two 503 codes stay distinct so an operator can tell which
        // dependency failed without either error naming a person.
        var names=directory.resolve(result.subjects());
        if(!names.available())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"STAFF_DIRECTORY_UNAVAILABLE");
        // Handles, not subjects. The browser never learns who these people are in any
        // sense that survives this case: the same colleague on another case has an
        // unrelated handle, so two of them cannot be joined into "the same person".
        // The display name is the one deliberate exception — an authorized triager reads
        // it live; what stays free of correlation keys is everything durable (log, audit,
        // export, backup), none of which this response touches.
        //
        // A subject the directory answered for but does not know (deleted or never
        // provisioned) is excluded rather than shown nameless: "assignable" means present
        // in BOTH the policy engine and the directory. A stale OpenFGA tuple for a
        // deleted person must not reappear here as a selectable ghost.
        return result.subjects().stream()
                .filter(subject->names.names().containsKey(subject))
                .map(subject->new AssignableStaffEntry(
                        handles.mint(staff.orgId(),caseId,subject),
                        names.names().get(subject)))
                .sorted(Comparator.comparing(AssignableStaffEntry::displayName,String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AssignableStaffEntry::handle))
                .toList();
    }

    /**
     * ES-203 / B+ slice 1 — record that a named person is on this case.
     *
     * <p>Replaces naming someone by label. {@code ethics_cases.assigned_to} is free text that has
     * held values like {@code jbjb}; nothing about it can be handed to an authorization check,
     * which is why third-party conflict, pre-disclosure routing and reveal-approver exclusion all
     * had nothing to name. The subject here is the same Keycloak subject the policy engine uses.
     *
     * <p><b>A participant row grants nothing.</b> Access still comes from product membership minus
     * {@code conflicted}/{@code recused} — exactly as before. Assignment and authorization stay
     * separate facts, which is also why no OpenFGA tuple is written: with nothing to keep in sync
     * across two stores, there is no window in which one says yes and the other no. The plan this
     * slice came from assumed a dual write and therefore an outbox; measuring what a participant
     * actually needs to do removed the requirement rather than solving it.
     *
     * <p>Three things are verified server-side before the row exists:
     * <ul>
     *   <li>the caller may assign on this case ({@code case_triager});</li>
     *   <li>the target is a member of <em>this</em> org's product — a subject from another tenant
     *       is refused, so assignment cannot be used to reach across orgs;</li>
     *   <li>the role is one the authorization model defines.</li>
     * </ul>
     */
    @Transactional
    public void addParticipant(StaffContext staff,UUID caseId,String handle,String role) {
        requireCase(staff,caseId,"case_triager");
        if(handle==null||handle.isBlank()||handle.length()>128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PARTICIPANT_HANDLE_INVALID");
        if(!CaseParticipant.ALLOWED_ROLES.contains(role))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PARTICIPANT_ROLE_INVALID");
        // Resolution is a search, not a lookup: the handle is one-way and there is no
        // reverse table to consult. Recomputing it for each member the case could
        // legitimately be assigned to costs one HMAC apiece and leaves nothing behind
        // that a backup or a curious operator could read.
        var members=authorization.assignableStaff(staff.orgId());
        if(!members.available())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"ASSIGNABLE_STAFF_UNAVAILABLE");
        String targetSubject=members.subjects().stream()
                .filter(subject->handles.matches(handle,staff.orgId(),caseId,subject))
                .findFirst()
                .orElseThrow(ParticipantHandles::unknown);
        // Membership is re-established here rather than trusted from the enumeration:
        // the list was read a moment ago, and an assignment must rest on what is true
        // now. It also keeps the guarantee if the list is ever cached.
        if(!authorization.isProductMember(targetSubject,staff.orgId()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PARTICIPANT_NOT_IN_ORG");
        if(participants.findByCaseIdAndKcSubjectAndRole(caseId,targetSubject,role).isPresent()) return;
        Instant now=Instant.now();
        participants.save(new CaseParticipant(UUID.randomUUID(),caseId,staff.orgId(),targetSubject,role,now,
                secrets.sha256(staff.subject())));
        audit.save(new AuditOutbox(UUID.randomUUID(),staff.orgId(),caseId,"ethics.case.participant.added",
                encodeAuditPayload(Map.of(
                        "actorHash", secrets.sha256(staff.subject()),
                        "targetHash", secrets.sha256(targetSubject),
                        "role", role)),
                now));
    }

    /**
     * Who is on this case. The caller already passes the case gate.
     *
     * <p>ES-203/C — this is a display surface, so the directory degrades instead of
     * failing closed: an unreachable name service must not make the participants of a
     * case unknowable. A {@code null} display name renders as "unresolved", which is
     * honest; an empty list would claim nobody is on the case, which is not.
     */
    @Transactional(readOnly=true)
    public List<CaseParticipantView> listParticipants(StaffContext staff,UUID caseId) {
        requireCase(staff,caseId,"case_viewer");
        var rows=participants.findAllByCaseIdOrderByCreatedAtAsc(caseId);
        var names=directory.resolve(rows.stream().map(CaseParticipant::getKcSubject).distinct().toList());
        return rows.stream()
                .map(p -> new CaseParticipantView(
                        handles.mint(staff.orgId(),caseId,p.getKcSubject()),
                        names.names().get(p.getKcSubject()),
                        p.getRole(),p.getCreatedAt())).toList();
    }

    /**
     * What has happened to this case, oldest first.
     *
     * <p>Every one of these has been recorded since the first day and none of it could be
     * read: the screen showed the case's current state and its messages, so "who moved
     * this to investigating, and when" had no answer where the question gets asked. A
     * handler inheriting a case could see where it ended up and not how it got there.
     *
     * <p>Actors are resolved the way ES-203 resolves everyone else — by recomputing, not by
     * looking up. The trail stores a one-way hash of the subject and there is deliberately
     * no reverse table, so each of this org's own members is hashed and matched. Someone
     * who has since left the product does not resolve, and that entry says the actor is
     * unknown rather than inventing one.
     *
     * <p>A display surface, so it degrades: an unreachable name directory costs the names,
     * not the history. The sequence of what happened is the part that must not disappear.
     */
    @Transactional(readOnly=true)
    public List<CaseTimelineEntry> caseTimeline(StaffContext staff,UUID caseId) {
        requireCase(staff,caseId,"case_viewer");
        // Case events and evidence events both belong to this case's history, but they are
        // filed under different aggregate ids — see AuditOutboxRepository#findCaseHistory.
        var rows=audit.findCaseHistory(staff.orgId(),caseId);
        if(rows.isEmpty()) return List.of();

        // hash -> subject for this org's members only. Building it costs one hash apiece and
        // leaves nothing behind; a stored reverse map would be the correlation table ES-203
        // exists to avoid.
        Map<String,String> subjectByHash=new LinkedHashMap<>();
        var members=authorization.assignableStaff(staff.orgId());
        if(members.available())
            for(String subject:members.subjects()) subjectByHash.put(secrets.sha256(subject),subject);
        var names=directory.resolve(List.copyOf(subjectByHash.values()));

        return rows.stream().map(row -> {
            String actorSubject=null;
            String detail=null;
            // Fail-closed: NONE is asserted only after the payload was read and shown to carry
            // no actor. An unreadable payload keeps UNRESOLVED, because "nobody acted" is a
            // claim about the case and a record we cannot parse does not support it.
            var actorState=TimelineActorState.UNRESOLVED;
            try {
                var payload=auditMapper.readTree(row.getPayload());
                if(payload.hasNonNull("actorHash"))
                    actorSubject=subjectByHash.get(payload.get("actorHash").asText());
                else
                    actorState=TimelineActorState.NONE;
                detail=timelineDetail(row.getEventType(),payload);
            } catch(RuntimeException|com.fasterxml.jackson.core.JsonProcessingException unreadable) {
                // A payload this service cannot parse is still an event that happened. Dropping
                // the row would quietly shorten the history; the entry keeps its time and type.
                detail=null;
            }
            if(actorSubject!=null) actorState=TimelineActorState.RESOLVED;
            return new CaseTimelineEntry(row.getCreatedAt(),row.getEventType(),
                    actorSubject==null?null:handles.mint(staff.orgId(),caseId,actorSubject),
                    actorSubject==null?null:names.names().get(actorSubject),
                    detail,actorState);
        }).toList();
    }

    /** The one field of each payload worth reading back, or null. Never the free text. */
    private static String timelineDetail(String eventType,com.fasterxml.jackson.databind.JsonNode payload) {
        return switch(eventType) {
            case "ethics.case.updated" -> payload.hasNonNull("status")?payload.get("status").asText():null;
            case "ethics.case.participant.added" -> payload.hasNonNull("role")?payload.get("role").asText():null;
            case "ethics.case.reopened" -> payload.hasNonNull("reopenReason")?payload.get("reopenReason").asText():null;
            default -> null;
        };
    }

    /**
     * ES-203 — the acting staff member steps away from a case.
     *
     * <p>Ordering is deliberate: {@code requireCase} runs first, so someone who cannot already see
     * the case gets the same 404 as for any other case. Declaring a conflict must not become a way
     * to learn that a case exists. The narrative is never loaded on this path.
     *
     * <p><b>A second declaration cannot happen through this endpoint.</b> The first one removes the
     * case from this staff member's view, so {@code requireCase} answers 404 on the next attempt —
     * measured on the running cell: first POST 204, second POST 404, one ledger entry. An earlier
     * revision emitted a separate {@code recusal.repeated} event for that case; it was unreachable
     * code describing a state the guard above already prevents, so it is gone rather than kept as a
     * branch nobody can enter.
     *
     * <p>There is deliberately no counterpart that lifts a recusal. Reversal is an authorized action
     * belonging to someone else; a party that could grant it to itself would make the whole
     * declaration decorative.
     */
    @Transactional
    public void declareRecusal(StaffContext staff,UUID caseId) {
        requireCase(staff,caseId,"case_viewer");
        authorization.recuseSelf(staff,caseId);
        audit.save(new AuditOutbox(UUID.randomUUID(),staff.orgId(),caseId,"ethics.case.recusal.declared",
                encodeAuditPayload(Map.of(
                        "actorHash", secrets.sha256(staff.subject()),
                        "selfDeclared", true)),
                Instant.now()));
    }

    /**
     * The one place a case message is written — and therefore the one place the art. 9(1)(b)
     * acknowledgement can be stamped without a future caller being able to forget it. It used
     * to be stamped by {@code staffReply}, which was correct until closing the case also began
     * writing to the reporter: a case closed straight out of {@code NEW} would have told its
     * reporter something while the record still said nobody had been in touch.
     */
    private MessageResponse createMessage(UUID caseId,String author,String visibility,String key,String body,UUID orgId,String actorHash,Runnable beforeCreate){
        if(key==null||key.isBlank()||key.length()>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key is required.");
        transactionLocks.lock("message\n"+caseId+"\n"+author+"\n"+key);
        Optional<EthicsMessage> prior=messages.findByCaseIdAndAuthorTypeAndIdempotencyKey(caseId,author,key);
        if(prior.isPresent()) { if(!prior.get().getBody().equals(body)||!prior.get().getVisibility().equals(visibility)) throw new ResponseStatusException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT"); return messageResponse(prior.get()); }
        beforeCreate.run();
        EthicsMessage message=new EthicsMessage(UUID.randomUUID(),caseId,author,visibility,body,key,Instant.now()); messages.save(message);
        audit.save(new AuditOutbox(UUID.randomUUID(),orgId,caseId,"ethics.mailbox.message.created",
                encodeAuditPayload(Map.of(
                        "visibility", visibility,
                        "actorHash", actorHash)),
                Instant.now()));
        if ("REPORTER".equals(author)) {
            notifications.enqueue(orgId, NotificationOutboxPublisher.REPORTER_MESSAGE, Instant.now());
        } else if ("REPORTER_VISIBLE".equals(visibility)
                && cases.markAcknowledged(caseId, message.getCreatedAt()) == 1) {
            // The row count decides: only the write that actually stamped records the event,
            // so a second reply cannot claim to have acknowledged an already-acknowledged case.
            EthicsCase item = cases.findById(caseId).orElseThrow();
            audit.save(new AuditOutbox(UUID.randomUUID(), orgId, caseId, "ethics.case.acknowledged",
                    encodeAuditPayload(Map.of(
                            "actorHash", actorHash,
                            "elapsedDays", java.time.Duration.between(item.getCreatedAt(), message.getCreatedAt()).toDays())),
                    Instant.now()));
        }
        return messageResponse(message);
    }

    private UUID caseForSession(String channel,String token){
        if(token==null||token.isBlank()) throw genericMailboxDeny();
        MailboxSession session=sessions.findById(secrets.sha256(token)).orElseThrow(EthicsService::genericMailboxDeny);
        if(session.getExpiresAt().isBefore(Instant.now())||!session.getChannel().equals(normalizeChannel(channel))) throw genericMailboxDeny(); return session.getCaseId();
    }
    @Transactional
    public void revokeMailbox(String channel,String token){
        caseForSession(channel,token);
        sessions.deleteById(secrets.sha256(token));
    }
    private EthicsCase requireCase(StaffContext staff,UUID caseId,String relation){
        EthicsCase item=cases.findByIdAndOrgId(caseId,staff.orgId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Case not found."));
        authorization.require(staff,relation,caseId);
        return item;
    }
    private static void ensureOpen(EthicsCase item){
        if("CLOSED".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"CASE_CLOSED");
        }
    }
    private static ResponseStatusException genericMailboxDeny(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Mailbox could not be opened.");}
    /**
     * ES-203 slice 2 — the legacy label is suppressed once the case has a real answer.
     *
     * <p>Live values were {@code team:ethics-test} (26 cases) and {@code jbjb} (1). Neither
     * names a person, and nothing maps them to one: guessing which colleague a human meant
     * would make a visibility decision out of a guess, which in a whistleblowing system is
     * the failure the whole design exists to prevent. So the label is preserved, shown only
     * while there is nothing better, and never turned into a principal.
     */
    /** One case on its own — the caller already has just this row, so a lookup apiece is honest. */
    private CaseSummary summary(EthicsCase c){
        return summary(c, reports.findByCaseId(c.getId()).orElse(null),
            participants.findAllByCaseIdOrderByCreatedAtAsc(c.getId()).size());}

    /**
     * @param report may be null — a case with no report row is malformed, but the list
     *               should still show it rather than disappear the row that needs attention
     */
    private CaseSummary summary(EthicsCase c, EthicsReport report, int participantCount){
        boolean named = participantCount > 0;
        return new CaseSummary(c.getId(),c.getStatus(),named?null:c.getAssignedTo(),c.getVersion(),c.getCreatedAt(),c.getUpdatedAt(),
            c.getAcknowledgedAt(),c.getOutcome(),c.getClosedAt(),
            report==null?null:report.getSubject(),
            report==null?null:report.getCategory(),
            report==null?null:report.getMode(),
            participantCount);}
    private static MessageResponse messageResponse(EthicsMessage m){return new MessageResponse(m.getId(),m.getAuthorType(),m.getVisibility(),m.getBody(),m.getCreatedAt());}
    /** @see CaseLifecycle#reporterVisibleStatus — one implementation, so the two cannot drift. */
    private static String reporterVisibleStatus(String status){ return CaseLifecycle.reporterVisibleStatus(status); }
    private static long parseVersion(String ifMatch){
        try { return Long.parseLong(ifMatch.replace("\"","").trim()); }
        catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"IF_MATCH_INVALID"); }
    }
    private static String normalizeChannel(String channel){
        String value=channel==null?"":channel.toLowerCase(Locale.ROOT);
        if(!Set.of("etik.acik.com","speakup.acik.com").contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PUBLIC_CHANNEL_INVALID");
        return value;
    }
    /**
     * Faz 35 ES-306 residual — audit outbox payload construction moved from
     * hand-rolled string concatenation to Jackson to eliminate JSON injection
     * (a reporter subject/description with an embedded quote or backslash used
     * to corrupt the {@code AuditOutbox.payload} record). All values are
     * emitted with the object mapper's escape rules; missing values raise a
     * {@link IllegalStateException} instead of silently producing an invalid
     * JSON document.
     */
    private String encodeAuditPayload(Map<String, Object> payload) {
        try {
            return auditMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Audit payload could not be serialised.", error);
        }
    }

    public record SessionGrant(String token,Instant expiresAt){}
}
