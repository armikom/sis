package com.armikom.sis.common;

import com.armikom.sis.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public base URL of the current request (honours X-Forwarded-* through server.forward-headers-strategy),
 * falling back to {@code app.public-base-url}.
 */
@Component
public class PublicBaseUrl {

    private final AppProperties props;
    private final Set<String> linkHosts;

    public PublicBaseUrl(AppProperties props) {
        this.props = props;
        String hosts = props.auth() != null ? props.auth().linkHosts() : null;
        this.linkHosts = hosts == null ? Set.of() : Arrays.stream(hosts.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** Base URL of the current request without a trailing slash; the configured one outside a request. */
    public String current() {
        try {
            return strip(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
        } catch (IllegalStateException e) {
            return configured();
        }
    }

    /**
     * Base for links sent by mail. The request's base is used unless {@code app.auth.link-hosts} is set and does
     * not contain its host (protects against Host / X-Forwarded-Host poisoning of login links).
     */
    public String forMailLinks() {
        String base = current();
        if (linkHosts.isEmpty()) return base;
        try {
            String host = URI.create(base).getHost();
            if (host != null && linkHosts.contains(host.toLowerCase(Locale.ROOT))) return base;
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        return configured();
    }

    public String configured() {
        String c = props.publicBaseUrl();
        return c != null && !c.isBlank() ? strip(c) : "http://localhost:8080";
    }

    private static String strip(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
