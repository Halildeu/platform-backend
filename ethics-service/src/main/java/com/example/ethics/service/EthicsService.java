package com.example.ethics.service;

import com.example.ethics.api.EthicsDtos.*;
import com.example.ethics.config.EthicsProperties;
import com.example.ethics.model.*;
import com.example.ethics.repository.*;
import com.example.ethics.security.StaffContext;
import com.example.ethics.security.EthicsAuthorization;
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

    public EthicsService(EthicsProperties properties, SecretHasher secrets, EthicsCaseRepository cases,
            EthicsReportRepository reports, ReporterAccessGrantRepository grants, EthicsMessageRepository messages,
            MailboxSessionRepository sessions, AuditOutboxRepository audit, IntakeIdempotencyRepository idempotency,
            EthicsAuthorization authorization, TransactionKeyLock transactionLocks) {
        this.properties=properties;this.secrets=secrets;this.cases=cases;this.reports=reports;this.grants=grants;
        this.messages=messages;this.sessions=sessions;this.audit=audit;this.idempotency=idempotency;
        this.authorization=authorization;
        this.transactionLocks=transactionLocks;
    }

    @Transactional
    public CreateReportResponse createReport(String channel, String key, CreateReportRequest request) {
        if (key == null || key.isBlank() || key.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required.");
        String normalizedChannel = switch (channel == null ? "" : channel.toLowerCase(Locale.ROOT)) {
            case "etik.acik.com", "speakup.acik.com" -> channel.toLowerCase(Locale.ROOT);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Public channel is invalid.");
        };
        UUID orgId = properties.publicOrgId();
        if (request.mode()!=ReportMode.ANONYMOUS) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"REPORT_MODE_NOT_ENABLED");
        transactionLocks.lock("intake\n"+orgId+"\n"+normalizedChannel+"\n"+key);
        String requestHash = secrets.sha256(request.mode()+"\n"+request.category()+"\n"+request.subject()+"\n"+request.description()+"\n"+request.locale());
        Optional<IntakeIdempotency> prior = idempotency.findByOrgIdAndChannelAndIdempotencyKey(orgId, normalizedChannel, key);
        if (prior.isPresent()) {
            if (!prior.get().getRequestHash().equals(requestHash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            // A replay never re-discloses the raw access secret. The user must retain the original receipt.
            return new CreateReportResponse(prior.get().getReceiptId(), null, Instant.now(), "/mailbox", true);
        }
        Instant now=Instant.now(); UUID caseId=UUID.randomUUID(); UUID reportId=UUID.randomUUID(); UUID receiptId=UUID.randomUUID();
        String rawSecret=secrets.newSecret();
        cases.save(new EthicsCase(caseId,orgId,now));
        reports.save(new EthicsReport(reportId,caseId,request.mode().name(),request.category(),request.subject(),request.description(),request.locale(),now));
        grants.save(new ReporterAccessGrant(receiptId,caseId,secrets.hash(rawSecret,properties.secretIterations()),now));
        audit.save(new AuditOutbox(UUID.randomUUID(),orgId,caseId,"ethics.report.created","{\"mode\":\""+request.mode().name()+"\",\"category\":\""+safeToken(request.category())+"\"}",now));
        idempotency.save(new IntakeIdempotency(UUID.randomUUID(),orgId,normalizedChannel,key,requestHash,receiptId,now));
        return new CreateReportResponse(receiptId,rawSecret,now,"/mailbox",false);
    }

    @Transactional(noRollbackFor=ResponseStatusException.class)
    public SessionGrant openMailbox(MailboxLoginRequest request) {
        ReporterAccessGrant grant=grants.findById(request.receiptId()).orElseThrow(EthicsService::genericMailboxDeny);
        Instant now=Instant.now();
        if (grant.getLockedUntil()!=null && grant.getLockedUntil().isAfter(now)) throw genericMailboxDeny();
        if (!secrets.verify(request.accessSecret(),grant.getSecretHash())) { grant.failed(now); throw genericMailboxDeny(); }
        grant.verified(); String token=secrets.newSecret(); Instant expires=now.plus(properties.mailboxSessionTtl());
        sessions.save(new MailboxSession(secrets.sha256(token),grant.getCaseId(),expires,now));
        return new SessionGrant(token,expires);
    }

    @Transactional(readOnly=true)
    public List<MessageResponse> reporterMessages(String token) {
        UUID caseId=caseForSession(token);
        return messages.findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(caseId,List.of("REPORTER_VISIBLE"))
                .stream().map(EthicsService::messageResponse).toList();
    }

    @Transactional
    public MessageResponse reporterReply(String token,String key,MessageRequest request) {
        UUID caseId=caseForSession(token);
        return createMessage(caseId,"REPORTER","REPORTER_VISIBLE",key,request.body(),properties.publicOrgId());
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
        String relation=request.assignedTo()!=null?"case_triager":"case_handler";
        EthicsCase item=requireCase(staff,caseId,relation);
        long expected=parseVersion(ifMatch);
        if(item.getVersion()!=expected) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"CASE_VERSION_MISMATCH");
        if(request.status()!=null&&!request.status().isBlank()) {
            String status=request.status().toUpperCase(Locale.ROOT);
            if(!Set.of("NEW","IN_REVIEW","CLOSED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"CASE_STATUS_INVALID");
            item.setStatus(status);
        }
        if(request.assignedTo()!=null) item.setAssignedTo(request.assignedTo().isBlank()?null:request.assignedTo());
        audit.save(new AuditOutbox(UUID.randomUUID(),staff.orgId(),caseId,"ethics.case.updated","{}",Instant.now()));
        return summary(item);
    }

    @Transactional
    public MessageResponse staffReply(StaffContext staff,UUID caseId,String key,MessageRequest request,boolean internal) {
        requireCase(staff,caseId,"case_handler");
        return createMessage(caseId,"STAFF",internal?"INTERNAL":"REPORTER_VISIBLE",key,request.body(),staff.orgId());
    }

    private MessageResponse createMessage(UUID caseId,String author,String visibility,String key,String body,UUID orgId){
        if(key==null||key.isBlank()||key.length()>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key is required.");
        transactionLocks.lock("message\n"+caseId+"\n"+author+"\n"+key);
        Optional<EthicsMessage> prior=messages.findByCaseIdAndAuthorTypeAndIdempotencyKey(caseId,author,key);
        if(prior.isPresent()) { if(!prior.get().getBody().equals(body)||!prior.get().getVisibility().equals(visibility)) throw new ResponseStatusException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT"); return messageResponse(prior.get()); }
        EthicsMessage message=new EthicsMessage(UUID.randomUUID(),caseId,author,visibility,body,key,Instant.now()); messages.save(message);
        audit.save(new AuditOutbox(UUID.randomUUID(),orgId,caseId,"ethics.mailbox.message.created","{\"visibility\":\""+visibility+"\"}",Instant.now()));
        return messageResponse(message);
    }

    private UUID caseForSession(String token){
        if(token==null||token.isBlank()) throw genericMailboxDeny();
        MailboxSession session=sessions.findById(secrets.sha256(token)).orElseThrow(EthicsService::genericMailboxDeny);
        if(session.getExpiresAt().isBefore(Instant.now())) throw genericMailboxDeny(); return session.getCaseId();
    }
    private EthicsCase requireCase(StaffContext staff,UUID caseId,String relation){
        EthicsCase item=cases.findByIdAndOrgId(caseId,staff.orgId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Case not found."));
        authorization.require(staff,relation,caseId);
        return item;
    }
    private static ResponseStatusException genericMailboxDeny(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Mailbox could not be opened.");}
    private static CaseSummary summary(EthicsCase c){return new CaseSummary(c.getId(),c.getStatus(),c.getAssignedTo(),c.getVersion(),c.getCreatedAt(),c.getUpdatedAt());}
    private static MessageResponse messageResponse(EthicsMessage m){return new MessageResponse(m.getId(),m.getAuthorType(),m.getVisibility(),m.getBody(),m.getCreatedAt());}
    private static long parseVersion(String ifMatch){
        try { return Long.parseLong(ifMatch.replace("\"","").trim()); }
        catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"IF_MATCH_INVALID"); }
    }
    private static String safeToken(String value){return value.replaceAll("[^A-Za-z0-9_-]","_").substring(0,Math.min(80,value.length()));}
    public record SessionGrant(String token,Instant expiresAt){}
}
