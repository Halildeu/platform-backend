package com.example.ethics.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EthicsDtos {
    private EthicsDtos() {}

    public record CreateReportRequest(
            @NotNull ReportMode mode,
            @NotNull ReportCategory category,
            @NotBlank @Size(max=240) String subject,
            @NotBlank @Size(max=16000) String description,
            @NotBlank @Size(max=12) String locale,
            @NotBlank @Size(min=43,max=128) @Pattern(regexp="[A-Za-z0-9_-]+") String accessSecret,
            @NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9._-]+") String noticeVersion) {}
    public enum ReportMode { ANONYMOUS, CONFIDENTIAL, NAMED }
    public enum ReportCategory { WORKPLACE_CONDUCT, FRAUD_CORRUPTION, HARASSMENT_DISCRIMINATION, OTHER }
    public record CreateReportResponse(UUID receiptId, Instant createdAt, String mailboxPath, boolean idempotentReplay) {}
    public record MailboxLoginRequest(@NotNull UUID receiptId, @NotBlank @Size(max=512) String accessSecret) {}
    public record MailboxSessionResponse(Instant expiresAt) {}
    public record MessageRequest(@NotBlank @Size(max=16000) String body) {}
    public record MessageResponse(UUID id, String authorType, String visibility, String body, Instant createdAt) {}
    /**
     * What the reporter can see of their own case.
     *
     * <p>{@code filedAt} and {@code acknowledgedAt} are added because the promise made to
     * the person filing — a reply within seven days — was tracked on the case and shown to
     * the handler, and never shown to them. A case acknowledged on day three and a case
     * ignored for three weeks looked identical from this side, so the only way to find out
     * was to keep opening the mailbox and reading the message list.
     *
     * <p>Neither field tells the reporter anything they could not already derive: they know
     * when they filed, and {@code acknowledgedAt} is stamped by the first staff message
     * already listed below. Nothing about the handler, the org, or who read the case is
     * carried here.
     *
     * <p>Deliberately no "last viewed" marker. Recording when an anonymous reporter checked
     * their case would build a timeline of that person\'s behaviour — who logs in at 3am
     * from which network — which is exactly the correlation the product exists to avoid.
     * The two timestamps here are facts about the case; a visit log would be a fact about
     * the human.
     */
    public record MailboxViewResponse(String status, List<MessageResponse> messages,
                                      Instant filedAt, Instant acknowledgedAt) {}
    /**
     * ES-203 slice 2 — {@code assignedTo} is gone; what remains is
     * {@code legacyAssignmentLabel}.
     *
     * <p>The rename is the point. The old field was free text that had held
     * {@code team:ethics-test} (a team, not a person) and {@code jbjb} (junk a
     * human typed and the system accepted). Nothing derives authority from it
     * and nothing ever did, but a field called "assignedTo" reads like an
     * answer to "who is on this case" — and a reader who believes that answer
     * is reading a label instead of the participant list.
     *
     * <p>It is {@code null} whenever the case has participants: once there is
     * a real answer, the legacy label must not sit next to it as a rival one.
     */
    /**
     * One row of the case list.
     *
     * <p>{@code subject}, {@code category}, {@code mode} and {@code participantCount} carry
     * what triage needs: the list used to show an id fragment, a status and a timestamp, so
     * two cases could not be told apart without opening each one. {@code mode} matters on its
     * own — an anonymous report has no channel back to the reporter, which changes how it is
     * handled. None of it widens the boundary: the list is already gated on {@code
     * case_viewer}, and every field here is visible to the same reader on the detail.
     */
    public record CaseSummary(UUID id, String status, String legacyAssignmentLabel, long version, Instant createdAt, Instant updatedAt,
                              Instant acknowledgedAt, String outcome, Instant closedAt,
                              String subject, String category, String mode, int participantCount) {}
    public record CaseDetail(UUID id, String status, String legacyAssignmentLabel, long version, String mode, String category, String subject, String description, List<MessageResponse> messages,
                             Instant acknowledgedAt, String outcome, Instant closedAt) {}
    /**
     * ES-301A / ES-301B. {@code outcome} is required when closing and refused otherwise;
     * {@code reason} is required only when reopening a closed case. Neither is a free-form
     * annotation — a conclusion with no finding, or a reopening with no stated cause, leaves
     * a record that cannot be read back years later.
     *
     * <p>{@code closingMessage} is what the reporter is actually told, and closing does not
     * happen without it. It is staff-authored prose rather than a rendering of
     * {@code outcome}: the internal finding is a workflow value, and what a person outside
     * the organisation should be told about their report is a judgement someone has to make.
     */
    /**
     * ES-203 slice 2 — {@code assignedTo} is still declared, only so it can be refused.
     *
     * <p>Dropping the field would have been quieter and worse: Spring's default mapper
     * ignores unknown properties, so an old client sending {@code assignedTo} would get
     * 200 and believe it had assigned someone, while nothing changed. A caller that is
     * wrong must be told, not humoured. The field is therefore kept, accepted only as
     * absent, and any value — including a blank one — is refused with a code that names
     * the replacement.
     */
    public record UpdateCaseRequest(@Size(max=40) String status,
                                    @Size(max=200) String assignedTo,
                                    @Size(max=40) String outcome, @Size(max=500) String reason,
                                    @Size(max=16000) String closingMessage) {}
    /**
     * ES-203 / B+ slice 1. The subject is a Keycloak subject UUID — the same identity the policy
     * engine uses. The pattern refuses anything that is not one, so a display name or a free-text
     * label cannot arrive here and be mistaken for a principal.
     */
    public record AddParticipantRequest(
            @NotBlank @Size(max=128) @Pattern(regexp="v[0-9]+\\.[A-Za-z0-9_-]+") String handle,
            @NotBlank @Size(max=32) String role) {}
    /**
     * ES-203/D — the listing crosses the same boundary the assignment does.
     *
     * <p>ES-203/C adds {@code displayName}: a pass-through from the user
     * directory, resolved at read time and stored nowhere in this service. It
     * is {@code null} when the directory cannot answer — this is a display
     * surface, and a missing name degrades the view without blocking it.
     */
    public record CaseParticipantView(String handle, String displayName, String role,
                                      java.time.Instant addedAt) {}

    /**
     * One thing that happened to a case, in the order it happened.
     *
     * <p>The audit trail has recorded every one of these since the beginning and nobody
     * could read it: the case screen showed the current state and the messages, so "who
     * moved this to investigating, and when" had no answer on the surface where the
     * question is asked.
     *
     * <p>{@code actorHandle} is the same case-scoped handle the participant list uses, not
     * the {@code actorHash} the trail stores. The hash is one-way and there is no reverse
     * table — by design (ES-203/D) — so the handle is recomputed by matching the hash
     * against the org's own members. A staff member who has since left the product cannot
     * be resolved, and the entry then says so rather than inventing an actor.
     *
     * <p>{@code actorState} exists because a null handle used to mean two different things
     * and the reader could not tell them apart: nobody was recorded as acting, or somebody
     * was and no longer resolves. On an audit trail those are opposite claims — "nobody
     * touched this" versus "we cannot say who did" — and collapsing them let the record
     * quietly lose fidelity whenever the name directory was down.
     */
    public record CaseTimelineEntry(Instant occurredAt, String event, String actorHandle,
                                    String actorDisplayName, String detail,
                                    TimelineActorState actorState) {}

    /**
     * Whether this entry has an actor, and whether it can be named.
     *
     * <p>Derived fail-closed from the recorded payload: {@link #NONE} is asserted only when
     * the payload was read and carried no actor reference at all. A payload this service
     * cannot parse yields {@link #UNRESOLVED}, never {@code NONE} — claiming nobody acted
     * is a statement about the case, and an unreadable record cannot support it.
     *
     * <p>There is deliberately no state for <em>why</em> an actor failed to resolve. The
     * person may have left the product, or the directory may be unreachable this second;
     * the service cannot reliably tell those apart, and a guess would both mislead and say
     * more about a named individual than this surface should.
     */
    public enum TimelineActorState {
        /** The payload was read and records no actor: an anonymous filing, a pipeline step. */
        NONE,
        /** An actor is recorded and resolves to a current member of this organization. */
        RESOLVED,
        /** An actor is recorded (or may be) but cannot be named right now. */
        UNRESOLVED
    }
    /**
     * ES-203/C — one selectable person in the assignment picker.
     *
     * <p>The handle is what the client sends back; the name is what the human
     * reads. Names are not unique, which is why both travel together: the UI
     * must disambiguate duplicates (it shows a handle-derived discriminator)
     * rather than letting two "Ayşe Yılmaz" rows collapse into one choice.
     */
    public record AssignableStaffEntry(String handle, String displayName) {}
}
