package net.hugmesh.akkoma.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Sign-in with Akkoma's own OAuth (authorisation code), and the session the UI reads. */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    /** Akkoma's own session cookie, as named with and without the __Host-/__Secure- prefixes. */
    private static final java.util.regex.Pattern AKKOMA_SESSION = java.util.regex.Pattern.compile("^(__Host-|__Secure-)?_?pleroma_key$");

    private final WebProperties props;
    private final SessionStore sessions;
    private final OAuthAppRegistry apps;
    private final AkkomaClient akkoma;

    public AuthController(WebProperties props, SessionStore sessions, OAuthAppRegistry apps, AkkomaClient akkoma) {
        this.props = props;
        this.sessions = sessions;
        this.apps = apps;
        this.akkoma = akkoma;
    }

    /** Starts the sign-in: sends the browser to Akkoma's authorisation page on this same origin. */
    @GetMapping("/bff/login")
    public Mono<ResponseEntity<Void>> login(@RequestParam(name = "next", required = false) String next) {
        return apps.app().map(app -> {
            String state = sessions.newLoginState(safeNext(next));
            String url = "/oauth/authorize?response_type=code"
                    + "&client_id=" + enc(app.clientId())
                    + "&redirect_uri=" + enc(app.redirectUri())
                    + "&scope=" + enc(props.scopes())
                    + "&state=" + enc(state);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, url)
                    .header(HttpHeaders.SET_COOKIE, cookie(SessionStore.STATE_COOKIE, state, 600, "/bff").toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .<Void>build();
        }).onErrorResume(e -> {
            log.warn("[login] cannot start: {}", e.toString());
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).<Void>build());
        });
    }

    /** Akkoma sends the browser back here with the code. */
    @GetMapping("/bff/callback")
    public Mono<ResponseEntity<Void>> callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            ServerWebExchange ex) {
        var stateCookie = ex.getRequest().getCookies().getFirst(SessionStore.STATE_COOKIE);
        String cookieState = stateCookie == null ? null : stateCookie.getValue();
        // The state must match what this browser was given AND be one this service issued, once.
        var next = sessions.consumeLoginState(state);
        if (state == null || cookieState == null || !cookieState.equals(state) || next.isEmpty()) {
            return Mono.just(redirect("/?login=invalid", clearState()));
        }
        if (error != null || code == null || code.isBlank()) {
            return Mono.just(redirect("/?login=denied", clearState()));
        }
        return apps.app()
                .flatMap(app -> akkoma.exchangeCode(app, code))
                .flatMap(token -> akkoma.verifyCredentials(token).map(acct -> {
                    String name = acct.path("acct").asText("");
                    String label = acct.path("display_name").asText("").isBlank()
                            ? acct.path("username").asText(name) : acct.path("display_name").asText();
                    var s = sessions.create(token, name, label);
                    long ttl = props.sessionTtl().toSeconds();
                    return ResponseEntity.status(HttpStatus.FOUND)
                            .header(HttpHeaders.LOCATION, next.get())
                            .header(HttpHeaders.SET_COOKIE, cookie(SessionStore.COOKIE, s.id(), ttl, "/").toString())
                            .header(HttpHeaders.SET_COOKIE, clearState().toString())
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .<Void>build();
                }))
                .onErrorResume(e -> {
                    log.warn("[login] sign-in failed: {}", e.toString());
                    return Mono.just(redirect("/?login=failed", clearState()));
                });
    }

    @PostMapping("/bff/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange ex) {
        var s = sessions.from(ex);
        s.ifPresent(x -> sessions.remove(x.id()));
        Mono<Void> revoke = s.map(x -> apps.app().flatMap(a -> akkoma.revoke(a, x.token())).onErrorResume(e -> Mono.empty()))
                .orElse(Mono.empty());
        // Akkoma keeps its own browser session (its sign-in page reuses it), so without this a "Sign in" after
        // "Sign out" would sign straight back in with no password: not acceptable on a shared computer.
        List<String> expire = new ArrayList<>();
        expire.add(cookie(SessionStore.COOKIE, "", 0, "/").toString());
        ex.getRequest().getCookies().values().stream().flatMap(List::stream)
                .filter(c -> AKKOMA_SESSION.matcher(c.getName()).matches())
                .forEach(c -> expire.add(ResponseCookie.from(c.getName(), "").httpOnly(true).path("/").maxAge(0)
                        // __Host- and __Secure- cookies can only be replaced by a Secure one
                        .secure(props.secureCookies() || c.getName().startsWith("__")).sameSite("Lax").build().toString()));
        return revoke.then(Mono.fromSupplier(() -> {
            var b = ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store");
            expire.forEach(c -> b.header(HttpHeaders.SET_COOKIE, c));
            return b.<Void>build();
        }));
    }

    /** The session the UI shows. Akkoma is asked again each time, so a revoked token ends the session. */
    @GetMapping("/bff/user/session")
    public Mono<ResponseEntity<Map<String, Object>>> session(ServerWebExchange ex) {
        var s = sessions.from(ex);
        if (s.isEmpty()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.CACHE_CONTROL, "no-store").build());
        }
        return akkoma.verifyCredentials(s.get().token())
                .map(acct -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(view(s.get(), acct)))
                .onErrorResume(e -> {
                    // Akkoma refused the token: the sign-in is over. Any other failure keeps the session.
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException w
                            && (w.getStatusCode().value() == 401 || w.getStatusCode().value() == 403)) {
                        sessions.remove(s.get().id());
                        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .header(HttpHeaders.SET_COOKIE, cookie(SessionStore.COOKIE, "", 0, "/").toString()).build());
                    }
                    log.warn("[session] cannot reach Akkoma: {}", e.toString());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).build());
                });
    }

    /** The shape the UI's SessionService reads (HugSession), from Akkoma's account. */
    static Map<String, Object> view(SessionStore.Session s, JsonNode acct) {
        List<String> roles = new ArrayList<>(List.of("user"));
        JsonNode pl = acct.path("pleroma");
        if (pl.path("is_moderator").asBoolean(false)) roles.add("mod");
        if (pl.path("is_admin").asBoolean(false)) roles.add("admin");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.acct());
        m.put("label", s.label());
        m.put("provider", "akkoma");
        m.put("roles", roles);
        m.put("registrationComplete", true);
        return m;
    }

    /** Only same-site relative paths: an absolute or protocol-relative `next` would be an open redirect. */
    static String safeNext(String next) {
        if (next == null || next.isBlank() || !next.startsWith("/") || next.startsWith("//") || next.contains("\\")
                || next.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "/";
        }
        return next;
    }

    private ResponseCookie cookie(String name, String value, long maxAgeSeconds, String path) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(props.secureCookies())
                .sameSite("Lax").path(path).maxAge(maxAgeSeconds).build();
    }

    private ResponseCookie clearState() {
        return cookie(SessionStore.STATE_COOKIE, "", 0, "/bff");
    }

    private ResponseEntity<Void> redirect(String to, ResponseCookie c) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, to)
                .header(HttpHeaders.SET_COOKIE, c.toString()).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static URI uri(String s) { return URI.create(s); }
}
