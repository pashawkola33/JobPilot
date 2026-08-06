package com.jobpilot.miniapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.miniapp.api.MiniAppController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The Mini App is served from the same origin as its API, which puts a static handler and
 * a REST controller on adjacent paths. These tests pin the boundary between them.
 *
 * <p>A real servlet container rather than MockMvc, because {@code /mini-app/} is a forward
 * and MockMvc records forwards instead of dispatching them. The document served here is
 * src/test/resources/static/mini-app/index.html; the real one is produced by the Docker
 * image's Node stage. The Mini App API stays disabled, which is its production default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:jobpilot-mini-app-routing;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class MiniAppRoutingTest {
    /** Present in the shell and in nothing the backend returns. */
    private static final String SHELL_MARKER = "<div id=\"root\">";

    @Autowired
    private TestRestTemplate http;

    @Test
    void servesTheAppAtItsPublishedPath() {
        ResponseEntity<String> response = http.getForEntity(MiniAppWebConfig.PATH + "/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(SHELL_MARKER);
    }

    @Test
    void reachesTheAppWithoutTheTrailingSlash() {
        // Redirected to the canonical path and followed, as a browser would.
        ResponseEntity<String> response = http.getForEntity(MiniAppWebConfig.PATH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(SHELL_MARKER);
    }

    @Test
    void servesTheAssetsTheBuildEmits() {
        assertThat(http.getForEntity(MiniAppWebConfig.PATH + "/index.html", String.class).getBody())
                .contains(SHELL_MARKER);
    }

    @Test
    void answersARefreshedClientRouteWithTheShellRatherThanA404() {
        ResponseEntity<String> response =
                http.getForEntity(MiniAppWebConfig.PATH + "/review/4821", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(SHELL_MARKER);
    }

    /**
     * Answering with HTML would hide a stale bundle behind a syntax error. The bare
     * directory counts: its trailing slash is normalised away before a resolver sees it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/assets/gone-abc123.js", "/assets/", "/assets"})
    void stillFailsAMissingAssetOutright(String path) {
        assertThat(http.getForEntity(MiniAppWebConfig.PATH + path, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void leavesTheApiToItsOwnGate() {
        // 503 is the disabled Mini App API answering, which proves the static fallback
        // never saw the request.
        ResponseEntity<String> response =
                http.getForEntity(MiniAppController.BASE + "/snapshot", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("MINI_APP_DISABLED");
    }

    /**
     * The failure this file exists to prevent: an SPA fallback wide enough to answer a
     * backend route with the Mini App's HTML.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            MiniAppController.BASE + "/snapshot",
            "/health",
            "/internal/anything",
            "/mini-app-other",
            "/",
    })
    void neverSwallowsABackendPath(String path) {
        assertThat(http.getForEntity(path, String.class).getBody()).doesNotContain(SHELL_MARKER);
    }
}
