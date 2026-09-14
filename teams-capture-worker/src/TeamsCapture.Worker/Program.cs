using TeamsCapture.Worker;
using Microsoft.Extensions.Options;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using System.Text.Json;

var builder = WebApplication.CreateBuilder(args);
builder.Services.Configure<TeamsCaptureOptions>(
    builder.Configuration.GetSection(TeamsCaptureOptions.SectionName));
builder.Services.AddHttpClient<ITeamsAccessTokenProvider, EntraTeamsAccessTokenProvider>(client =>
    client.Timeout = TimeSpan.FromSeconds(15))
    .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });
builder.Services.AddSingleton<TeamsCallbackState>();
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

app.MapGet("/health", (IOptions<TeamsCaptureOptions> options) => Results.Ok(new
{
    service = "teams-capture-worker",
    status = "up",
    capture = options.Value.IsReadyForRegistration()
        ? "configuration-present-join-not-wired"
        : "disabled-until-tenant-registration"
}));

app.Run();

public partial class Program { }
