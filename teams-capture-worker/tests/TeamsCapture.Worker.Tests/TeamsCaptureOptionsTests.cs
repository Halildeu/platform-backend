using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class TeamsCaptureOptionsTests
{
    [Theory]
    [InlineData("https://bot.example.com", "synthetic-credential", true)]
    [InlineData("https://bot.example.com", "", false)]
    [InlineData("https://bot.example.com", "  ", false)]
    [InlineData("http://bot.example.com", "synthetic-credential", false)]
    [InlineData("https://user:password@bot.example.com", "synthetic-credential", false)]
    [InlineData("https://bot.example.com/wrong-prefix", "synthetic-credential", false)]
    [InlineData("https://bot.example.com?token=bad", "synthetic-credential", false)]
    [InlineData("https://bot.example.com#fragment", "synthetic-credential", false)]
    [InlineData("https://localhost", "synthetic-credential", false)]
    public void Readiness_requires_credentials_and_a_callback_origin(string origin, string credential, bool ready)
    {
        var options = new TeamsCaptureOptions
        {
            Enabled = true, TenantId = Guid.NewGuid().ToString(), ApplicationId = Guid.NewGuid().ToString(),
            ClientSecret = credential, ControlApiKey = new string('k', 32), PublicCallbackBaseUrl = origin,
            CallStateFilePath = Path.GetFullPath("calls.json"), CalendarStateFilePath = Path.GetFullPath("calendar.json")
        };
        Assert.Equal(ready, options.IsReadyForRegistration());
    }
}
