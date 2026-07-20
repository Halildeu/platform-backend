package com.example.ethics.api;

import com.example.ethics.api.EthicsDtos.*;
import com.example.ethics.security.*;
import com.example.ethics.service.EthicsService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ethics/cases")
public class StaffEthicsController {
    private final EthicsService service; private final StaffContextResolver context;
    public StaffEthicsController(EthicsService service,StaffContextResolver context){this.service=service;this.context=context;}
    @GetMapping List<CaseSummary> list(){return service.listCases(context.required());}
    @GetMapping("/{id}") ResponseEntity<CaseDetail> detail(@PathVariable UUID id){CaseDetail value=service.caseDetail(context.required(),id);return ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value);}
    @PatchMapping("/{id}") ResponseEntity<CaseSummary> update(@PathVariable UUID id,@RequestHeader("If-Match") String ifMatch,@Valid @RequestBody UpdateCaseRequest body){CaseSummary value=service.updateCase(context.required(),id,ifMatch,body);return ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value);}
    @PostMapping("/{id}/messages") ResponseEntity<MessageResponse> reply(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MessageRequest body){return ResponseEntity.status(HttpStatus.CREATED).body(service.staffReply(context.required(),id,key,body,false));}
    @PostMapping("/{id}/internal-notes") ResponseEntity<MessageResponse> note(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MessageRequest body){return ResponseEntity.status(HttpStatus.CREATED).body(service.staffReply(context.required(),id,key,body,true));}
}
