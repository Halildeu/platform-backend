package com.example.user.controller;

import com.example.user.dto.AssigneeCandidateEntry;
import com.example.user.dto.AssigneeCandidateSearchRequest;
import com.example.user.dto.AssigneeCandidateSearchResponse;
import com.example.user.security.ServiceAuthenticationToken;
import com.example.user.service.AssigneeCandidateService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Faz 24 (gitops#3834) — people-picker search for trusted services acting for a signed-in user.
 *
 * <p>meeting-service authorizes the user (module:meeting MANAGER on a meeting it can see) and asks
 * here on the user's behalf. The answer is scoped by the requester's directory row, not by the
 * calling service: a requester that is not an active directory member is refused (403), never
 * served an unscoped list.
 *
 * <p>POST so the requester subject and the typed name never enter a URL or an access log.
 * Nothing here is logged. Protected like the other internal surfaces ({@code PERM_users:internal};
 * see {@code SecurityConfig#internalServiceTokenFilterChain}).
 */
@RestController
@RequestMapping("/api/users/internal")
public class AssigneeCandidatesInternalController {

    private final AssigneeCandidateService candidateService;

    public AssigneeCandidatesInternalController(AssigneeCandidateService candidateService) {
        this.candidateService = candidateService;
    }

    @PostMapping("/assignee-candidates")
    public ResponseEntity<?> search(@Valid @RequestBody AssigneeCandidateSearchRequest request) {
        requireServiceAuthority("PERM_users:internal");
        String query = request.query().trim();
        if (query.length() < AssigneeCandidateService.MIN_QUERY_LENGTH) {
            return ResponseEntity.badRequest().build();
        }
        int limit = request.limit() == null ? AssigneeCandidateService.DEFAULT_LIMIT : request.limit();
        return candidateService.search(request.requesterSubject(), query, limit)
                .<ResponseEntity<?>>map(users -> ResponseEntity.ok(new AssigneeCandidateSearchResponse(
                        users.stream()
                                .map(user -> new AssigneeCandidateEntry(user.getId(), user.getName(), user.getEmail()))
                                .toList())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "requester_not_in_directory")));
    }

    /**
     * Only a service token carrying the authority passes — a user JWT that happens to hold the same
     * authority string must not (same rule as {@code UserController#requireAnyServiceAuthority}).
     */
    private static void requireServiceAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ServiceAuthenticationToken serviceAuth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service token gerekli");
        }
        boolean has = serviceAuth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
        if (!has) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Yetersiz servis yetkisi");
        }
    }
}
