package net.hugmesh.akkoma.web;

import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/** A tiny stand-in for Akkoma: the endpoints the web service calls, recording what it was sent. */
final class StubAkkoma {

    record Seen(String method, String path, String query, String authorization, String cookie, String host,
                String forwardedProto, String contentType, String body) {}

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile boolean tokenRevoked;
    private DisposableServer server;

    int start() {
        server = HttpServer.create().host("127.0.0.1").port(0).route(r -> {
            r.post("/api/v1/apps", (q, s) -> record(q, s, "{\"client_id\":\"cid-1\",\"client_secret\":\"secret-1\"}", "application/json"));
            r.post("/oauth/token", (q, s) -> record(q, s, null, "application/json"));
            r.post("/oauth/revoke", (q, s) -> record(q, s, "{}", "application/json"));
            r.get("/api/v1/accounts/verify_credentials", (q, s) -> record(q, s, null, "application/json"));
            // Akkoma's streaming API is a WebSocket only: two frames in its own format, then it holds the socket open
            r.get("/api/v1/streaming", (q, res) -> {
                seen.add(snapshot(q, ""));
                if (!"Bearer tok-1".equals("Bearer " + queryParam(q.uri(), "access_token"))) return res.status(401).send();
                return res.sendWebsocket((in, out) -> out.sendString(Flux.just(
                        "{\"event\":\"update\",\"payload\":\"{\\\"id\\\":\\\"1\\\",\\\"content\\\":\\\"hello live\\\"}\",\"stream\":[\"user\"]}",
                        "{\"event\":\"delete\",\"payload\":\"123\",\"stream\":[\"user\"]}",
                        "{\"event\":\"filters_changed\",\"stream\":[\"user\"]}",
                        "this is not json")).then(Mono.never()));
            });
            r.route(q -> true, (q, s) -> record(q, s, null, "application/json"));
        }).bindNow();
        return server.port();
    }

    void stop() {
        if (server != null) server.disposeNow();
    }

    Seen last(String path) {
        for (int i = seen.size() - 1; i >= 0; i--) if (seen.get(i).path().equals(path)) return seen.get(i);
        return null;
    }

    private Seen snapshot(HttpServerRequest q, String body) {
        String uri = q.uri();
        int i = uri.indexOf('?');
        return new Seen(q.method().name(), i < 0 ? uri : uri.substring(0, i), i < 0 ? "" : uri.substring(i + 1),
                q.requestHeaders().get("Authorization"), q.requestHeaders().get("Cookie"), q.requestHeaders().get("Host"),
                q.requestHeaders().get("X-Forwarded-Proto"), q.requestHeaders().get("Content-Type"), body);
    }

    private Mono<Void> record(HttpServerRequest q, HttpServerResponse s, String fixed, String ctype) {
        return q.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty("").flatMap(body -> {
            Seen seenReq = snapshot(q, body);
            seen.add(seenReq);
            String path = seenReq.path();
            if (path.equals("/oauth/token")) {
                if (!body.contains("code=good-code")) return s.status(400).sendString(Mono.just("{\"error\":\"invalid_grant\"}")).then();
                return s.header("Content-Type", ctype).sendString(Mono.just("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\"}")).then();
            }
            if (path.equals("/api/v1/accounts/verify_credentials")) {
                String a = seenReq.authorization();
                if (tokenRevoked || a == null || !a.equals("Bearer tok-1")) return s.status(401).sendString(Mono.just("{\"error\":\"invalid_token\"}")).then();
                return s.header("Content-Type", ctype).sendString(Mono.just(
                        "{\"acct\":\"alice\",\"username\":\"alice\",\"display_name\":\"Alice\",\"pleroma\":{\"is_admin\":true,\"is_moderator\":false}}")).then();
            }
            if (path.equals("/api/v1/needs-token")) {
                if (tokenRevoked) return s.status(401).sendString(Mono.just("{\"error\":\"invalid_token\"}")).then();
            }
            if (fixed != null) return s.header("Content-Type", ctype).sendString(Mono.just(fixed)).then();
            // an echo of what Akkoma received, so tests can see exactly what the proxy sent
            String echo = "{\"path\":\"" + seenReq.path() + "\",\"auth\":\"" + nz(seenReq.authorization()) + "\",\"cookie\":\""
                    + nz(seenReq.cookie()) + "\",\"host\":\"" + nz(seenReq.host()) + "\",\"proto\":\"" + nz(seenReq.forwardedProto())
                    + "\",\"query\":\"" + seenReq.query() + "\",\"body\":\"" + body.replace("\"", "'").replace("\r", "").replace("\n", "|") + "\"}";
            return s.header("Content-Type", ctype).header("Set-Cookie", "_akk=1; Path=/").sendString(Mono.just(echo)).then();
        });
    }

    static String queryParam(String uri, String name) {
        int i = uri.indexOf('?');
        if (i < 0) return "";
        for (String kv : uri.substring(i + 1).split("&")) {
            if (kv.startsWith(name + "=")) return java.net.URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @SuppressWarnings("unused") private static io.netty.buffer.ByteBuf buf(String s) { return Unpooled.copiedBuffer(s, StandardCharsets.UTF_8); }
}
