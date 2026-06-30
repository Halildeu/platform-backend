package com.example.gpcore.port;

import com.example.gpcore.domain.Principal;
import com.example.gpcore.domain.SubjectAttributes;

import java.util.Optional;

/**
 * Authoritative resolution of subject-side ABAC attributes (clearances +
 * subject-policy version). Resolving here — rather than trusting a caller-built
 * {@link Principal} — prevents clearance forgery and lets clearance revocation
 * invalidate cached decisions (Codex 019f1913 #3).
 *
 * <p>{@link Optional#empty()} (or throwing) means missing subject context and is
 * treated as DENY by the decision service.
 */
public interface SubjectAttributePort {

    Optional<SubjectAttributes> resolve(Principal principal);
}
