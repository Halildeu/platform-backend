namespace TeamsCapture.Worker;

/// <summary>
/// Tenant-owned Teams configuration. Secrets remain outside source control and are
/// supplied only through the deployment secret store.
/// </summary>
public sealed class TeamsCaptureOptions
{
    public const string SectionName = "TeamsCapture";

    public bool Enabled { get; init; }

    public string? TenantId { get; init; }

    public string? ApplicationId { get; init; }

    // Supply through the institution's secret store, never a tracked settings file.
    public string? ClientSecret { get; init; }

    public string? PublicCallbackBaseUrl { get; init; }

    // Separate internal control-plane credential. Supply only through secret storage.
    public string? ControlApiKey { get; init; }

    // These paths must point to a persistent volume in a deployed worker.
    public string? CallStateFilePath { get; init; }

    public string? CalendarStateFilePath { get; init; }

    public bool IsReadyForRegistration() => Enabled
        && Guid.TryParse(TenantId, out _)
        && Guid.TryParse(ApplicationId, out _)
        && Uri.TryCreate(PublicCallbackBaseUrl, UriKind.Absolute, out var callbackUrl)
        && callbackUrl.Scheme == Uri.UriSchemeHttps
        && ControlApiKey?.Length >= 32
        && !string.IsNullOrWhiteSpace(CallStateFilePath)
        && Path.IsPathFullyQualified(CallStateFilePath)
        && !string.IsNullOrWhiteSpace(CalendarStateFilePath)
        && Path.IsPathFullyQualified(CalendarStateFilePath);
}
