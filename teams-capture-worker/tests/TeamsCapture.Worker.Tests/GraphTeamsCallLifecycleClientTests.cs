using System.Net;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class GraphTeamsCallLifecycleClientTests
{
    [Theory]
    [InlineData(false, HttpStatusCode.OK, TeamsCallOperationResult.Succeeded)]
    [InlineData(true, HttpStatusCode.NoContent, TeamsCallOperationResult.Succeeded)]
    [InlineData(false, HttpStatusCode.NotFound, TeamsCallOperationResult.Ended)]
    [InlineData(true, HttpStatusCode.NotFound, TeamsCallOperationResult.Ended)]
    [InlineData(false, HttpStatusCode.Unauthorized, TeamsCallOperationResult.Unconfirmed)]
    [InlineData(true, HttpStatusCode.ServiceUnavailable, TeamsCallOperationResult.Unconfirmed)]
    [InlineData(true, HttpStatusCode.Redirect, TeamsCallOperationResult.Unconfirmed)]
    public async Task Uses_expected_Graph_operation_and_confirms_only_documented_status(bool leave, HttpStatusCode status, TeamsCallOperationResult expected)
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var handler = new Handler(status);
        var lifecycle = new GraphTeamsCallLifecycleClient(new HttpClient(handler), new Token(), fixture.Options);
        var result = leave ? await lifecycle.LeaveAsync("call-1", CancellationToken.None)
            : await lifecycle.KeepAliveAsync("call-1", CancellationToken.None);
        Assert.Equal(expected, result);
        Assert.Equal(leave ? HttpMethod.Delete : HttpMethod.Post, handler.Method);
        Assert.Equal("https://graph.microsoft.com/v1.0/communications/calls/call-1" + (leave ? "" : "/keepAlive"), handler.Url);
        Assert.False(handler.HadBody);
    }

    [Theory]
    [InlineData("../other")]
    [InlineData("https://example.com")]
    [InlineData("")]
    public async Task Invalid_call_never_contacts_Graph(string callId)
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var handler = new Handler(HttpStatusCode.NoContent);
        var lifecycle = new GraphTeamsCallLifecycleClient(new HttpClient(handler), new Token(), fixture.Options);
        Assert.Equal(TeamsCallOperationResult.Unconfirmed, await lifecycle.LeaveAsync(callId, CancellationToken.None));
        Assert.Null(handler.Url);
    }

    [Fact]
    public async Task Disabled_worker_does_not_send_keepAlive()
    {
        var handler = new Handler(HttpStatusCode.OK);
        var lifecycle = new GraphTeamsCallLifecycleClient(new HttpClient(handler), new Token(), Options.Create(new TeamsCaptureOptions()));
        Assert.Equal(TeamsCallOperationResult.Unconfirmed, await lifecycle.KeepAliveAsync("call-1", CancellationToken.None));
        Assert.Null(handler.Url);
    }

    private sealed class Token : ITeamsAccessTokenProvider
    {
        public Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken) => Task.FromResult<string?>("synthetic-token");
    }
    private sealed class Handler(HttpStatusCode status) : HttpMessageHandler
    {
        public string? Url { get; private set; }
        public HttpMethod? Method { get; private set; }
        public bool HadBody { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Url = request.RequestUri?.ToString(); Method = request.Method; HadBody = request.Content is not null;
            Assert.Equal("Bearer", request.Headers.Authorization?.Scheme);
            return Task.FromResult(new HttpResponseMessage(status));
        }
    }
}
