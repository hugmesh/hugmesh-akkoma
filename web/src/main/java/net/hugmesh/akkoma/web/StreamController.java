package net.hugmesh.akkoma.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * STANDALONE ONLY (hugmesh.web.stream-bridge, on by default; off = this class is not even registered).
 * Inside HugMesh nothing changes: the web UI there reaches Akkoma through the mesh's own gateway and BFF proxy,
 * and those, not this service, carry the stream.
 *
 * Live updates. Akkoma serves its streaming API over WebSocket only (/api/v1/streaming), while the web UI
 * reads Server-Sent Events from /api/v1/streaming/{stream}, the form Mastodon's HTTP streaming uses and the one
 * a browser can authenticate with an HttpOnly cookie. This bridges the two: it opens Akkoma's WebSocket with
 * the signed-in user's token (which never reaches the browser) and re-emits each frame as an SSE event.
 *
 * <pre>
 *   Akkoma  {"event":"update","payload":"{...status json...}","stream":["user"]}
 *   here    event: update
 *           data: {...status json...}
 * </pre>
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "hugmesh.web.stream-bridge", havingValue = "true", matchIfMissing = true)
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The streams Akkoma offers, as the Mastodon API names them. */
    private static final Set<String> STREAMS = Set.of(
            "user", "user:notification", "public", "public:local", "public:remote", "public:media",
            "public:local:media", "public:remote:media", "hashtag", "hashtag:local", "list", "direct");
    private static final Pattern SIMPLE = Pattern.compile("^[A-Za-z0-9_\\-]{1,100}$");
    static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final SessionStore sessions;
    private final WebProperties props;
    private final ReactorNettyWebSocketClient ws = new ReactorNettyWebSocketClient();

    public StreamController(SessionStore sessions, WebProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    /** `/streaming/user`, `/streaming/public/local`, `/streaming/hashtag?tag=x`, `/streaming/list?list=1`, ... */
    // No `produces`: a refusal (401/400) must be an ordinary empty response. Declaring text/event-stream on the mapping
    // made those empty answers go out as an event stream with no events, which can leave the client waiting.
    @GetMapping("/bff/user/mastodon/api/v1/streaming/{*rest}")
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> stream(
            @PathVariable("rest") String rest,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "list", required = false) String list,
            ServerWebExchange ex) {
        var session = sessions.from(ex);
        if (session.isEmpty()) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        String stream = rest.startsWith("/") ? rest.substring(1) : rest;
        stream = stream.replace('/', ':');
        if (!STREAMS.contains(stream)
                || (tag != null && !SIMPLE.matcher(tag).matches()) || (list != null && !SIMPLE.matcher(list).matches())) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }
        URI target = upstream(stream, tag, list, session.get().token());
        Flux<ServerSentEvent<String>> events = Flux.<ServerSentEvent<String>>create(sink -> {
            var d = ws.execute(target, in -> in.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(frame -> emit(sink, frame))
                            .then())
                    .subscribe(v -> {}, e -> {
                        // a failed handshake (a revoked token, Akkoma down) ends the stream; the UI reconnects
                        log.warn("[stream] {} ended: {}", session.get().acct(), e.toString());
                        sink.complete();
                    }, sink::complete);
            sink.onDispose(d);
        }, FluxSink.OverflowStrategy.LATEST);
        // A comment line now and then keeps idle connections open through proxies and tells the browser we are alive.
        Flux<ServerSentEvent<String>> beat = Flux.interval(HEARTBEAT).map(i -> ServerSentEvent.<String>builder().comment("thump").build());
        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(Flux.merge(events, beat).takeUntilOther(events.ignoreElements().then(Mono.just(1)).filter(x -> false))));
    }

    private URI upstream(String stream, String tag, String list, String token) {
        String base = props.akkomaUrl();
        String ws = base.startsWith("https://") ? "wss://" + base.substring(8) : "ws://" + base.substring(base.indexOf("://") + 3);
        StringBuilder q = new StringBuilder("?stream=").append(URLEncoder.encode(stream, StandardCharsets.UTF_8));
        if (tag != null) q.append("&tag=").append(URLEncoder.encode(tag, StandardCharsets.UTF_8));
        if (list != null) q.append("&list=").append(URLEncoder.encode(list, StandardCharsets.UTF_8));
        q.append("&access_token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));
        return URI.create(ws + "/api/v1/streaming" + q);
    }

    /** One Akkoma frame to one SSE event. Unknown or malformed frames are dropped, never forwarded raw. */
    static void emit(FluxSink<ServerSentEvent<String>> sink, String frame) {
        try {
            JsonNode j = JSON.readTree(frame);
            String event = j.path("event").asText("");
            if (event.isBlank() || !SIMPLE.matcher(event).matches()) return;
            JsonNode p = j.get("payload");
            String payload = p == null || p.isNull() ? "" : p.isTextual() ? p.asText() : p.toString();
            sink.next(ServerSentEvent.<String>builder().event(event).data(payload).build());
        } catch (Exception e) {
            log.debug("[stream] dropped a frame that is not JSON");
        }
    }

    @SuppressWarnings("unused") private static final List<String> NONE = List.of();
}
