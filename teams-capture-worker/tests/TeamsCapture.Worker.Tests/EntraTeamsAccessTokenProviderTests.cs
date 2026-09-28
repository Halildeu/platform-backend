using System.Net;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public class EntraTeamsAccessTokenProviderTests
{
    [Theory]
    [InlineData("{}")]
    [InlineData("not-json")]
    [InlineData("[]")]
    [InlineData("{\"token_type\":\"Bearer\",\"expires_in\":\"3600\",\"access_token\":\"test\"}")]
    [InlineData("{\"token_type\":\"Bearer\",\"expires_in\":30,\"access_token\":\"test\"}")]
    public async Task Rejects_invalid_or_short_lived_tokens(string json)
    {
        var handler = new Handler(json);
        Assert.Null(await Provider(handler).GetAccessTokenAsync(default));
    }

    [Fact]
    public async Task Uses_exact_tenant_and_graph_scope()
    {
        var handler = new Handler("{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"test-token\"}");
        Assert.Equal("test-token", await Provider(handler).GetAccessTokenAsync(default));
        Assert.Equal("https://login.microsoftonline.com/11111111-1111-4111-8111-111111111111/oauth2/v2.0/token", handler.Url);
        Assert.Contains("scope=https%3A%2F%2Fgraph.microsoft.com%2F.default", handler.Body);
        Assert.Contains("client_secret=test%26secret", handler.Body);
    }

    [Fact]
    public async Task Disabled_configuration_does_not_send_credentials()
    {
        var handler = new Handler("{}");
        var provider = new EntraTeamsAccessTokenProvider(new HttpClient(handler), Options.Create(new TeamsCaptureOptions()));
        Assert.Null(await provider.GetAccessTokenAsync(default));
        Assert.Null(handler.Url);
    }

    private static EntraTeamsAccessTokenProvider Provider(Handler handler) => new(new HttpClient(handler),
        Options.Create(new TeamsCaptureOptions { Enabled = true, TenantId = "11111111-1111-4111-8111-111111111111",
            ApplicationId = "22222222-2222-4222-8222-222222222222", ClientSecret = "test&secret" }));

    private sealed class Handler(string json) : HttpMessageHandler
    {
        public string? Url { get; private set; }
        public string Body { get; private set; } = "";
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Url = request.RequestUri!.ToString();
            Body = await request.Content!.ReadAsStringAsync(cancellationToken);
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json) };
        }
    }
}
