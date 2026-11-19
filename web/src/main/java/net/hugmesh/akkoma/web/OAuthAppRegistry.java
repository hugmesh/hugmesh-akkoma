package net.hugmesh.akkoma.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The OAuth app this service signs users in with. Akkoma issues client ids on request
 * (POST /api/v1/apps), so the first sign-in registers one and keeps it in the state directory;
 * later starts reuse it. A changed public URL registers a new app (the redirect URI is part of it).
 */
@Component
public class OAuthAppRegistry {

    private static final Logger log = LoggerFactory.getLogger(OAuthAppRegistry.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebProperties props;
    private final AkkomaClient akkoma;
    private volatile Mono<AkkomaClient.OAuthApp> cached;

    public OAuthAppRegistry(WebProperties props, AkkomaClient akkoma) {
        this.props = props;
        this.akkoma = akkoma;
    }

    public String redirectUri() {
        return props.publicUrl() + "/bff/callback";
    }

    /** The registered app; an Akkoma that is not up yet fails this call and the next one retries. */
    public Mono<AkkomaClient.OAuthApp> app() {
        Mono<AkkomaClient.OAuthApp> m = cached;
        if (m == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = load().switchIfEmpty(Mono.defer(this::register))
                            .doOnError(e -> reset())
                            .cache();
                }
                m = cached;
            }
        }
        return m;
    }

    private synchronized void reset() {
        cached = null;
    }

    private Path file() {
        return props.stateDir().resolve("oauth-app.json");
    }

    private Mono<AkkomaClient.OAuthApp> load() {
        return Mono.fromCallable(() -> {
            Path f = file();
            if (!Files.isRegularFile(f)) return null;
            var j = JSON.readTree(Files.readString(f, StandardCharsets.UTF_8));
            if (!redirectUri().equals(j.path("redirect_uri").asText())
                    || !props.akkomaUrl().equals(j.path("akkoma_url").asText())) {
                return null; // registered for another address
            }
            return new AkkomaClient.OAuthApp(j.path("client_id").asText(), j.path("client_secret").asText(), redirectUri());
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(e -> {
              log.warn("[oauth] cannot read the saved app registration, registering again: {}", e.toString());
              return Mono.empty();
          })
          .filter(a -> !a.clientId().isBlank() && !a.clientSecret().isBlank());
    }

    private Mono<AkkomaClient.OAuthApp> register() {
        log.info("[oauth] registering the sign-in app with Akkoma (redirect {})", redirectUri());
        return akkoma.registerApp(props.clientName(), redirectUri(), props.scopes(), props.publicUrl())
                .flatMap(a -> Mono.fromCallable(() -> { save(a); return a; }).subscribeOn(Schedulers.boundedElastic()));
    }

    private void save(AkkomaClient.OAuthApp a) throws IOException {
        Files.createDirectories(props.stateDir());
        ObjectNode n = JSON.createObjectNode();
        n.put("client_id", a.clientId());
        n.put("client_secret", a.clientSecret());
        n.put("redirect_uri", a.redirectUri());
        n.put("akkoma_url", props.akkomaUrl());
        Path tmp = Files.createTempFile(props.stateDir(), "oauth-app", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(tmp, JSON.writeValueAsString(n), StandardCharsets.UTF_8);
        Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
