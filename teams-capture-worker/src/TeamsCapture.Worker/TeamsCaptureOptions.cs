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

    // Metadata only. Automatic cleanup remains off until the operator supplies retention.
    public int? CompletedCallRetentionHours { get; init; }

    public bool IsReadyForRegistration() => Enabled
        && Guid.TryParse(TenantId, out var tenant) && tenant != Guid.Empty
        && Guid.TryParse(ApplicationId, out var application) && application != Guid.Empty
        && !string.IsNullOrWhiteSpace(ClientSecret)
        && Uri.TryCreate(PublicCallbackBaseUrl, UriKind.Absolute, out var callbackUrl)
        && callbackUrl.Scheme == Uri.UriSchemeHttps
        && string.IsNullOrEmpty(callbackUrl.UserInfo)
        && callbackUrl.AbsolutePath == "/" && string.IsNullOrEmpty(callbackUrl.Query)
        && string.IsNullOrEmpty(callbackUrl.Fragment) && !callbackUrl.IsLoopback
        && ControlApiKey?.Length >= 32
        && !string.IsNullOrWhiteSpace(CallStateFilePath)
        && Path.IsPathFullyQualified(CallStateFilePath)
        && !string.IsNullOrWhiteSpace(CalendarStateFilePath)
        && Path.IsPathFullyQualified(CalendarStateFilePath)
        && !string.Equals(Path.GetFullPath(CallStateFilePath), Path.GetFullPath(CalendarStateFilePath),
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal)
        && (CompletedCallRetentionHours is null or >= 1 and <= 2160);
}
