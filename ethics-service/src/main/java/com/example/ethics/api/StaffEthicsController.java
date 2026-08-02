package com.example.ethics.api;

import com.example.ethics.api.EthicsDtos.*;
import com.example.ethics.api.EvidenceDtos.*;
import com.example.ethics.evidence.EvidenceService;
import com.example.ethics.security.*;
import com.example.ethics.service.EthicsService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ethics/cases")
public class StaffEthicsController {
    private final EthicsService service; private final EvidenceService evidence; private final StaffContextResolver context;
    private final com.example.ethics.service.CaseChainService chain;
    private final com.example.ethics.service.AcknowledgementService acknowledgements;
    public StaffEthicsController(EthicsService service,EvidenceService evidence,StaffContextResolver context,com.example.ethics.service.AcknowledgementService acknowledgements,com.example.ethics.service.CaseChainService chain){this.service=service;this.evidence=evidence;this.context=context;this.acknowledgements=acknowledgements;this.chain=chain;}
    @GetMapping List<CaseSummary> list(){return service.listCases(context.required());}
    @GetMapping("/{id}") ResponseEntity<CaseDetail> detail(@PathVariable UUID id){CaseDetail value=service.caseDetail(context.required(),id);return ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value);}
    @PatchMapping("/{id}") ResponseEntity<CaseSummary> update(@PathVariable UUID id,@RequestHeader("If-Match") String ifMatch,@Valid @RequestBody UpdateCaseRequest body){CaseSummary value=service.updateCase(context.required(),id,ifMatch,body);return ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value);}
    @PostMapping("/{id}/messages") ResponseEntity<MessageResponse> reply(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MessageRequest body){return ResponseEntity.status(HttpStatus.CREATED).body(service.staffReply(context.required(),id,key,body,false));}
    // ES-2 (#3271): automatic draft, human dispatch. GET is side-effect free; POST
    // sends the (possibly edited) draft through the ordinary staff-reply spine and
    // returns the mandatory sections the edit removed, if any — recorded, not blocked.
    @GetMapping("/{id}/acknowledgement-draft")
    EthicsDtos.AcknowledgementDraftResponse acknowledgementDraft(@PathVariable UUID id){
        var draft=acknowledgements.draft(context.required(),id);
        return new EthicsDtos.AcknowledgementDraftResponse(
                draft.body(),draft.templateId(),draft.templateVersion(),
                draft.alreadyAcknowledged(),draft.mandatorySections());
    }
    @PostMapping("/{id}/acknowledgement")
    ResponseEntity<EthicsDtos.AcknowledgementDispatchResponse> acknowledge(
            @PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody EthicsDtos.AcknowledgementDispatchRequest body){
        var result=acknowledgements.dispatch(
                context.required(),id,key,body.body(),body.templateId(),body.templateVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new EthicsDtos.AcknowledgementDispatchResponse(result.messageId(),result.missingSections()));
    }
    @PostMapping("/{id}/internal-notes") ResponseEntity<MessageResponse> note(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MessageRequest body){return ResponseEntity.status(HttpStatus.CREATED).body(service.staffReply(context.required(),id,key,body,true));}
    /**
     * ES-203 / B+ slice 1 — put a named person on this case, or read who is on it.
     *
     * <p>A participant grants nothing; access still comes from product membership. What it gives
     * the rest of ES-203 is something to name: a conflict declared about a third party, and the
     * routing exclusion, both need a principal rather than a label.
     */
    /**
     * ES-203/D — handles, scoped to this case. The browser never sees a Keycloak
     * subject: the platform keeps that surface server-to-server, and in a whistleblowing
     * product a subject that reaches a browser is a correlation key. Scoping to the case
     * also means two handles for the same colleague on two cases cannot be joined.
     */
    @GetMapping("/{id}/assignable-staff")
    ResponseEntity<List<AssignableStaffEntry>> assignableStaff(@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.assignableStaff(context.required(),id));
    }
    @PostMapping("/{id}/participants")
    ResponseEntity<Void> addParticipant(@PathVariable UUID id,@Valid @RequestBody AddParticipantRequest body){
        service.addParticipant(context.required(),id,body.handle(),body.role());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{id}/participants")
    ResponseEntity<List<CaseParticipantView>> participants(@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listParticipants(context.required(),id));
    }

    /**
     * The case's own history, oldest first. {@code no-store} like every other staff read:
     * a whistleblowing case's audit trail must not sit in a shared cache.
     */
    @GetMapping("/{id}/timeline")
    ResponseEntity<List<CaseTimelineEntry>> timeline(@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.caseTimeline(context.required(),id));
    }
    /**
     * ES-203 — step away from a case. The body is empty on purpose: the actor is the token, so
     * there is no field through which one person could recuse another.
     */
    /**
     * Records why a case is waiting. Returns no dates on purpose: this moves no deadline,
     * and answering with one would suggest it had.
     */
    @PostMapping("/{id}/waiting")
    ResponseEntity<Void> declareWaiting(@PathVariable UUID id, @RequestBody WaitingRequest body){
        service.declareWaiting(context.required(), id, body.reason());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/{id}/waiting")
    ResponseEntity<Void> resolveWaiting(@PathVariable UUID id){
        service.resolveWaiting(context.required(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Closed vocabulary; free text here would collect names. */
    record WaitingRequest(String reason) {}

    @PostMapping("/{id}/recusal")
    ResponseEntity<Void> recuse(@PathVariable UUID id){
        service.declareRecusal(context.required(),id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{id}/attachments")
    ResponseEntity<List<StaffEvidenceResponse>> attachments(@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.staffAttachments(context.required(),id));
    }
    @GetMapping("/{caseId}/attachments/{attachmentId}/derivative")
    ResponseEntity<byte[]> derivative(
            @PathVariable UUID caseId,@PathVariable UUID attachmentId){
        EvidenceService.EvidenceDownload download =
                evidence.downloadDerivative(context.required(),caseId,attachmentId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(download.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"sanitized-evidence\"")
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy",
                        "default-src 'none'; sandbox")
                .body(download.body());
    }

    // ---- ES-213 (#3375): sanctions ----------------------------------------

    @GetMapping("/{id}/sanctions")
    ResponseEntity<List<SanctionView>> sanctions(@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(chain.sanctionsFor(context.required(),id).stream().map(StaffEthicsController::toView).toList());
    }

    @PostMapping("/{id}/sanctions")
    ResponseEntity<SanctionView> recordSanction(@PathVariable UUID id,@Valid @RequestBody RecordSanctionRequest body){
        var saved=chain.recordSanction(context.required(),id,body.severityScore(),
                com.example.ethics.model.CaseSanction.Band.valueOf(body.severityBand()),
                body.escalationReason(),body.sanctionType());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(toView(saved));
    }

    @PostMapping("/sanctions/{sanctionId}/application")
    ResponseEntity<SanctionView> applySanction(@PathVariable UUID sanctionId,@Valid @RequestBody ApplySanctionRequest body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toView(chain.applySanction(context.required(),sanctionId,body.verificationNote())));
    }

    @PostMapping("/sanctions/{sanctionId}/appeal")
    ResponseEntity<SanctionView> appeal(@PathVariable UUID sanctionId,@Valid @RequestBody AppealTransitionRequest body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toView(chain.moveAppeal(context.required(),sanctionId,body.appealState())));
    }

    // ---- ES-213: retaliation monitoring -----------------------------------

    @GetMapping("/{id}/retaliation-checks")
    ResponseEntity<List<RetaliationCheckView>> checks(@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(chain.checksFor(context.required(),id).stream().map(StaffEthicsController::toView).toList());
    }

    /** Stamps that the question actually reached the reporter, as distinct from falling due. */
    @PostMapping("/retaliation-checks/{checkId}/asked")
    ResponseEntity<RetaliationCheckView> markAsked(@PathVariable UUID checkId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toView(chain.markAsked(context.required(),checkId)));
    }

    @PostMapping("/retaliation-checks/{checkId}/conclusion")
    ResponseEntity<RetaliationCheckView> concludeCheck(@PathVariable UUID checkId,@Valid @RequestBody ConcludeCheckRequest body){
        var indicators=body.indicators()==null?java.util.Set.<String>of():java.util.Set.copyOf(body.indicators());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toView(chain.concludeCheck(context.required(),checkId,body.observation(),body.risk(),body.action(),indicators)));
    }

    private static SanctionView toView(com.example.ethics.model.CaseSanction s){
        return new SanctionView(s.getId(),s.getSeverityScore(),s.getSeverityBand(),s.getEscalationReason(),
                s.getSanctionType(),s.getDecidedAt(),s.getAppliedAt(),s.getVerificationNote(),s.getAppealState());
    }

    private static RetaliationCheckView toView(com.example.ethics.model.RetaliationCheck c){
        return new RetaliationCheckView(c.getId(),c.getPeriodMonths(),c.getDueAt(),c.getAskedAt(),
                c.getObservation(),c.getRisk(),c.getAction(),c.getClosedAt());
    }
}
