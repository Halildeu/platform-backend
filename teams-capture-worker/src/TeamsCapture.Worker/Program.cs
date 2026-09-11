using TeamsCapture.Worker;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);
builder.Services.Configure<TeamsCaptureOptions>(
    builder.Configuration.GetSection(TeamsCaptureOptions.SectionName));
builder.Services.AddSingleton<BotCaptureCoordinator>();

var app = builder.Build();

app.MapGet("/health", (IOptions<TeamsCaptureOptions> options) => Results.Ok(new
{
    service = "teams-capture-worker",
    status = "up",
    capture = options.Value.IsReadyForRegistration()
        ? "registration-ready-media-disabled"
        : "disabled-until-tenant-registration"
}));

app.Run();
