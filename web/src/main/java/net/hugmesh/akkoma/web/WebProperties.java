package net.hugmesh.akkoma.web;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param akkomaUrl   where Akkoma listens, as this service reaches it (the compose service name).
 * @param publicUrl   the origin users type into the browser, e.g. https://social.example.org. It is the
 *                    OAuth redirect origin, the base of the CSRF origin check and decides the Secure flag.
 * @param uiDir       the built web UI (index.html and assets).
 * @param stateDir    where the OAuth app registration and per-user preferences are kept.
 * @param sessionTtl  how long a sign-in lasts.
 * @param scopes      OAuth scopes requested from Akkoma.
 * @param clientName  the name Akkoma shows on its authorisation page.
 */
@ConfigurationProperties("hugmesh.web")
public record WebProperties(
        @DefaultValue("http://akkoma:4000") String akkomaUrl,
        @DefaultValue("http://localhost:8080") String publicUrl,
        @DefaultValue("/app/ui") Path uiDir,
        @DefaultValue("/var/lib/hugmesh-web") Path stateDir,
        @DefaultValue("12h") Duration sessionTtl,
        @DefaultValue("read write follow push") String scopes,
        @DefaultValue("HugMesh web") String clientName) {

    public WebProperties {
        akkomaUrl = stripTrailingSlash(akkomaUrl);
        publicUrl = stripTrailingSlash(publicUrl);
        URI u = URI.create(publicUrl);
        if (u.getScheme() == null || u.getHost() == null) {
            throw new IllegalArgumentException("hugmesh.web.public-url must be an absolute http(s) URL: " + publicUrl);
        }
    }

    public boolean secureCookies() {
        return "https".equalsIgnoreCase(URI.create(publicUrl).getScheme());
    }

    /** scheme://host[:port] of the public URL, as a browser sends it in Origin. */
    public String publicOrigin() {
        URI u = URI.create(publicUrl);
        return u.getScheme() + "://" + u.getAuthority();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
