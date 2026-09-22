using TeamsCapture.Worker;
using Microsoft.Extensions.Options;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

var builder = WebApplication.CreateBuilder(args);
builder.Services.Configure<TeamsCaptureOptions>(
    builder.Configuration.GetSection(TeamsCaptureOptions.SectionName));
builder.Services.AddHttpClient<ITeamsAccessTokenProvider, EntraTeamsAccessTokenProvider>(client =>
    client.Timeout = TimeSpan.FromSeconds(15))
    .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });
builder.Services.AddSingleton<TeamsCallbackState>();
builder.Services.AddSingleton<DurableTeamsCalendarMeetingResolver>();
builder.Services.AddSingleton<ITeamsCalendarMeetingResolver>(services =>
    services.GetRequiredService<DurableTeamsCalendarMeetingResolver>());
builder.Services.AddSingleton<TeamsMeetingPresenceCoordinator>();
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddHttpClient<ITeamsCallLifecycleClient, GraphTeamsCallLifecycleClient>(client =>
    client.Timeout = TimeSpan.FromSeconds(15))
    .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });
builder.Services.AddHostedService<TeamsCallMaintenanceService>();
builder.Services.AddHttpClient<ITeamsMeetingPresenceClient, GraphTeamsMeetingPresenceClient>(client =>
    client.Timeout = TimeSpan.FromSeconds(30))
    .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });
builder.Services.AddHttpClient<ITeamsParticipantRosterClient, GraphTeamsParticipantRosterClient>(client =>
    client.Timeout = TimeSpan.FromSeconds(15))
    .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer(options =>
{
    options.MetadataAddress = "https://api.aps.skype.com/v1/.well-known/OpenIdConfiguration";
    options.RequireHttpsMetadata = true;
    options.MapInboundClaims = false;
    options.TokenValidationParameters = new TokenValidationParameters
    {
        ValidateIssuer = true, ValidIssuer = "https://api.botframework.com",
        ValidateAudience = true, ValidAudience = builder.Configuration["TeamsCapture:ApplicationId"] ?? "disabled",
        ValidateLifetime = true, RequireExpirationTime = true, RequireSignedTokens = true,
        ValidateIssuerSigningKey = true, ValidAlgorithms = [SecurityAlgorithms.RsaSha256],
        ClockSkew = TimeSpan.FromSeconds(30)
    };
});
builder.Services.AddAuthorization();
builder.WebHost.ConfigureKestrel(options => options.Limits.MaxRequestBodySize = 65536);

var app = builder.Build();
app.Use(async (context, next) =>
{
    try { await next(context); }
    catch (Exception error) when (error is IOException or UnauthorizedAccessException)
    {
        app.Logger.LogError("Teams durable state unavailable; request was not confirmed.");
        if (context.Response.HasStarted) throw;
        context.Response.StatusCode = 503;
        await context.Response.WriteAsJsonAsync(new { code = "durable_state_unavailable" });
    }
});
app.UseAuthentication();
app.UseAuthorization();
app.MapPost("/api/teams/callback", (JsonElement payload, HttpContext context,
    TeamsCallbackState state, IOptions<TeamsCaptureOptions> settings) =>
{
    var config = settings.Value;
    if (!config.Enabled || !Guid.TryParse(config.TenantId, out var expectedTenant)
        || !Guid.TryParse(context.User.FindFirst("tid")?.Value, out var actualTenant)
        || expectedTenant != actualTenant) return Results.Forbid();
    return state.Apply(payload) ? Results.NoContent() : Results.StatusCode(503);
}).RequireAuthorization();

app.MapPost("/api/teams/meetings/{meetingId:guid}/join", async (
    Guid meetingId,
    JoinMeetingRequest request,
    HttpContext context,
    IOptions<TeamsCaptureOptions> settings,
    DurableTeamsCalendarMeetingResolver resolver,
    TeamsMeetingPresenceCoordinator coordinator,
    ITeamsMeetingPresenceClient teamsClient,
    CancellationToken cancellationToken) =>
{
    var config = settings.Value;
    if (!config.IsReadyForRegistration()) return Results.StatusCode(503);
    if (!ControlKeyMatches(context, config.ControlApiKey!)) return Results.Unauthorized();
    if (!resolver.Register(meetingId, request.CalendarEventId,
            new ScheduledTeamsMeeting(request.ThreadId, request.MessageId, request.OrganizerUserId)))
        return Results.BadRequest(new { code = "invalid_or_conflicting_calendar_reference" });

    var result = await coordinator.JoinAsync(
        new MeetingPresenceCommand(meetingId, request.CalendarEventId, request.CorrelationId),
        teamsClient,
        cancellationToken).ConfigureAwait(false);
    return result.Joined
        ? Results.Accepted(value: new { result.CallId })
        : Results.Problem(statusCode: result.FailureCode is "conflict" or "capacity_exhausted"
            or "call_already_ended" or "join_outcome_unconfirmed" ? 409 : 502, title: result.FailureCode);
});

app.MapGet("/api/teams/meetings/{meetingId:guid}/join-status", (
    Guid meetingId, HttpContext context, IOptions<TeamsCaptureOptions> settings, TeamsCallbackState state) =>
{
    context.Response.Headers.CacheControl = "no-store";
    if (!ControlKeyMatches(context, settings.Value.ControlApiKey ?? "")) return Results.Unauthorized();
    var attempt = state.ReadJoin(meetingId);
    return attempt is null ? Results.NotFound() : Results.Ok(new
    {
        meetingId, attempt.CallId, attempt.RequestedAt,
        state = attempt.CallId is null ? "join_outcome_unconfirmed" : state.Read(attempt.CallId)
    });
});

app.MapPost("/api/teams/calls/{callId}/leave", async (
    string callId, HttpContext context, IOptions<TeamsCaptureOptions> settings, TeamsCallbackState state,
    ITeamsCallLifecycleClient lifecycle, CancellationToken cancellationToken) =>
{
    context.Response.Headers.CacheControl = "no-store";
    if (!ControlKeyMatches(context, settings.Value.ControlApiKey ?? "")) return Results.Unauthorized();
    if (!settings.Value.IsReadyForRegistration()) return Results.StatusCode(503);
    if (state.ReadMeetingId(callId) is null) return Results.NotFound();
    if (state.Read(callId) == "terminated") return Results.NoContent();
    var result = await lifecycle.LeaveAsync(callId, cancellationToken).ConfigureAwait(false);
    if (result == TeamsCallOperationResult.Unconfirmed)
        return Results.Problem(statusCode: 502, title: "call_leave_not_confirmed");
    state.MarkTerminated(callId);
    return Results.NoContent();
});

app.MapGet("/api/teams/readiness", (HttpContext context, IOptions<TeamsCaptureOptions> settings) =>
{
    context.Response.Headers.CacheControl = "no-store";
    var config = settings.Value;
    if (!ControlKeyMatches(context, config.ControlApiKey ?? "")) return Results.Unauthorized();
    return Results.Json(new
    {
        controlPlaneConfigured = config.IsReadyForRegistration(),
        tenantAcceptance = "not-verified-by-configuration",
        mediaMode = "service-hosted-presence-only",
        liveAudio = false, liveSpeakerAttribution = false, teamsSidePanel = false, automaticCalendarScan = false,
        completedCallRetentionConfigured = config.CompletedCallRetentionHours is not null,
        requirements = new[] { "tenant-callback-and-calling-registration", "approved-media-host-and-adapter",
            "media-permission-and-recording-status", "canonical-audio-analysis-integration", "authorized-teams-side-panel",
            "real-two-participant-live-acceptance" }
    }, statusCode: config.IsReadyForRegistration() ? 200 : 503);
});

app.MapGet("/api/teams/calls/{callId}", (
    string callId,
    HttpContext context,
    IOptions<TeamsCaptureOptions> settings,
    TeamsCallbackState state) =>
{
    var config = settings.Value;
    if (!config.IsReadyForRegistration()) return Results.StatusCode(503);
    if (!ControlKeyMatches(context, config.ControlApiKey!)) return Results.Unauthorized();
    var callState = state.Read(callId);
    return callState is null
        ? Results.NotFound()
        : Results.Ok(new { callId, meetingId = state.ReadMeetingId(callId), state = callState });
});

app.MapGet("/api/teams/calls/{callId}/participants", async (
    string callId,
    HttpContext context,
    IOptions<TeamsCaptureOptions> settings,
    TeamsCallbackState state,
    ITeamsParticipantRosterClient rosterClient,
    CancellationToken cancellationToken) =>
{
    context.Response.Headers.CacheControl = "no-store";
    var config = settings.Value;
    if (!config.IsReadyForRegistration()) return Results.StatusCode(503);
    if (!ControlKeyMatches(context, config.ControlApiKey!)) return Results.Unauthorized();
    var meetingId = state.ReadMeetingId(callId);
    if (meetingId is null) return Results.NotFound();
    if (state.Read(callId) != "established")
        return Results.Conflict(new { code = "call_not_established" });
    var roster = await rosterClient.ReadAsync(callId, cancellationToken).ConfigureAwait(false);
    if (roster is null || roster.CallId != callId || roster.MeetingId != meetingId)
        return Results.Problem(statusCode: 502, title: "participant_roster_unavailable");
    if (state.Read(callId) != "established")
        return Results.Conflict(new { code = "call_not_established" });
    return Results.Ok(roster);
});

app.MapGet("/health", (IOptions<TeamsCaptureOptions> options) => Results.Ok(new
{
    service = "teams-capture-worker",
    status = "up",
    capture = options.Value.IsReadyForRegistration()
        ? "ready-for-authorized-join"
        : "disabled-until-tenant-registration",
    media = "service-hosted-presence-only",
    liveAudio = false
}));

app.Run();

static bool ControlKeyMatches(HttpContext context, string expected)
{
    if (expected.Length < 32) return false;
    if (!context.Request.Headers.TryGetValue("X-Teams-Control-Key", out var supplied)
        || supplied.Count != 1) return false;
    var expectedBytes = Encoding.UTF8.GetBytes(expected);
    var suppliedBytes = Encoding.UTF8.GetBytes(supplied[0] ?? string.Empty);
    return expectedBytes.Length == suppliedBytes.Length
        && CryptographicOperations.FixedTimeEquals(expectedBytes, suppliedBytes);
}

public partial class Program { }

public sealed record JoinMeetingRequest(
    string CalendarEventId,
    string ThreadId,
    string MessageId,
    string OrganizerUserId,
    string CorrelationId);
