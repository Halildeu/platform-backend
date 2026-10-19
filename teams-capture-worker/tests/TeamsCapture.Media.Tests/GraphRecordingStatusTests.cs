using System.Net;
using System.Text.Json;
using TeamsCapture.Media;
using Xunit;

namespace TeamsCapture.Media.Tests;

public class GraphRecordingStatusTests
{
    [Theory] [InlineData("ok")] [InlineData("pending")] [InlineData("context")]
    [InlineData("call")] [InlineData("operation")] [InlineData("error")] [InlineData("redirect")]
    public async Task OnlyExactCompletedRecordingOperationIssuesReceipt(string fault)
    {
        var call = new MediaCallKey(Guid.NewGuid(), Guid.NewGuid(), "call-1", Guid.NewGuid());
        using var client = new GraphRecordingStatus(new HttpClient(new BotProcessingAuthorizationTests.Handler(async request => {
            Assert.Equal("https://graph.microsoft.com/v1.0/communications/calls/call-1/updateRecordingStatus", request.RequestUri!.AbsoluteUri);
            Assert.Equal("synthetic-graph", request.Headers.Authorization!.Parameter);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            Assert.Equal("recording", body.RootElement.GetProperty("status").GetString());
            var fields = new Dictionary<string, object?> {
                ["@odata.type"] = "#microsoft.graph.updateRecordingStatusOperation", ["id"] = "operation-1",
                ["clientContext"] = fault == "context" ? "old" : body.RootElement.GetProperty("clientContext").GetString(),
                ["status"] = fault == "pending" ? "running" : "completed", ["resultInfo"] = fault == "error" ? new { code = 500 } : null
            };
            var response = BotProcessingAuthorizationTests.Reply(fields);
            response.Headers.Location = new Uri("https://graph.microsoft.com/v1.0/communications/calls/" +
                (fault == "call" ? "other" : "call-1") + "/operations/" + (fault == "operation" ? "other" : "operation-1"));
            if (fault == "redirect") response.StatusCode = HttpStatusCode.Redirect;
            return response;
        })), _ => Task.FromResult("synthetic-graph"));
        if (fault == "ok") Assert.Equal(call, (await client.StartAsync(call, default)).Call);
        else await Assert.ThrowsAsync<InvalidOperationException>(() => client.StartAsync(call, default));
    }
}
