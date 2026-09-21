package com.alpas.ainativesearchrankingoptimizationplatform.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Uses its own database so stopping it cannot interfere with API contract checks. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseAvailabilityIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;

    @Test
    void databaseFailureMakesReadinessFailButKeepsTheProcessAlive() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(200, get(client, "/actuator/health/readiness").statusCode());
            POSTGRES.stop();
            HttpResponse<String> api = get(client, "/api/v1/ads/" + UUID.randomUUID());
            assertEquals(503, api.statusCode(), api.body());
            assertTrue(api.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/problem+json"));
            assertFalse(api.body().contains("jdbc:"));
            assertEquals(503, get(client, "/actuator/health/readiness").statusCode());
            HttpResponse<String> live = get(client, "/actuator/health/liveness");
            assertEquals(200, live.statusCode(), live.body());
            assertEquals("UP", JsonMapper.builder().build().readTree(live.body()).path("status").asText());
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
