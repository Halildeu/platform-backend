# Build context is teams-capture-worker, restricted by its allowlist .dockerignore.
FROM mcr.microsoft.com/dotnet/sdk:8.0-bookworm-slim@sha256:78235e09001f52b6592c458ac010775ebac6725422e80cd0c1650590f67b2743 AS build
WORKDIR /source
COPY NuGet.Config ./
COPY src/TeamsCapture.Worker/TeamsCapture.Worker.csproj src/TeamsCapture.Worker/
RUN dotnet restore src/TeamsCapture.Worker/TeamsCapture.Worker.csproj --configfile NuGet.Config
COPY src/TeamsCapture.Worker/*.cs src/TeamsCapture.Worker/
RUN dotnet publish src/TeamsCapture.Worker/TeamsCapture.Worker.csproj -c Release --no-restore -p:UseAppHost=false -o /out

FROM mcr.microsoft.com/dotnet/aspnet:8.0-bookworm-slim@sha256:2f202e1169ec507bdc07007cf68c14d0ff3a098110b17c460a60185e1f36a9d1
ARG SOURCE_COMMIT
LABEL org.opencontainers.image.source="https://github.com/Halildeu/platform-backend" \
      org.opencontainers.image.revision="${SOURCE_COMMIT}" \
      org.opencontainers.image.title="Meeting Intelligence Teams control worker"
WORKDIR /app
RUN mkdir -p /var/lib/teams-capture && chown 1654:1654 /var/lib/teams-capture
COPY --from=build /out/ ./
ENV ASPNETCORE_HTTP_PORTS=8080 \
    TeamsCapture__Enabled=false \
    TeamsCapture__CallStateFilePath=/var/lib/teams-capture/calls.json \
    TeamsCapture__CalendarStateFilePath=/var/lib/teams-capture/calendar.json
USER 1654:1654
EXPOSE 8080
ENTRYPOINT ["dotnet", "TeamsCapture.Worker.dll"]
