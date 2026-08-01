package com.example.ethics.service;

import com.example.ethics.model.AckTemplate;
import com.example.ethics.model.AuditOutbox;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.model.EthicsReport;
import com.example.ethics.repository.AckTemplateRepository;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.EthicsReportRepository;
import com.example.ethics.security.StaffContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ES-2 (#3271) — the acknowledgement draft: automatic preparation, human dispatch.
 *
 * <p>Two rejected extremes shaped this. Fully automatic sending was rejected because the
 * acknowledgement is the reporter's first contact with a human process, and a robot text
 * at that moment tells them nobody read it. Fully manual was rejected because the art.
 * 9(1)(b) seven-day clock cannot hang on someone's memory — the {@code AckNetWorker}
 * covers that with a last-day net. Between the two: the system writes the draft, a person
 * reads, edits and sends it; only when nobody does is it sent as-is, and that exception is
 * written to the ledger as {@code dispatch=AUTOMATIC}.
 */
@Service
public class AcknowledgementService {

    /** Bounded audit vocabulary for the mandatory sections the acceptance names. */
    static final Map<String, String> MANDATORY_SECTIONS = Map.of(
            "PROCESS", "Süreç nasıl işleyecek",
            "FEEDBACK_WINDOW", "geri bildirim",
            "CONFIDENTIALITY", "Gizlilik",
            "RETALIATION_BAN", "Misilleme",
            "EXTERNAL_CHANNELS", "Dış kanal",
            "RETURN_PATH", "dönebilirsiniz");

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("tr"));
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    /** Art. 9(1)(f): feedback within three months of receipt. */
    private static final int FEEDBACK_MONTHS = 3;

    private final AckTemplateRepository templates;
    private final EthicsCaseRepository cases;
    private final EthicsReportRepository reports;
    private final AuditOutboxRepository audit;
    private final EthicsService ethics;
    private final ObjectMapper mapper;

    public AcknowledgementService(
            AckTemplateRepository templates,
            EthicsCaseRepository cases,
            EthicsReportRepository reports,
            AuditOutboxRepository audit,
            EthicsService ethics,
            ObjectMapper mapper) {
        this.templates = templates;
        this.cases = cases;
        this.reports = reports;
        this.audit = audit;
        this.ethics = ethics;
        this.mapper = mapper;
    }

    public record Draft(
            String body,
            UUID templateId,
            int templateVersion,
            boolean alreadyAcknowledged,
            List<String> mandatorySections) {}

    public record DispatchResult(UUID messageId, List<String> missingSections) {}

    /** The draft the staff screen shows: resolved template, placeholders filled. */
    @Transactional(readOnly = true)
    public Draft draft(StaffContext staff, UUID caseId) {
        EthicsCase item = ethics.requireCase(staff, caseId, "case_handler");
        EthicsReport report = reports.findByCaseId(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found."));
        AckTemplate template = templates.resolve(staff.orgId(), report.getCategory())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "ACK_TEMPLATE_MISSING"));
        return new Draft(
                fill(template.getBody(), item),
                template.getId(),
                template.getVersion(),
                item.getAcknowledgedAt() != null,
                List.copyOf(MANDATORY_SECTIONS.keySet()));
    }

    /**
     * Human dispatch. The body is whatever the handler made of the draft — edited or not.
     * Missing mandatory sections do not block the send (the human has seen the warning and
     * the wording is their call) but they are RECORDED: the ledger entry carries the exact
     * list, because "the acknowledgement went out without the retaliation-ban wording" is
     * a fact a future audit needs to find without diffing prose.
     */
    @Transactional
    public DispatchResult dispatch(
            StaffContext staff, UUID caseId, String idempotencyKey,
            String body, UUID templateId, int templateVersion) {
        var message = ethics.staffReply(
                staff, caseId, idempotencyKey,
                new com.example.ethics.api.EthicsDtos.MessageRequest(body), false);
        List<String> missing = missingSections(body);
        appendDispatchAudit(staff.orgId(), caseId, templateId, templateVersion, "MANUAL", missing);
        return new DispatchResult(message.id(), missing);
    }

    static List<String> missingSections(String body) {
        return MANDATORY_SECTIONS.entrySet().stream()
                .filter(entry -> !body.toLowerCase(Locale.forLanguageTag("tr"))
                        .contains(entry.getValue().toLowerCase(Locale.forLanguageTag("tr"))))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    String fill(String template, EthicsCase item) {
        LocalDate filed = LocalDate.ofInstant(item.getCreatedAt(), ZONE);
        return template
                .replace("{{RECEIPT_ID}}", "#" + shortId(item.getId()))
                .replace("{{FILED_AT}}", DATE.format(filed))
                .replace("{{FEEDBACK_DUE}}", DATE.format(filed.plusMonths(FEEDBACK_MONTHS)));
    }

    void appendDispatchAudit(
            UUID orgId, UUID caseId, UUID templateId, int templateVersion,
            String dispatchMode, List<String> missing) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateId", templateId);
        payload.put("templateVersion", templateVersion);
        payload.put("dispatch", dispatchMode);
        if (!missing.isEmpty()) payload.put("missingSections", missing);
        try {
            audit.save(new AuditOutbox(
                    UUID.randomUUID(), orgId, caseId,
                    "ethics.case.acknowledgement.dispatched",
                    mapper.writeValueAsString(payload), Instant.now()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Acknowledgement audit payload could not be serialized", error);
        }
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
