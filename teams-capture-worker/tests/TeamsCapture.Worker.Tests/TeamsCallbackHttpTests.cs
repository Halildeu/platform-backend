using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public class TeamsCallbackHttpTests
{
    private const string Tenant = "11111111-1111-4111-8111-111111111111";
    private const string Audience = "22222222-2222-4222-8222-222222222222";
    private const string Issuer = "https://api.botframework.com";

    [Theory]
    [InlineData(null, Tenant, HttpStatusCode.Unauthorized)]
    [InlineData("wrong-app", Tenant, HttpStatusCode.Unauthorized)]
    [InlineData(Audience, "33333333-3333-4333-8333-333333333333", HttpStatusCode.Forbidden)]
    [InlineData(Audience, Tenant, HttpStatusCode.NoContent)]
    [InlineData(Audience, Tenant, HttpStatusCode.Unauthorized, "expired")]
    [InlineData(Audience, Tenant, HttpStatusCode.Unauthorized, "wrong-key")]
    public async Task Callback_checks_signature_audience_and_tenant(string? audience, string tenant, HttpStatusCode expected, string scenario = "valid")
    {
        using var rsa = RSA.Create(2048);
        var key = new RsaSecurityKey(rsa) { KeyId = "local-test" };
        using var otherRsa = RSA.Create(2048);
        using var factory = new Factory(key);
        using var client = factory.CreateClient();
        factory.Services.GetRequiredService<TeamsCallbackState>().Register("call-1");
        if (audience != null)
        {
            var signingKey = scenario == "wrong-key" ? new RsaSecurityKey(otherRsa) { KeyId = "local-test" } : key;
            var token = new JwtSecurityToken(Issuer, audience, [new Claim("tid", tenant)],
                DateTime.UtcNow.AddMinutes(-10), DateTime.UtcNow.AddMinutes(scenario == "expired" ? -5 : 5), new SigningCredentials(signingKey, SecurityAlgorithms.RsaSha256));
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", new JwtSecurityTokenHandler().WriteToken(token));
        }
        var response = await client.PostAsJsonAsync("/api/teams/callback", new {
            value = new[] { new { resourceUrl = "/communications/calls/call-1", resourceData = new { state = "established" } } }
        });
        Assert.Equal(expected, response.StatusCode);
        Assert.Equal(expected == HttpStatusCode.NoContent ? "established" : "establishing",
            factory.Services.GetRequiredService<TeamsCallbackState>().Read("call-1"));
    }

    private sealed class Factory(SecurityKey key) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseContentRoot(AppContext.BaseDirectory);
            builder.ConfigureLogging(logging => logging.ClearProviders());
            builder.ConfigureServices(services => {
                services.Configure<TeamsCaptureOptions>(options => { });
                services.AddSingleton(Microsoft.Extensions.Options.Options.Create(new TeamsCaptureOptions {
                    Enabled = true, TenantId = Tenant, ApplicationId = Audience
                }));
                services.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, options => {
                    var configuration = new OpenIdConnectConfiguration { Issuer = Issuer };
                    configuration.SigningKeys.Add(key);
                    options.ConfigurationManager = new StaticConfigurationManager<OpenIdConnectConfiguration>(configuration);
                    options.TokenValidationParameters.ValidAudience = Audience;
                });
            });
        }
    }
}
