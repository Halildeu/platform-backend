using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

public sealed record CalendarBrowseRequest(Guid OrganizerId, DateTimeOffset From, DateTimeOffset To)
{
    public bool IsValid(DateTimeOffset now) => OrganizerId != Guid.Empty && To > From
        && To - From <= TimeSpan.FromDays(31) && From >= now.AddMinutes(-5) && To <= now.AddDays(90);
}

public sealed record CalendarEventChoice(string EventId, string Title, DateTimeOffset StartsAt, DateTimeOffset EndsAt);
public sealed record CalendarEventChoices(IReadOnlyList<CalendarEventChoice> Items, bool Truncated);
public interface ITeamsCalendarBrowser
{
    Task<CalendarEventChoices?> BrowseAsync(Guid organizerId, DateTimeOffset from, DateTimeOffset to,
        CancellationToken cancellationToken);
}

public static class TeamsCalendarBrowse
{
    public static void MapTeamsCalendarBrowse(this WebApplication app, Func<HttpContext, string, bool> authorized)
    {
        // Private service-only surface; the user-facing proxy derives OrganizerId from verified identity.
        app.MapPost("/api/teams/calendar/events", async (CalendarBrowseRequest request, HttpContext context,
            IOptions<TeamsCaptureOptions> settings, ITeamsCalendarBrowser calendar, TimeProvider clock, CancellationToken token) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            if (!authorized(context, settings.Value.ControlApiKey ?? "")) return Results.Unauthorized();
            if (!settings.Value.IsReadyForCalendarScheduling()) return Results.StatusCode(503);
            if (!request.IsValid(clock.GetUtcNow())) return Results.BadRequest(new { code = "invalid_calendar_window" });
            if (!settings.Value.CalendarOrganizerIds.Contains(request.OrganizerId)) return Results.StatusCode(403);
            var result = await calendar.BrowseAsync(request.OrganizerId, request.From, request.To, token);
            return result is null ? Results.Problem(statusCode: 502, title: "calendar_read_unavailable") : Results.Ok(result);
        });
    }
}
