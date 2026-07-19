package com.example.endpointadmin.remoteaccess.preflight;

/** Non-interchangeable GitHub OIDC profiles accepted by endpoint-admin. */
public enum ViewOnlyGithubOidcProfile {
    PREFLIGHT("preflight", "faz22-view-only-preflight", "github-hosted", false),
    AUTHORIZATION("authorization", "faz22-view-only-checkpoint-lease", "github-hosted", true),
    EXECUTOR("executor", "faz22-view-only-checkpoint", "self-hosted", false);

    private final String receiptName;
    private final String audience;
    private final String runnerEnvironment;
    private final boolean protectedEnvironment;

    ViewOnlyGithubOidcProfile(String receiptName,
                              String audience,
                              String runnerEnvironment,
                              boolean protectedEnvironment) {
        this.receiptName = receiptName;
        this.audience = audience;
        this.runnerEnvironment = runnerEnvironment;
        this.protectedEnvironment = protectedEnvironment;
    }

    public String receiptName() {
        return receiptName;
    }

    public String audience() {
        return audience;
    }

    public String runnerEnvironment() {
        return runnerEnvironment;
    }

    public boolean protectedEnvironment() {
        return protectedEnvironment;
    }
}
