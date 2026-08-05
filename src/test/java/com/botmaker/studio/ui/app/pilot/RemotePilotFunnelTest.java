package com.botmaker.studio.ui.app.pilot;

import com.botmaker.studio.ui.app.pilot.RemotePilotUi.FunnelIssue;
import com.botmaker.studio.ui.app.pilot.RemotePilotUi.PilotMode;
import com.botmaker.studio.ui.app.pilot.RemotePilotUi.PilotOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pure pieces of the Remote Pilot bring-up: turning a {@code tailscale funnel} error into the step of
 * the setup wizard to highlight, and deriving the pairing URL from its parts.
 *
 * <p>Both were untested private statics on {@code UIManager}. The URL half in particular was a regex rewrite
 * ({@code replaceFirst("token=[^&]*$", …)}) that only worked while the token happened to be the last query
 * parameter — these assertions pin the parts-based construction that replaced it.
 */
class RemotePilotFunnelTest {

    @Test
    void classifiesTheOperatorGrantAheadOfEverythingElse() {
        assertEquals(FunnelIssue.NEEDS_OPERATOR,
                RemotePilotUi.classifyFunnel("Funnel: must be run as operator, or with sudo"));
        // Tailscale often names two blockers in one line. The operator grant is the one the user has to do
        // first — nothing else can be attempted without it — so it wins the classification.
        assertEquals(FunnelIssue.NEEDS_OPERATOR,
                RemotePilotUi.classifyFunnel("HTTPS cert unavailable; run tailscale set --operator first"));
    }

    @Test
    void classifiesTheHttpsCertificateBlocker() {
        assertEquals(FunnelIssue.NO_HTTPS_CERT,
                RemotePilotUi.classifyFunnel("HTTPS is not enabled in the admin panel"));
        assertEquals(FunnelIssue.NO_HTTPS_CERT,
                RemotePilotUi.classifyFunnel("could not get cert for host.tailnet.ts.net"));
    }

    @Test
    void classifiesTheAclGrantAndTheSignedOutCase() {
        assertEquals(FunnelIssue.NOT_ENABLED,
                RemotePilotUi.classifyFunnel("Funnel is not enabled for this tailnet"));
        assertEquals(FunnelIssue.LOGGED_OUT, RemotePilotUi.classifyFunnel("you are not logged in"));
    }

    @Test
    void unrecognisedAndAbsentErrorsFallBackToOther() {
        assertEquals(FunnelIssue.OTHER, RemotePilotUi.classifyFunnel("something we have never seen"));
        assertEquals(FunnelIssue.OTHER, RemotePilotUi.classifyFunnel(null));
    }

    @Test
    void buildsThePairingUrlFromTheBaseAndTheToken() {
        PilotOutcome direct = new PilotOutcome("http://100.64.0.7:8123", "abc", PilotMode.TAILNET_DIRECT, null, null);
        assertEquals("http://100.64.0.7:8123/?token=abc", direct.url());

        PilotOutcome funnel = new PilotOutcome("https://box.tail1234.ts.net", "xyz", PilotMode.FUNNEL_HTTPS, null,
                new RemotePilotUi.FunnelDiag(true, true, FunnelIssue.NONE));
        assertEquals("https://box.tail1234.ts.net/?token=xyz", funnel.url());
    }

    @Test
    void resettingTheTokenRebuildsTheUrlAndKeepsEverythingElse() {
        PilotOutcome original =
                new PilotOutcome("http://100.64.0.7:8123", "old-token", PilotMode.TAILNET_DIRECT, "boom", null);
        PilotOutcome refreshed = original.withToken("new-token");

        assertEquals("http://100.64.0.7:8123/?token=new-token", refreshed.url());
        assertEquals(original.baseUrl(), refreshed.baseUrl());
        assertEquals(original.mode(), refreshed.mode());
        assertEquals(original.funnelError(), refreshed.funnelError());
        assertNotEquals(original.token(), refreshed.token());
    }

    @Test
    void aResetNeverLeavesTheOldTokenAnywhereInTheUrl() {
        // What the old regex could not promise: anchored at the end of the string, it rewrote only a token
        // that was the last query parameter and silently left any other occurrence behind.
        PilotOutcome refreshed =
                new PilotOutcome("http://100.64.0.7:8123", "old-token", PilotMode.ALL_INTERFACES, null, null)
                        .withToken("new-token");
        assertTrue(refreshed.url().endsWith("token=new-token"), refreshed.url());
        assertTrue(!refreshed.url().contains("old-token"), refreshed.url());
    }
}
