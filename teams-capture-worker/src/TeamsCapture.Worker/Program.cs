using TeamsCapture.Worker;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);
builder.Services.Configure<TeamsCaptureOptions>(
    builder.Configuration.GetSection(TeamsCaptureOptions.SectionName));

var app = builder.Build();

app.MapGet("/health", (IOptions<TeamsCaptureOptions> options) => Results.Ok(new
{
    service = "teams-capture-worker",
    status = "up",
    capture = options.Value.IsReadyForRegistration()
        ? "registration-ready-service-hosted-media"
        : "disabled-until-tenant-registration"
}));

app.Run();
