package com.botmaker.studio.services.pilot;

import com.botmaker.studio.events.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capture-free smoke test of {@link PilotServer}: verifies Javalin boots, serves the bundled pilot UI from
 * the classpath, and that the WebSocket handshake is token-gated. Deliberately does <em>not</em> open an
 * authorized WS client — that would start real screen capture (a portal prompt on Wayland). The frame loop
 * short-circuits while no client is connected, so nothing is captured here.
 */
class PilotServerTest {

    private PilotServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    @Test
    void servesStaticUiAndGatesWebSocketByToken() throws Exception {
        server = new PilotServer(new EventBus(), null, new PilotControlService(null));
        PilotServer.Endpoint ep = server.start("127.0.0.1");

        HttpClient http = HttpClient.newHttpClient();

        // Static UI is served from the classpath /pilot dir.
        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ep.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("BotPilot"), "served page should be the pilot UI");

        // A bad token must be rejected: the server closes the session right after connect.
        CountDownLatch closed = new CountDownLatch(1);
        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + ep.port() + "/ws?token=WRONG"),
                        new WebSocket.Listener() {
                            @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                                closed.countDown();
                                return null;
                            }
                            @Override public void onError(WebSocket w, Throwable error) {
                                closed.countDown();
                            }
                        })
                .get(5, TimeUnit.SECONDS);
        assertTrue(closed.await(5, TimeUnit.SECONDS), "unauthorized WS should be closed by the server");
        ws.abort();
    }
}
