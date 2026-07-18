package com.example.ethics.api;

import com.example.ethics.api.EthicsDtos.*;
import com.example.ethics.security.PublicCredentialBoundaryFilter;
import com.example.ethics.service.EthicsService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/ethics")
public class PublicEthicsController {
    private final EthicsService service;
    public PublicEthicsController(EthicsService service){this.service=service;}

    @PostMapping("/reports")
    ResponseEntity<CreateReportResponse> create(HttpServletRequest servletRequest,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateReportRequest request){
        // ForwardedHeaderFilter/framework resolves the trusted ingress host into
        // serverName. A caller-controlled business header is never tenant input.
        CreateReportResponse result=service.createReport(servletRequest.getServerName(),key,request);
        return ResponseEntity.status(result.idempotentReplay()?HttpStatus.OK:HttpStatus.CREATED).body(result);
    }

    @PostMapping("/mailbox/sessions")
    MailboxSessionResponse login(@Valid @RequestBody MailboxLoginRequest request,HttpServletResponse response){
        EthicsService.SessionGrant grant=service.openMailbox(request);
        ResponseCookie cookie=ResponseCookie.from(PublicCredentialBoundaryFilter.MAILBOX_COOKIE,grant.token()).httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/public/ethics/mailbox").maxAge(java.time.Duration.between(java.time.Instant.now(),grant.expiresAt())).build();
        response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return new MailboxSessionResponse(grant.expiresAt());
    }

    @GetMapping("/mailbox/messages")
    List<MessageResponse> list(HttpServletRequest request){return service.reporterMessages(mailboxToken(request));}

    @PostMapping("/mailbox/messages")
    ResponseEntity<MessageResponse> reply(HttpServletRequest request,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MessageRequest body){return ResponseEntity.status(HttpStatus.CREATED).body(service.reporterReply(mailboxToken(request),key,body));}

    private static String mailboxToken(HttpServletRequest request){
        if(request.getCookies()!=null) for(Cookie c:request.getCookies()) if(PublicCredentialBoundaryFilter.MAILBOX_COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }
}
