package com.example.ethics.api;

import com.example.ethics.api.EthicsDtos.*;
import com.example.ethics.api.EvidenceDtos.*;
import com.example.ethics.evidence.EvidenceService;
import com.example.ethics.security.PublicCredentialBoundaryFilter;
import com.example.ethics.service.EthicsService;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/ethics")
public class PublicEthicsController {
    private final EthicsService service;
    private final EvidenceService evidence;
    public PublicEthicsController(EthicsService service,EvidenceService evidence){
        this.service=service;this.evidence=evidence;
    }

    /**
     * ES-212 — which reporting modes this host's tenant offers. Read before the form
     * renders so the reporter is never shown an option that would be refused on submit.
     * No authentication: the answer is the same for everyone who can reach the form, and
     * requiring a session to find out how to report anonymously would defeat the point.
     */
    @GetMapping("/intake-options")
    @RateLimiter(name = "publicIntake")
    ResponseEntity<IntakeOptionsResponse> intakeOptions(HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.intakeOptions(servletRequest.getServerName()));
    }

    @PostMapping("/reports")
    @RateLimiter(name = "publicIntake")
    @Bulkhead(name = "publicIntake")
    ResponseEntity<CreateReportResponse> create(HttpServletRequest servletRequest,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateReportRequest request){
        CreateReportResponse result=service.createReport(servletRequest.getServerName(),key,request);
        return ResponseEntity.status(result.idempotentReplay()?HttpStatus.OK:HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/mailbox/sessions")
    @RateLimiter(name = "publicMailbox")
    @Bulkhead(name = "publicMailbox")
    MailboxSessionResponse login(@Valid @RequestBody MailboxLoginRequest request,HttpServletRequest servletRequest,HttpServletResponse response){
        EthicsService.SessionGrant grant=service.openMailbox(servletRequest.getServerName(),request);
        ResponseCookie cookie=ResponseCookie.from(PublicCredentialBoundaryFilter.MAILBOX_COOKIE,grant.token()).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(java.time.Duration.between(java.time.Instant.now(),grant.expiresAt())).build();
        response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return new MailboxSessionResponse(grant.expiresAt());
    }

    @GetMapping("/mailbox/messages")
    ResponseEntity<MailboxViewResponse> list(HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.reporterMailbox(request.getServerName(),mailboxToken(request)));
    }

    @PostMapping("/mailbox/messages")
    @RateLimiter(name = "publicMailbox")
    @Bulkhead(name = "publicMailbox")
    ResponseEntity<MessageResponse> reply(HttpServletRequest request,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MessageRequest body){return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.reporterReply(request.getServerName(),mailboxToken(request),key,body));}

    @PostMapping("/mailbox/attachments")
    @RateLimiter(name = "publicMailbox")
    @Bulkhead(name = "publicMailbox")
    ResponseEntity<EvidenceDeclarationResponse> declareAttachment(
            HttpServletRequest request,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody EvidenceDeclarationRequest body) {
        EvidenceDeclarationResponse result =
                evidence.declare(request.getServerName(), mailboxToken(request), key, body);
        return ResponseEntity.status(result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    @PutMapping(value = "/evidence/uploads", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @RateLimiter(name = "publicMailbox")
    @Bulkhead(name = "publicMailbox")
    ResponseEntity<EvidenceStatusResponse> uploadAttachment(
            HttpServletRequest request,
            @RequestHeader("X-Etik-Upload-Capability") String capability,
            @RequestHeader(HttpHeaders.CONTENT_LENGTH) long contentLength)
            throws java.io.IOException {
        EvidenceStatusResponse result = evidence.upload(
                request.getServerName(), capability, contentLength, request.getInputStream());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/mailbox/attachments")
    ResponseEntity<java.util.List<EvidenceStatusResponse>> listAttachments(
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                evidence.reporterAttachments(
                        request.getServerName(), mailboxToken(request)));
    }

    @DeleteMapping("/mailbox/session")
    ResponseEntity<Void> logout(HttpServletRequest request,HttpServletResponse response){
        service.revokeMailbox(request.getServerName(),mailboxToken(request));
        ResponseCookie expired=ResponseCookie.from(PublicCredentialBoundaryFilter.MAILBOX_COOKIE,"").httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE,expired.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return ResponseEntity.noContent().build();
    }

    private static String mailboxToken(HttpServletRequest request){
        if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if(PublicCredentialBoundaryFilter.MAILBOX_COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }
}
