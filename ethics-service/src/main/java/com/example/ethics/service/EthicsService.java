package com.example.ethics.service;

import com.example.ethics.api.EthicsDtos.*;
import com.example.ethics.config.EthicsProperties;
import com.example.ethics.model.*;
import com.example.ethics.notification.NotificationOutboxPublisher;
import com.example.ethics.repository.*;
import com.example.ethics.security.StaffContext;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.PublicTenantResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
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
    private final String dummyMailboxHash;

    public EthicsService(EthicsProperties properties, SecretHasher secrets, EthicsCaseRepository cases,
            EthicsReportRepository reports, ReporterAccessGrantRepository grants, EthicsMessageRepository messages,
            MailboxSessionRepository sessions, AuditOutboxRepository audit, IntakeIdempotencyRepository idempotency,
            EthicsAuthorization authorization, TransactionKeyLock transactionLocks,
            PublicIntakeSanitizer publicIntakeSanitizer, ObjectMapper auditMapper,
            PublicTenantResolver tenantResolver,
            NotificationOutboxPublisher notifications) {
        this.properties=properties;this.secrets=secrets;this.cases=cases;this.reports=reports;this.grants=grants;
        this.messages=messages;this.sessions=sessions;this.audit=audit;this.idempotency=idempotency;
        this.authorization=authorization;
        this.transactionLocks=transactionLocks;
        this.publicIntakeSanitizer=publicIntakeSanitizer;
        this.auditMapper=auditMapper;
        this.tenantResolver=tenantResolver;
        this.notifications=notifications;
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
        return new MailboxViewResponse(reporterVisibleStatus(item.getStatus()),visible);
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

    @Transactional(readOnly=true)
    public List<CaseSummary> listCases(StaffContext staff) {
        return cases.findAllByOrgIdOrderByUpdatedAtDesc(staff.orgId()).stream()
                .filter(item -> authorization.can(staff,"case_viewer",item.getId()))
                .map(EthicsService::summary).toList();
    }

    @Transactional(readOnly=true)
    public CaseDetail caseDetail(StaffContext staff,UUID caseId) {
        EthicsCase item=requireCase(staff,caseId,"case_viewer"); EthicsReport report=reports.findByCaseId(caseId).orElseThrow();
        List<MessageResponse> all=messages.findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(caseId,List.of("REPORTER_VISIBLE","INTERNAL"))
                .stream().map(EthicsService::messageResponse).toList();
        return new CaseDetail(item.getId(),item.getStatus(),item.getAssignedTo(),item.getVersion(),report.getMode(),report.getCategory(),report.getSubject(),report.getNarrative(),all);
    }

    @Transactional
    public CaseSummary updateCase(StaffContext staff,UUID caseId,String ifMatch,UpdateCaseRequest request) {
        EthicsCase item=cases.findByIdAndOrgId(caseId,staff.orgId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Case not found."));
        if(request.status()!=null) authorization.require(staff,"case_handler",caseId);
        if(request.assignedTo()!=null) authorization.require(staff,"case_triager",caseId);
        if(request.status()==null&&request.assignedTo()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_UPDATE_EMPTY");
        long expected=parseVersion(ifMatch);
        if(item.getVersion()!=expected) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"CASE_VERSION_MISMATCH");
        if(request.status()!=null&&!request.status().isBlank()) {
            String status=request.status().toUpperCase(Locale.ROOT);
            if(!Set.of("NEW","IN_REVIEW","CLOSED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_STATUS_INVALID");
            item.setStatus(status);
        }
        if(request.assignedTo()!=null) item.setAssignedTo(request.assignedTo().isBlank()?null:request.assignedTo());
        cases.saveAndFlush(item);
        audit.save(new AuditOutbox(UUID.randomUUID(),staff.orgId(),caseId,"ethics.case.updated",
                encodeAuditPayload(Map.of(
                        "status", item.getStatus(),
                        "assigned", item.getAssignedTo() != null,
                        "actorHash", secrets.sha256(staff.subject()))),
                Instant.now()));
        return summary(item);
    }

    @Transactional
    public MessageResponse staffReply(StaffContext staff,UUID caseId,String key,MessageRequest request,boolean internal) {
        requireCase(staff,caseId,"case_handler");
        return createMessage(caseId,"STAFF",internal?"INTERNAL":"REPORTER_VISIBLE",key,request.body(),staff.orgId(),secrets.sha256(staff.subject()),
                () -> ensureOpen(cases.findByIdAndOrgId(caseId,staff.orgId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Case not found."))));
    }

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
    private static CaseSummary summary(EthicsCase c){return new CaseSummary(c.getId(),c.getStatus(),c.getAssignedTo(),c.getVersion(),c.getCreatedAt(),c.getUpdatedAt());}
    private static MessageResponse messageResponse(EthicsMessage m){return new MessageResponse(m.getId(),m.getAuthorType(),m.getVisibility(),m.getBody(),m.getCreatedAt());}
    private static String reporterVisibleStatus(String status){
        return switch(status){
            case "NEW", "IN_REVIEW", "CLOSED" -> status;
            default -> throw new IllegalStateException("Unsupported reporter-visible case status");
        };
    }
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
