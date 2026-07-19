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
        CreateReportResponse result=service.createReport(servletRequest.getServerName(),key,request);
        return ResponseEntity.status(result.idempotentReplay()?HttpStatus.OK:HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/mailbox/sessions")
    MailboxSessionResponse login(@Valid @RequestBody MailboxLoginRequest request,HttpServletRequest servletRequest,HttpServletResponse response){
        EthicsService.SessionGrant grant=service.openMailbox(servletRequest.getServerName(),request);
        ResponseCookie cookie=ResponseCookie.from(PublicCredentialBoundaryFilter.MAILBOX_COOKIE,grant.token()).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(java.time.Duration.between(java.time.Instant.now(),grant.expiresAt())).build();
        response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        return new MailboxSessionResponse(grant.expiresAt());
    }

    @GetMapping("/mailbox/messages")
    List<MessageResponse> list(HttpServletRequest request){return service.reporterMessages(request.getServerName(),mailboxToken(request));}

    @PostMapping("/mailbox/messages")
    ResponseEntity<MessageResponse> reply(HttpServletRequest request,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MessageRequest body){return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.reporterReply(request.getServerName(),mailboxToken(request),key,body));}

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
