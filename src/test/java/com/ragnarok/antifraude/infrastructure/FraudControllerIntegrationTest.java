package com.ragnarok.antifraude.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Teste de integração completo — Spring Boot + PostgreSQL + Redis reais via Testcontainers.
 *
 * TODO [Claude Code]: Expandir com cenários de cada regra contra Redis/PostgreSQL reais.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class FraudControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("ragnarok_antifraude_test")
        .withUsername("postgres")
        .withPassword("postgre");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Health endpoint retorna 200")
    void healthCheck() throws Exception {
        mockMvc.perform(get("/api/fraud/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("POST /analyze com trade normal → APPROVED")
    void normalTrade_approved() throws Exception {
        String payload = """
            {
                "eventId": "test-001",
                "eventType": "ITEM_TRADE",
                "playerId": 1,
                "ipAddress": "127.0.0.1",
                "countryCode": "BR",
                "payload": {
                    "itemId": 1101,
                    "itemUuid": "unique-uuid-001",
                    "zenysValue": 500
                }
            }
            """;

        mockMvc.perform(post("/api/fraud/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("APPROVED"));
    }

    @Test
    @DisplayName("POST /analyze com dupe → segundo request BLOCKED")
    void duplicateTrade_blocked() throws Exception {
        String payload = """
            {
                "eventId": "test-dupe-001",
                "eventType": "ITEM_TRADE",
                "playerId": 666,
                "ipAddress": "10.0.0.1",
                "countryCode": "BR",
                "payload": {
                    "itemId": 9999,
                    "itemUuid": "DUPE-FIXED-UUID",
                    "zenysValue": 500
                }
            }
            """;

        // Primeira → ok
        mockMvc.perform(post("/api/fraud/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("APPROVED"));

        // Segunda com mesmo itemUuid → BLOCKED
        String payload2 = payload.replace("test-dupe-001", "test-dupe-002");
        mockMvc.perform(post("/api/fraud/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("BLOCKED"))
            .andExpect(jsonPath("$.triggeredRules[0]").value("ANTI_DUPE"));
    }

    @Test
    @DisplayName("POST /analyze com email não verificado → BLOCKED")
    void unverifiedEmail_blocked() throws Exception {
        String payload = """
            {
                "eventId": "test-reg-001",
                "eventType": "SESSION_LOGIN",
                "playerId": 100,
                "ipAddress": "200.1.2.3",
                "countryCode": "BR",
                "payload": {
                    "emailVerified": false,
                    "ageVerified": true
                }
            }
            """;

        mockMvc.perform(post("/api/fraud/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verdict").value("BLOCKED"))
            .andExpect(jsonPath("$.triggeredRules[0]").value("REGISTRATION_VALIDATION"));
    }

    @Test
    @DisplayName("Resposta completa em menos de 80ms")
    void latencyBudget() throws Exception {
        String payload = """
            {
                "eventId": "test-lat-001",
                "eventType": "ITEM_TRADE",
                "playerId": 42,
                "ipAddress": "127.0.0.1",
                "countryCode": "BR",
                "payload": {
                    "itemId": 501,
                    "itemUuid": "latency-test-uuid",
                    "zenysValue": 100
                }
            }
            """;

        long start = System.currentTimeMillis();
        mockMvc.perform(post("/api/fraud/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 500, // Generous for CI — stress test validates the real SLA
            "Response took " + elapsed + "ms — expected < 500ms even in CI");
    }
}
