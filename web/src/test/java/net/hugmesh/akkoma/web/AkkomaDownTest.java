package net.hugmesh.akkoma.web;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** With no Akkoma behind it the service still starts, stays healthy and answers with clean gateway errors. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AkkomaDownTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) { closed = s.getLocalPort(); }
        r.add("hugmesh.web.akkoma-url", () -> "http://127.0.0.1:" + closed);
        r.add("hugmesh.web.public-url", () -> "http://social.test");
        var ui = Files.createTempDirectory("ui");
        r.add("hugmesh.web.ui-dir", ui::toString);
        r.add("hugmesh.web.state-dir", () -> ui.resolve("state").toString());
    }

    @LocalServerPort int port;

    @Test
    void passthroughIsABadGatewayNotAServerError() {
        var web = WebTestClient.bindToServer(NoPool.connector()).baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(30)).build();
        web.get().uri("/api/v1/instance").exchange().expectStatus().isEqualTo(502);
        web.get().uri("/.well-known/nodeinfo").exchange().expectStatus().isEqualTo(502);
        web.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void startingASignInIsABadGatewayToo() {
        var web = WebTestClient.bindToServer(NoPool.connector()).baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(30)).build();
        web.get().uri("/bff/login").exchange().expectStatus().isEqualTo(502);
    }
}
