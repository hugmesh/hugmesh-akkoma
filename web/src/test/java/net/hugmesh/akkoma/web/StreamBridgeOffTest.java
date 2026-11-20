package net.hugmesh.akkoma.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** With the bridge off the service does nothing special for streaming: the request goes to Akkoma untouched. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamBridgeOffTest {

    static final StubAkkoma AKKOMA = new StubAkkoma();
    static int akkomaPort;

    @AfterAll
    static void down() { AKKOMA.stop(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        synchronized (StreamBridgeOffTest.class) { if (akkomaPort == 0) akkomaPort = AKKOMA.start(); }
        r.add("hugmesh.web.akkoma-url", () -> "http://127.0.0.1:" + akkomaPort);
        r.add("hugmesh.web.public-url", () -> "http://social.test");
        var ui = Files.createTempDirectory("ui");
        r.add("hugmesh.web.ui-dir", ui::toString);
        r.add("hugmesh.web.state-dir", () -> ui.resolve("state").toString());
        r.add("hugmesh.web.stream-bridge", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired SessionStore sessions;

    @Test
    void theBridgeIsNotEvenRegistered() {
        assertThat(context.getBeanNamesForType(StreamController.class)).isEmpty();
    }

    @Test
    void streamingRequestsGoThroughTheOrdinaryProxyToAkkoma() {
        var web = WebTestClient.bindToServer(NoPool.connector()).baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(30)).build();
        var s = sessions.create("tok-1", "alice", "Alice");
        web.get().uri("/bff/user/mastodon/api/v1/streaming/user").cookie(SessionStore.COOKIE, s.id()).exchange().expectStatus().isOk();
        // forwarded as the plain path, with the token as a header: no WebSocket, no access_token in a URL
        var seen = AKKOMA.last("/api/v1/streaming/user");
        assertThat(seen).isNotNull();
        assertThat(seen.authorization()).isEqualTo("Bearer tok-1");
        assertThat(seen.query()).doesNotContain("access_token");
    }
}
