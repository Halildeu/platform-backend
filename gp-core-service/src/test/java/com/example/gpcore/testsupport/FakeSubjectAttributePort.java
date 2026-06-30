package com.example.gpcore.testsupport;

import com.example.gpcore.domain.Principal;
import com.example.gpcore.domain.SubjectAttributes;
import com.example.gpcore.port.SubjectAttributePort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link SubjectAttributePort}. Default subject has no clearances and a
 * fixed version; per-subject attributes (clearances + version) can be set, and a
 * subject can be marked unknown (→ empty → deny) for the missing-subject test.
 */
public class FakeSubjectAttributePort implements SubjectAttributePort {

    private final Map<String, SubjectAttributes> bySubject = new HashMap<>();
    private final Set<String> unknown = new HashSet<>();
    private SubjectAttributes defaultAttributes = new SubjectAttributes(Set.of(), "subject-v1");

    public FakeSubjectAttributePort put(String userId, SubjectAttributes attributes) {
        bySubject.put(userId, attributes);
        return this;
    }

    public FakeSubjectAttributePort withClearance(String userId, String clearanceToken, String version) {
        return put(userId, new SubjectAttributes(Set.of(clearanceToken), version));
    }

    public FakeSubjectAttributePort unknown(String userId) {
        unknown.add(userId);
        return this;
    }

    public FakeSubjectAttributePort defaultAttributes(SubjectAttributes attributes) {
        this.defaultAttributes = attributes;
        return this;
    }

    @Override
    public Optional<SubjectAttributes> resolve(Principal principal) {
        if (unknown.contains(principal.userId())) {
            return Optional.empty();
        }
        return Optional.of(bySubject.getOrDefault(principal.userId(), defaultAttributes));
    }
}
