using TeamsCapture.Worker;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);
builder.Services.Configure<TeamsCaptureOptions>(
    builder.Configuration.GetSection(TeamsCaptureOptions.SectionName));
builder.Services.AddHttpClient<ITeamsAccessTokenProvider, EntraTeamsAccessTokenProvider>(client =>
    client.Timeout = TimeSpan.FromSeconds(15))
    .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });

var app = builder.Build();

app.MapGet("/health", (IOptions<TeamsCaptureOptions> options) => Results.Ok(new
{
    service = "teams-capture-worker",
    status = "up",
    capture = options.Value.IsReadyForRegistration()
        ? "configuration-present-join-and-callback-not-wired"
        : "disabled-until-tenant-registration"
}));

app.Run();
