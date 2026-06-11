package com.example.endpointadmin.remoteaccess;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Faz 22.6 remote-access feature flag (ADR-0034 #1388 / D10). <b>Disabled by default.</b>
 *
 * <p>Even with {@code enabled=true}, no live session may open until the §11/D10 acceptance gate
 * (broker negative-tests + recording fail-closed + D29-EA) is met. This skeleton ships the
 * policy/state-machine/token/audit contracts only — there is no tunnel runtime to enable yet.
 *
 * <p>Bound from {@code endpoint-admin.remote-access.*} (env {@code ENDPOINT_ADMIN_REMOTE_ACCESS_ENABLED}).
 */
@ConfigurationProperties(prefix = "endpoint-admin.remote-access")
public class RemoteAccessProperties {

    /** Master switch. Default false — runtime stays off until #1388 live-acceptance (D10). */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
