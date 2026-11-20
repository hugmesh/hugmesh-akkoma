package net.hugmesh.akkoma.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Server-side sessions: the browser holds only an opaque random id in an HttpOnly cookie, and the
 * Akkoma access token never leaves this process. In memory, which is right for one instance; a restart
 * signs everyone out (they sign in again with one click, since Akkoma remembers the app).
 */
@Component
public class SessionStore {

    public static final String COOKIE = "bff_session";
    public static final String STATE_COOKIE = "bff_oauth_state";
    private static final int MAX_SESSIONS = 10_000;
    private static final int MAX_PENDING = 1_000;

    /** @param token the Akkoma access token; never serialised. */
    public record Session(String id, String token, String acct, String label, Instant expires) {
        @Override public String toString() { return "Session[" + acct + "]"; }
    }

    private record Pending(String next, Instant expires) {}

    private final WebProperties props;
    private final Clock clock;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SessionStore(WebProperties props) {
        this(props, Clock.systemUTC());
    }

    SessionStore(WebProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    private String token() {
        byte[] b = new byte[32];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public Session create(String accessToken, String acct, String label) {
        sweep();
        if (sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("too many open sessions");
        }
        Session s = new Session(token(), accessToken, acct, label, clock.instant().plus(props.sessionTtl()));
        sessions.put(s.id(), s);
        return s;
    }

    public Optional<Session> find(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        Session s = sessions.get(id);
        if (s == null) return Optional.empty();
        if (!s.expires().isAfter(clock.instant())) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public Optional<Session> from(ServerWebExchange ex) {
        HttpCookie c = ex.getRequest().getCookies().getFirst(COOKIE);
        return c == null ? Optional.empty() : find(c.getValue());
    }

    public void remove(String id) {
        if (id != null) sessions.remove(id);
    }

    /** Remembers where to send the user after sign-in; returns the OAuth `state` value. */
    public String newLoginState(String next) {
        sweep();
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("too many sign-ins in progress");
        }
        String state = token();
        pending.put(state, new Pending(next, clock.instant().plusSeconds(600)));
        return state;
    }

    /** One use: returns the `next` path stored for this state, or empty if unknown or expired. */
    public Optional<String> consumeLoginState(String state) {
        if (state == null) return Optional.empty();
        Pending p = pending.remove(state);
        if (p == null || !p.expires().isAfter(clock.instant())) return Optional.empty();
        return Optional.of(p.next());
    }

    private void sweep() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !s.expires().isAfter(now));
        pending.values().removeIf(p -> !p.expires().isAfter(now));
    }

    int sessionCount() { return sessions.size(); }
}
