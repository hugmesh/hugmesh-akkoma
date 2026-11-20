package net.hugmesh.akkoma.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Front door for a standalone Akkoma: the Mastodon web UI, sign-in through Akkoma's own OAuth,
 *  the Mastodon API with the user's token held server-side, and Akkoma's federation paths. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
