package net.hugmesh.akkoma.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** The calls this service makes to Akkoma itself (the proxy uses {@link #http()} directly). */
@Component
public class AkkomaClient {

    /** An OAuth app registered with Akkoma. */
    public record OAuthApp(String clientId, String clientSecret, String redirectUri) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebClient http;

    public AkkomaClient(WebProperties props) {
        // No redirect following: Akkoma's OAuth pages answer with 302s that belong to the browser.
        HttpClient netty = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .followRedirect(false);
        this.http = WebClient.builder().baseUrl(props.akkomaUrl()).clientConnector(new ReactorClientHttpConnector(netty)).build();
    }

    public WebClient http() {
        return http;
    }

    public Mono<OAuthApp> registerApp(String name, String redirectUri, String scopes, String website) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_name", name);
        form.add("redirect_uris", redirectUri);
        form.add("scopes", scopes);
        form.add("website", website);
        return http.post().uri("/api/v1/apps")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve().bodyToMono(String.class).map(AkkomaClient::parse)
                .map(j -> new OAuthApp(text(j, "client_id"), text(j, "client_secret"), redirectUri));
    }

    /** Exchanges an authorisation code for an access token. */
    public Mono<String> exchangeCode(OAuthApp app, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", app.clientId());
        form.add("client_secret", app.clientSecret());
        form.add("redirect_uri", app.redirectUri());
        return http.post().uri("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve().bodyToMono(String.class).map(AkkomaClient::parse)
                .map(j -> text(j, "access_token"));
    }

    /** The signed-in account, or an error when Akkoma refuses the token. */
    public Mono<JsonNode> verifyCredentials(String token) {
        return http.get().uri("/api/v1/accounts/verify_credentials")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().bodyToMono(String.class).map(AkkomaClient::parse);
    }

    /** Best effort: a sign-out must succeed locally even when Akkoma is unreachable. */
    public Mono<Void> revoke(OAuthApp app, String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", app.clientId());
        form.add("client_secret", app.clientSecret());
        form.add("token", token);
        return http.post().uri("/oauth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve().toBodilessEntity().then()
                .onErrorResume(e -> Mono.empty());
    }

    static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Akkoma returned a body that is not JSON", e);
        }
    }

    static String text(JsonNode j, String field) {
        JsonNode n = j.get(field);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            throw new IllegalStateException("Akkoma's reply has no '" + field + "'");
        }
        return n.asText();
    }
}
