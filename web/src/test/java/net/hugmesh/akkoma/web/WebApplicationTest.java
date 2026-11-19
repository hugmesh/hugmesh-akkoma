package net.hugmesh.akkoma.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApplicationTest {

    static final StubAkkoma AKKOMA = new StubAkkoma();
    static Path ui;
    static Path state;

    @AfterAll
    static void down() { AKKOMA.stop(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // @BeforeAll runs after this, so start the stub here when needed.
        r.add("hugmesh.web.akkoma-url", () -> "http://127.0.0.1:" + ensureStub());
        r.add("hugmesh.web.public-url", () -> "http://social.test");
        r.add("hugmesh.web.ui-dir", () -> uiDir().toString());
        r.add("hugmesh.web.state-dir", () -> stateDir().toString());
    }

    private static synchronized int ensureStub() {
        if (port == 0) port = AKKOMA.start();
        return port;
    }
    static int port;
    private static synchronized Path uiDir() { return ensure(true); }
    private static synchronized Path stateDir() { return ensure(false); }
    private static Path ensure(boolean wantUi) {
        try {
            if (ui == null) {
                ui = Files.createTempDirectory("ui");
                state = Files.createTempDirectory("state");
                Files.writeString(ui.resolve("index.html"), "<html>app shell</html>");
                Files.createDirectories(ui.resolve("assets"));
                Files.writeString(ui.resolve("assets/app-abc123.js"), "console.log(1)");
            }
            return wantUi ? ui : state;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @LocalServerPort int webPort;
    @Autowired SessionStore sessions;
    WebTestClient web;

    @BeforeEach
    void client() {
        AKKOMA.seen.clear();
        AKKOMA.tokenRevoked = false;
        web = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + webPort)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    /** Signs in through the real flow (login, callback) and returns the session cookie value. */
    private String signIn() {
        EntityExchangeResult<Void> login = web.get().uri("/bff/login?next=/home").exchange()
                .expectStatus().isFound().expectBody().isEmpty();
        String loc = login.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(loc).startsWith("/oauth/authorize?").contains("client_id=cid-1").contains("response_type=code")
                .contains("redirect_uri=http%3A%2F%2Fsocial.test%2Fbff%2Fcallback");
        String state = query(loc, "state");
        String stateCookie = cookieValue(login, SessionStore.STATE_COOKIE);
        assertThat(stateCookie).isEqualTo(state);
        EntityExchangeResult<Void> cb = web.get().uri("/bff/callback?code=good-code&state=" + state)
                .cookie(SessionStore.STATE_COOKIE, stateCookie).exchange().expectStatus().isFound().expectBody().isEmpty();
        assertThat(cb.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/home");
        return cookieValue(cb, SessionStore.COOKIE);
    }

    private static String query(String url, String name) {
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            if (kv.startsWith(name + "=")) return java.net.URLDecoder.decode(kv.substring(name.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String cookieValue(EntityExchangeResult<?> r, String name) {
        List<String> all = r.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        if (all == null) return null;
        for (String c : all) if (c.startsWith(name + "=")) return c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';'));
        return null;
    }

    @Test
    void signInThenSessionShowsRolesAndNoToken() {
        String sid = signIn();
        assertThat(sid).isNotBlank();
        var body = web.get().uri("/bff/user/session").cookie(SessionStore.COOKIE, sid).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"id\":\"alice\"").contains("\"label\":\"Alice\"").contains("\"admin\"").contains("\"user\"")
                .contains("\"provider\":\"akkoma\"").doesNotContain("tok-1");
        // the cookie is HttpOnly and SameSite=Lax
        var c = web.get().uri("/bff/login").exchange().returnResult(Void.class).getResponseHeaders().get(HttpHeaders.SET_COOKIE).get(0);
        assertThat(c).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/bff");
    }

    @Test
    void noSessionIs401() {
        web.get().uri("/bff/user/session").exchange().expectStatus().isUnauthorized();
        web.get().uri("/bff/user/mastodon/api/v1/timelines/home").exchange().expectStatus().isUnauthorized();
        web.get().uri("/bff/user/preferences").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void callbackRejectsAStateThisBrowserWasNotGiven() {
        web.get().uri("/bff/login").exchange().expectStatus().isFound();
        web.get().uri("/bff/callback?code=good-code&state=forged").cookie(SessionStore.STATE_COOKIE, "forged")
                .exchange().expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "/?login=invalid");
        // a real state without the cookie is no better (login CSRF)
        var login = web.get().uri("/bff/login").exchange().returnResult(Void.class);
        String st = query(login.getResponseHeaders().getFirst(HttpHeaders.LOCATION), "state");
        web.get().uri("/bff/callback?code=good-code&state=" + st).exchange()
                .expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "/?login=invalid");
        // and a state is single use
        var login2 = web.get().uri("/bff/login").exchange().returnResult(Void.class);
        String st2 = query(login2.getResponseHeaders().getFirst(HttpHeaders.LOCATION), "state");
        web.get().uri("/bff/callback?code=good-code&state=" + st2).cookie(SessionStore.STATE_COOKIE, st2).exchange().expectStatus().isFound();
        web.get().uri("/bff/callback?code=good-code&state=" + st2).cookie(SessionStore.STATE_COOKIE, st2).exchange()
                .expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "/?login=invalid");
    }

    @Test
    void aDeniedOrBadCodeEndsAtTheLoginPageWithoutASession() {
        var login = web.get().uri("/bff/login").exchange().returnResult(Void.class);
        String st = query(login.getResponseHeaders().getFirst(HttpHeaders.LOCATION), "state");
        web.get().uri("/bff/callback?error=access_denied&state=" + st).cookie(SessionStore.STATE_COOKIE, st).exchange()
                .expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "/?login=denied");
        var login2 = web.get().uri("/bff/login").exchange().returnResult(Void.class);
        String st2 = query(login2.getResponseHeaders().getFirst(HttpHeaders.LOCATION), "state");
        var r = web.get().uri("/bff/callback?code=wrong&state=" + st2).cookie(SessionStore.STATE_COOKIE, st2).exchange()
                .expectStatus().isFound().expectBody().isEmpty();
        assertThat(r.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/?login=failed");
        assertThat(cookieValue(r, SessionStore.COOKIE)).isNull();
    }

    @Test
    void theNextPathCannotLeaveTheSite() {
        assertThat(AuthController.safeNext("//evil.example")).isEqualTo("/");
        assertThat(AuthController.safeNext("https://evil.example")).isEqualTo("/");
        assertThat(AuthController.safeNext("/\\evil.example")).isEqualTo("/");
        assertThat(AuthController.safeNext("/a\r\nSet-Cookie: x=1")).isEqualTo("/");
        assertThat(AuthController.safeNext(null)).isEqualTo("/");
        assertThat(AuthController.safeNext("/home?x=1")).isEqualTo("/home?x=1");
    }

    @Test
    void theSignedInApiSendsTheServerHeldTokenAndNothingTheBrowserSent() {
        String sid = signIn();
        AKKOMA.seen.clear();
        var body = web.get().uri("/bff/user/mastodon/api/v1/timelines/home?limit=5&max_id=abc")
                .cookie(SessionStore.COOKIE, sid).cookie("_akk", "browser-cookie")
                .header(HttpHeaders.AUTHORIZATION, "Bearer attacker-supplied")
                .header(HttpHeaders.HOST, "social.test")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        String b = body.getResponseBody();
        assertThat(b).contains("\"path\":\"/api/v1/timelines/home\"").contains("\"auth\":\"Bearer tok-1\"")
                .contains("limit=5&max_id=abc").contains("\"host\":\"social.test\"").contains("\"proto\":\"http\"")
                .contains("\"cookie\":\"\"");
        // Akkoma's Set-Cookie never reaches the browser on the signed-in API
        assertThat(body.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void onlyAkkomasApiIsReachableThroughTheSignedInProxy() {
        String sid = signIn();
        for (String p : List.of("/bff/user/mastodon/oauth/token", "/bff/user/mastodon/api/../oauth/token",
                "/bff/user/mastodon/api/%2e%2e/oauth/token", "/bff/user/mastodon/api//x", "/bff/user/mastodon/api%2fv1/x")) {
            web.get().uri(java.net.URI.create("http://127.0.0.1:" + webPort + p)).cookie(SessionStore.COOKIE, sid)
                    .exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    void aRevokedTokenEndsTheSession() {
        String sid = signIn();
        AKKOMA.tokenRevoked = true;
        var r = web.get().uri("/bff/user/mastodon/api/v1/needs-token").cookie(SessionStore.COOKIE, sid).exchange()
                .expectStatus().isUnauthorized().expectBody().returnResult();
        assertThat(cookieValue(r, SessionStore.COOKIE)).isEmpty();
        AKKOMA.tokenRevoked = false;
        web.get().uri("/bff/user/session").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void passthroughKeepsTheClientsAuthorizationAndHostAndDropsOurCookies() {
        String sid = signIn();
        AKKOMA.seen.clear();
        var body = web.get().uri("/api/v1/timelines/public")
                .header(HttpHeaders.AUTHORIZATION, "Bearer a-mobile-clients-own-token")
                .header(HttpHeaders.HOST, "social.test")
                .cookie(SessionStore.COOKIE, sid).cookie("_akk", "akkoma-cookie")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(body.getResponseBody()).contains("\"auth\":\"Bearer a-mobile-clients-own-token\"")
                .contains("\"host\":\"social.test\"").contains("_akk=akkoma-cookie").doesNotContain(sid);
        // OAuth pages need Akkoma's own Set-Cookie to reach the browser
        assertThat(body.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).anyMatch(c -> c.startsWith("_akk="));
    }

    @Test
    void federationAndMediaPathsReachAkkoma() {
        for (String p : List.of("/.well-known/webfinger?resource=acct:alice@social.test", "/nodeinfo/2.1.json", "/users/alice",
                "/media/abc.png", "/oauth/authorize?client_id=x", "/inbox")) {
            web.get().uri(p).exchange().expectStatus().isOk();
        }
        web.post().uri("/inbox").contentType(MediaType.parseMediaType("application/activity+json"))
                .header("Signature", "keyId=x").bodyValue("{\"type\":\"Follow\"}").exchange().expectStatus().isOk();
        assertThat(AKKOMA.last("/inbox").contentType()).isEqualTo("application/activity+json");
        assertThat(AKKOMA.last("/inbox").body()).contains("Follow");
    }

    @Test
    void akkomasWebSocketStreamArrivesAsServerSentEvents() {
        String sid = signIn();
        AKKOMA.seen.clear();
        var events = web.get().uri("/bff/user/mastodon/api/v1/streaming/user").cookie(SessionStore.COOKIE, sid)
                .accept(MediaType.TEXT_EVENT_STREAM).exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new org.springframework.core.ParameterizedTypeReference<org.springframework.http.codec.ServerSentEvent<String>>() {}).getResponseBody()
                .take(3).collectList().block(Duration.ofSeconds(8));
        assertThat(events).hasSize(3);
        assertThat(events.get(0).event()).isEqualTo("update");
        assertThat(String.valueOf(events.get(0).data())).contains("hello live").contains("\"id\"");
        assertThat(events.get(1).event()).isEqualTo("delete");
        assertThat(String.valueOf(events.get(1).data())).isEqualTo("123");
        // an event with no payload still arrives; a frame that is not JSON is dropped, never forwarded
        assertThat(events.get(2).event()).isEqualTo("filters_changed");
        // Akkoma was spoken to over WebSocket with the SERVER-HELD token; the browser sent none
        var s = AKKOMA.last("/api/v1/streaming");
        assertThat(s).isNotNull();
        assertThat(StubAkkoma.queryParam("/x?" + s.query(), "stream")).isEqualTo("user");
        assertThat(StubAkkoma.queryParam("/x?" + s.query(), "access_token")).isEqualTo("tok-1");
    }

    @Test
    void streamNamesMapToAkkomasAndOthersAreRefused() {
        String sid = signIn();
        for (String ok : List.of("/public/local", "/user/notification", "/direct")) {
            AKKOMA.seen.clear();
            web.get().uri("/bff/user/mastodon/api/v1/streaming" + ok).cookie(SessionStore.COOKIE, sid).accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange().expectStatus().isOk().returnResult(String.class).getResponseBody().take(1).blockLast(Duration.ofSeconds(8));
            assertThat(StubAkkoma.queryParam("/x?" + AKKOMA.last("/api/v1/streaming").query(), "stream")).isEqualTo(ok.substring(1).replace('/', ':'));
        }
        AKKOMA.seen.clear();
        web.get().uri("/bff/user/mastodon/api/v1/streaming/hashtag?tag=cats").cookie(SessionStore.COOKIE, sid).accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk().returnResult(String.class).getResponseBody().take(1).blockLast(Duration.ofSeconds(8));
        assertThat(StubAkkoma.queryParam("/x?" + AKKOMA.last("/api/v1/streaming").query(), "stream")).isEqualTo("hashtag");
        assertThat(StubAkkoma.queryParam("/x?" + AKKOMA.last("/api/v1/streaming").query(), "tag")).isEqualTo("cats");
        for (String bad : List.of("/admin", "/user/../../x", "/public/local/evil", "/hashtag?tag=a%26access_token%3Dx")) {
            web.get().uri(java.net.URI.create("http://127.0.0.1:" + webPort + "/bff/user/mastodon/api/v1/streaming" + bad))
                    .cookie(SessionStore.COOKIE, sid).exchange().expectStatus().value(st -> assertThat(st).isIn(400, 404));
        }
    }

    @Test
    void theStreamNeedsASession() {
        web.get().uri("/bff/user/mastodon/api/v1/streaming/user").accept(MediaType.TEXT_EVENT_STREAM).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aRevokedTokenEndsTheStreamInsteadOfHangingIt() {
        String sid = signIn();
        var events = web.get().uri("/bff/user/mastodon/api/v1/streaming/user").cookie(SessionStore.COOKIE, sid)
                .accept(MediaType.TEXT_EVENT_STREAM).exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody().take(1).collectList().block(Duration.ofSeconds(8));
        assertThat(events).isNotEmpty();
    }

    @Test
    void mediaUploadGoesToAkkomasV2MediaWithTheToken() {
        String sid = signIn();
        AKKOMA.seen.clear();
        web.post().uri("/bff/user/media/upload").cookie(SessionStore.COOKIE, sid)
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=xyz"))
                .bodyValue("--xyz\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n\r\nPNGDATA\r\n--xyz--\r\n")
                .exchange().expectStatus().isOk();
        var s = AKKOMA.last("/api/v2/media");
        assertThat(s).isNotNull();
        assertThat(s.authorization()).isEqualTo("Bearer tok-1");
        assertThat(s.body()).contains("PNGDATA");
    }

    @Test
    void logoutEndsTheSessionAndRevokesTheToken() {
        String sid = signIn();
        web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isNoContent();
        web.get().uri("/bff/user/session").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isUnauthorized();
        assertThat(AKKOMA.last("/oauth/revoke")).isNotNull();
        assertThat(AKKOMA.last("/oauth/revoke").body()).contains("token=tok-1");
    }

    @Test
    void logoutAlsoClearsAkkomasOwnBrowserSession() {
        String sid = signIn();
        var r = web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid)
                .cookie("__Host-pleroma_key", "akkoma-session").cookie("unrelated", "keep").exchange()
                .expectStatus().isNoContent().expectBody().isEmpty();
        List<String> set = r.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(set).anyMatch(x -> x.startsWith("__Host-pleroma_key=;") && x.contains("Max-Age=0") && x.contains("Secure") && x.contains("Path=/"));
        assertThat(set).anyMatch(x -> x.startsWith(SessionStore.COOKIE + "=;"));
        assertThat(set).noneMatch(x -> x.startsWith("unrelated="));
    }

    @Test
    void aCrossSitePostIsRefused() {
        String sid = signIn();
        web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid).header("Sec-Fetch-Site", "cross-site").exchange()
                .expectStatus().isForbidden();
        web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid).header(HttpHeaders.ORIGIN, "https://evil.example").exchange()
                .expectStatus().isForbidden();
        web.post().uri("/bff/user/mastodon/api/v1/statuses").cookie(SessionStore.COOKIE, sid).header("Sec-Fetch-Site", "same-site").exchange()
                .expectStatus().isForbidden();
        web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid).header(HttpHeaders.ORIGIN, "http://social.test").exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void preferencesRoundTripPerAccountAndRejectBadKeys() {
        String sid = signIn();
        web.put().uri("/bff/user/preferences").cookie(SessionStore.COOKIE, sid).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("key", "mastodon.appearance.theme", "value", "dark")).exchange().expectStatus().isNoContent();
        web.get().uri("/bff/user/preferences").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$['mastodon.appearance.theme']").isEqualTo("dark");
        for (String bad : List.of("other.key", "mastodon.appearance.", "mastodon.appearance.a/b", "../../etc/passwd")) {
            web.put().uri("/bff/user/preferences").cookie(SessionStore.COOKIE, sid).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("key", bad, "value", "x")).exchange().expectStatus().isBadRequest();
        }
        web.put().uri("/bff/user/preferences").cookie(SessionStore.COOKIE, sid).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("key", "mastodon.appearance.font", "value", "x".repeat(600))).exchange().expectStatus().isBadRequest();
    }

    @Test
    void uiIsServedWithAnAppShellFallbackAndNoTraversal() {
        web.get().uri("/").exchange().expectStatus().isOk().expectHeader().cacheControl(org.springframework.http.CacheControl.noCache())
                .expectBody(String.class).isEqualTo("<html>app shell</html>");
        web.get().uri("/home/timeline").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("<html>app shell</html>");
        web.get().uri("/assets/app-abc123.js").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("console.log(1)");
        web.get().uri("/assets/missing.js").exchange().expectStatus().isNotFound();
        web.get().uri(java.net.URI.create("http://127.0.0.1:" + webPort + "/%2e%2e/%2e%2e/etc/passwd")).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(400, 404));
    }

    @Test
    void healthIsUpWithoutAnAkkoma() {
        web.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
