package com.alpas.ainativesearchrankingoptimizationplatform.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdApiIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearCatalog() {
        jdbc.sql("DELETE FROM ads").update();
    }

    @Test
    void createsReadsAndStoresTheSameAd() throws Exception {
        HttpResponse<String> created = request("POST", "/api/v1/ads", """
                {"title":"  Camera  ","category":"  Electronics  "}
                """);
        assertEquals(201, created.statusCode(), created.body());
        JsonNode body = json.readTree(created.body());
        UUID id = UUID.fromString(body.path("id").asText());
        assertEquals("Camera", body.path("title").asText());
        assertEquals("Electronics", body.path("category").asText());
        assertDoesNotThrow(() -> Instant.parse(body.path("createdAt").asText()));
        String location = created.headers().firstValue("Location").orElseThrow();
        assertEquals("/api/v1/ads/" + id, location);
        HttpResponse<String> fetched = request("GET", location, null);
        assertEquals(200, fetched.statusCode(), fetched.body());
        assertEquals(body, json.readTree(fetched.body()));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM ads WHERE id = :id")
                .param("id", id).query(Long.class).single());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"title\":\"\",\"category\":\"Cameras\"}",
            "{\"title\":null,\"category\":\"Cameras\"}",
            "{\"title\":\"Camera\",\"category\":\"   \"}",
            "{\"title\":\"bad\\u0000title\",\"category\":\"Cameras\"}"
    })
    void rejectsInvalidFieldsWithoutWritingRows(String payload) throws Exception {
        HttpResponse<String> response = request("POST", "/api/v1/ads", payload);
        assertProblem(response, 400);
        assertTrue(json.readTree(response.body()).path("errors").isArray());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM ads").query(Long.class).single());
    }

    @Test
    void rejectsOversizedTitle() throws Exception {
        assertProblem(request("POST", "/api/v1/ads",
                "{\"title\":\"" + "a".repeat(201) + "\",\"category\":\"Cameras\"}"), 400);
    }

    @Test
    void countsUnicodeCodePointsConsistentlyAtTheDatabaseBoundary() throws Exception {
        String title = "📷".repeat(200);
        HttpResponse<String> response = request("POST", "/api/v1/ads",
                "{\"title\":\"" + title + "\",\"category\":\"Cameras\"}");
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(title, json.readTree(response.body()).path("title").asText());
        assertProblem(request("POST", "/api/v1/ads",
                "{\"title\":\"" + title + "📷\",\"category\":\"Cameras\"}"), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]",
            "{\"title\":123,\"category\":\"Cameras\"}",
            "{\"title\":\"Camera\",\"category\":true}",
            "{\"title\":\"Camera\",\"category\":\"Cameras\"} {}",
            "{\"title\":\"Camera\",\"category\":\"Cameras\",\"bid\":10}"})
    void rejectsMalformedOrUnexpectedPayloads(String payload) throws Exception {
        assertProblem(request("POST", "/api/v1/ads", payload), 400);
    }

    @Test
    void distinguishesMalformedAndMissingIds() throws Exception {
        assertProblem(request("GET", "/api/v1/ads/not-a-uuid", null), 400);
        assertProblem(request("GET", "/api/v1/ads/" + UUID.randomUUID(), null), 404);
    }

    @Test
    void rejectsUnsupportedMethodsAndMediaTypes() throws Exception {
        assertProblem(request("PUT", "/api/v1/ads/" + UUID.randomUUID(), "{}"), 405);
        HttpRequest text = HttpRequest.newBuilder(uri("/api/v1/ads"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("Camera")).build();
        assertProblem(http.send(text, HttpResponse.BodyHandlers.ofString()), 415);
    }

    @Test
    void bindsSqlLikeTextAsData() throws Exception {
        String title = "O'Reilly; DROP TABLE ads; --";
        HttpResponse<String> created = request("POST", "/api/v1/ads",
                "{\"title\":\"" + title + "\",\"category\":\"Cameras\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(title, json.readTree(created.body()).path("title").asText());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM ads").query(Long.class).single());
    }

    @Test
    void runsMigrationAndEnforcesDatabaseConstraints() {
        assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success
                """).query(Long.class).single());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO ads (id, title, category, created_at)
                VALUES (:id, '   ', 'Cameras', CURRENT_TIMESTAMP)
                """).param("id", UUID.randomUUID()).update());
    }

    @Test
    void exposesHealthWithoutDatabaseConnectionDetails() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            HttpResponse<String> response = request("GET", path, null);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("UP", json.readTree(response.body()).path("status").asText());
            assertFalse(response.body().contains("jdbc:"));
            assertFalse(response.body().contains(POSTGRES.getPassword()));
        }
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json"));
        assertEquals(status, json.readTree(response.body()).path("status").asInt());
        assertFalse(response.body().contains("stackTrace"));
        assertFalse(response.body().contains("jdbc:"));
    }
}
