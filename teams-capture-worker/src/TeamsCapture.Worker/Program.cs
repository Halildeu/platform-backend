using TeamsCapture.Worker;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddSingleton<BotCaptureCoordinator>();

var app = builder.Build();

app.MapGet("/health", () => Results.Ok(new
{
    service = "teams-capture-worker",
    status = "up",
    capture = "disabled-until-tenant-registration"
}));

app.Run();
