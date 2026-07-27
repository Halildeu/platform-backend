package com.example.ethics.api;

import com.example.ethics.security.StaffContextResolver;
import com.example.ethics.service.EthicsService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Faz 35 ES-203 — who may be put on a case in this organisation.
 *
 * <p>Deliberately not mounted under {@code /cases/{id}}. The answer is the same for every
 * case in the org, and hanging it off a case would say otherwise: it would read as
 * case-specific, and the next person to touch it would find it natural to filter by who
 * is already attached. In a whistleblowing system that filter is correlation data — which
 * staff are on which report — and no endpoint should hand it out to fill a dropdown.
 *
 * <p><b>Subjects only.</b> The response carries Keycloak subject UUIDs and nothing else,
 * because nothing else exists: ethics-service holds no names and no email addresses, and
 * there is no subject-keyed lookup anywhere it can reach. That makes this a technical
 * precondition rather than a usable picker — a human cannot choose between UUIDs. The
 * resolver that would make it usable is its own piece of work, because adding one means
 * opening a staff-directory query from inside the ethics hotline and that deserves its
 * own review rather than arriving as a side effect of a dropdown.
 */
@RestController
@RequestMapping("/api/v1/ethics/assignable-staff")
public class StaffDirectoryController {

    private final EthicsService service;
    private final StaffContextResolver context;

    public StaffDirectoryController(EthicsService service, StaffContextResolver context) {
        this.service = service;
        this.context = context;
    }

    @GetMapping
    ResponseEntity<List<String>> assignableStaff() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.assignableStaff(context.required()));
    }
}
