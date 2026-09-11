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

    public string? PublicCallbackBaseUrl { get; init; }

    public bool IsReadyForRegistration() => Enabled
        && Guid.TryParse(TenantId, out _)
        && Guid.TryParse(ApplicationId, out _)
        && Uri.TryCreate(PublicCallbackBaseUrl, UriKind.Absolute, out var callbackUrl)
        && callbackUrl.Scheme == Uri.UriSchemeHttps;
}
