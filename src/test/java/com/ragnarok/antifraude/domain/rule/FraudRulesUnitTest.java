package com.ragnarok.antifraude.domain.rule;

import com.ragnarok.antifraude.application.rule.*;
import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.model.Verdict;
import com.ragnarok.antifraude.domain.port.out.PlayerActivityRepository;
import com.ragnarok.antifraude.domain.port.out.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Testes unitários das 9 regras — sem Spring, sem Redis, sem banco.
 * Cada regra é testada isoladamente com mocks das ports.
 */
@ExtendWith(MockitoExtension.class)
class FraudRulesUnitTest {

    @Mock TransactionRepository transactionRepo;
    @Mock PlayerActivityRepository playerRepo;

    private FraudEvent event(String eventType, Map<String, Object> payload) {
        return new FraudEvent("test-001", eventType, 1L, "127.0.0.1", "BR", Instant.now(), payload);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Regra 1 — Anti-Dupe
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Regra 1: Anti-Dupe")
    class AntiDupeTests {

        @Test
        @DisplayName("Primeira transação com itemUuid → APPROVED")
        void firstTransaction_approved() {
            when(transactionRepo.tryAcquireDupeLock(anyString(), anyLong())).thenReturn(true);
            var rule = new AntiDupeRule(transactionRepo);
            var result = rule.evaluate(event("ITEM_TRADE", Map.of("itemUuid", "uuid-123")));
            assertEquals(Verdict.APPROVED, result.verdict());
        }

        @Test
        @DisplayName("Mesmo itemUuid duplicado → BLOCKED")
        void duplicateTransaction_blocked() {
            when(transactionRepo.tryAcquireDupeLock(anyString(), anyLong())).thenReturn(false);
            var rule = new AntiDupeRule(transactionRepo);
            var result = rule.evaluate(event("ITEM_TRADE", Map.of("itemUuid", "uuid-123")));
            assertEquals(Verdict.BLOCKED, result.verdict());
        }

        @Test
        @DisplayName("Payload sem itemUuid → APPROVED (fail-open)")
        void missingUuid_approved() {
            var rule = new AntiDupeRule(transactionRepo);
            var result = rule.evaluate(event("ITEM_TRADE", Map.of()));
            assertEquals(Verdict.APPROVED, result.verdict());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Regra 2 — Transferência Desproporcional
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Regra 2: Transferência Desproporcional")
    class DisproportionateTransferTests {

        @Test
        @DisplayName("Valor dentro do normal → APPROVED")
        void normalValue_approved() {
            when(transactionRepo.getMarketPrice(607L)).thenReturn(Optional.of(5000L));
            var rule = new DisproportionateTransferRule(transactionRepo);
            var result = rule.evaluate(event("ITEM_TRADE",
                Map.of("itemId", 607, "zenysValue", 10000)));
            assertEquals(Verdict.APPROVED, result.verdict());
        }

        @Test
        @DisplayName("Valor 10000x acima → BLOCKED CRITICAL")
        void absurdValue_blocked() {
            when(transactionRepo.getMarketPrice(607L)).thenReturn(Optional.of(5000L));
            var rule = new DisproportionateTransferRule(transactionRepo);
            var result = rule.evaluate(event("ITEM_TRADE",
                Map.of("itemId", 607, "zenysValue", 1_000_000_000)));
            assertEquals(Verdict.BLOCKED, result.verdict());
        }

        @Test
        @DisplayName("Item sem preço cadastrado → APPROVED")
        void unknownPrice_approved() {
            when(transactionRepo.getMarketPrice(anyLong())).thenReturn(Optional.empty());
            var rule = new DisproportionateTransferRule(transactionRepo);
            var result = rule.evaluate(event("ITEM_TRADE",
                Map.of("itemId", 9999, "zenysValue", 1_000_000_000)));
            assertEquals(Verdict.APPROVED, result.verdict());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Regra 3 — Bot Farm Time
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Regra 3: Bot Farm Time")
    class BotFarmTimeTests {

        @Test
        @DisplayName("Farm por 2h → APPROVED")
        void shortFarm_approved() {
            when(playerRepo.getFarmDuration(1L, "prt_fild01"))
                .thenReturn(Optional.of(Duration.ofHours(2)));
            var rule = new BotFarmTimeRule(playerRepo);
            var result = rule.evaluate(event("FARM_HEARTBEAT", Map.of("mapId", "prt_fild01")));
            assertEquals(Verdict.APPROVED, result.verdict());
        }

        @Test
        @DisplayName("Farm por 7h → CHALLENGE (captcha)")
        void longFarm_challenge() {
            when(playerRepo.getFarmDuration(1L, "prt_fild01"))
                .thenReturn(Optional.of(Duration.ofHours(7)));
            var rule = new BotFarmTimeRule(playerRepo);
            var result = rule.evaluate(event("FARM_HEARTBEAT", Map.of("mapId", "prt_fild01")));
            assertEquals(Verdict.CHALLENGE, result.verdict());
        }

        @Test
        @DisplayName("Farm por 25h → BLOCKED (drop session)")
        void extremeFarm_blocked() {
            when(playerRepo.getFarmDuration(1L, "prt_fild01"))
                .thenReturn(Optional.of(Duration.ofHours(25)));
            var rule = new BotFarmTimeRule(playerRepo);
            var result = rule.evaluate(event("FARM_HEARTBEAT", Map.of("mapId", "prt_fild01")));
            assertEquals(Verdict.BLOCKED, result.verdict());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Regra 5 — Registration Validation
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Regra 5: Registration Validation")
    class RegistrationTests {

        @Test
        @DisplayName("Email verificado + maior de idade → APPROVED")
        void validRegistration_approved() {
            var rule = new RegistrationValidationRule();
            var result = rule.evaluate(event("SESSION_LOGIN",
                Map.of("emailVerified", true, "ageVerified", true)));
            assertEquals(Verdict.APPROVED, result.verdict());
        }

        @Test
        @DisplayName("Email não verificado → BLOCKED")
        void unverifiedEmail_blocked() {
            var rule = new RegistrationValidationRule();
            var result = rule.evaluate(event("SESSION_LOGIN",
                Map.of("emailVerified", false, "ageVerified", true)));
            assertEquals(Verdict.BLOCKED, result.verdict());
        }

        @Test
        @DisplayName("Menor de idade → BLOCKED")
        void underage_blocked() {
            var rule = new RegistrationValidationRule();
            var result = rule.evaluate(event("SESSION_LOGIN",
                Map.of("emailVerified", true, "ageVerified", false)));
            assertEquals(Verdict.BLOCKED, result.verdict());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Regra 8 — Impossible Travel
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Regra 8: Impossible Travel")
    class ImpossibleTravelTests {

        @Test
        @DisplayName("Mesmo país → APPROVED")
        void sameCountry_approved() {
            when(playerRepo.getLastLogin(1L)).thenReturn(Optional.of(
                new PlayerActivityRepository.LoginRecord("BR", Instant.now().minus(Duration.ofMinutes(5)))));
            var rule = new ImpossibleTravelRule(playerRepo);
            var evt = new FraudEvent("e1", "SESSION_LOGIN", 1L, "1.2.3.4", "BR", Instant.now(), Map.of());
            var result = rule.evaluate(evt);
            assertEquals(Verdict.APPROVED, result.verdict());
        }

        @Test
        @DisplayName("País diferente em 30 min → BLOCKED")
        void impossibleTravel_blocked() {
            when(playerRepo.getLastLogin(1L)).thenReturn(Optional.of(
                new PlayerActivityRepository.LoginRecord("BR", Instant.now().minus(Duration.ofMinutes(30)))));
            var rule = new ImpossibleTravelRule(playerRepo);
            var evt = new FraudEvent("e1", "SESSION_LOGIN", 1L, "1.2.3.4", "JP", Instant.now(), Map.of());
            var result = rule.evaluate(evt);
            assertEquals(Verdict.BLOCKED, result.verdict());
        }

        @Test
        @DisplayName("País diferente em 5h → APPROVED")
        void legitimateTravel_approved() {
            when(playerRepo.getLastLogin(1L)).thenReturn(Optional.of(
                new PlayerActivityRepository.LoginRecord("BR", Instant.now().minus(Duration.ofHours(5)))));
            var rule = new ImpossibleTravelRule(playerRepo);
            var evt = new FraudEvent("e1", "SESSION_LOGIN", 1L, "1.2.3.4", "JP", Instant.now(), Map.of());
            var result = rule.evaluate(evt);
            assertEquals(Verdict.APPROVED, result.verdict());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Regra 9 — Market Monopoly
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Regra 9: Market Monopoly")
    class MarketMonopolyTests {

        @Test
        @DisplayName("Compra de 10% do estoque → APPROVED")
        void smallPurchase_approved() {
            var rule = new MarketMonopolyRule();
            var result = rule.evaluate(event("MARKET_PURCHASE",
                Map.of("quantityRequested", 10, "totalInStock", 100, "itemId", 501)));
            assertEquals(Verdict.APPROVED, result.verdict());
        }

        @Test
        @DisplayName("Compra de 96% do estoque → CHALLENGE (flag)")
        void monopolyPurchase_challenge() {
            var rule = new MarketMonopolyRule();
            var result = rule.evaluate(event("MARKET_PURCHASE",
                Map.of("quantityRequested", 96, "totalInStock", 100, "itemId", 501)));
            assertEquals(Verdict.CHALLENGE, result.verdict());
        }
    }
}
