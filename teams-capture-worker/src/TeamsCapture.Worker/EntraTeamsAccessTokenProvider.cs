using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

/// <summary>Server-only client credentials. No token or error body is logged or persisted.</summary>
public sealed class EntraTeamsAccessTokenProvider(
    HttpClient client, IOptions<TeamsCaptureOptions> settings) : ITeamsAccessTokenProvider
{
    public async Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken)
    {
        var options = settings.Value;
        if (!options.Enabled || !Guid.TryParse(options.TenantId, out var tenant)
            || !Guid.TryParse(options.ApplicationId, out var application)
            || string.IsNullOrWhiteSpace(options.ClientSecret)) return null;

        using var request = new HttpRequestMessage(HttpMethod.Post,
            $"https://login.microsoftonline.com/{tenant:D}/oauth2/v2.0/token")
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = application.ToString("D"),
                ["client_secret"] = options.ClientSecret,
                ["scope"] = "https://graph.microsoft.com/.default",
                ["grant_type"] = "client_credentials"
            })
        };
        using var response = await client.SendAsync(request, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        try
        {
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false));
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object
                || !root.TryGetProperty("token_type", out var type) || type.ValueKind != JsonValueKind.String
                || !string.Equals(type.GetString(), "Bearer", StringComparison.OrdinalIgnoreCase)
                || !root.TryGetProperty("expires_in", out var expires) || expires.ValueKind != JsonValueKind.Number || !expires.TryGetInt32(out var seconds) || seconds <= 60
                || !root.TryGetProperty("access_token", out var token) || token.ValueKind != JsonValueKind.String)
                return null;
            var value = token.GetString();
            return !string.IsNullOrWhiteSpace(value) && value.Length <= 16384
                && !value.Any(char.IsWhiteSpace) ? value : null;
        }
        catch (JsonException) { return null; }
    }
}
