package com.ragnarok.antifraude.application.rule;

import com.ragnarok.antifraude.domain.model.FraudEvent;
import com.ragnarok.antifraude.domain.model.RequiredAction;
import com.ragnarok.antifraude.domain.model.RiskLevel;
import com.ragnarok.antifraude.domain.port.out.PlayerActivityRepository;
import com.ragnarok.antifraude.domain.rule.FraudRule;
import com.ragnarok.antifraude.domain.rule.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Regra 8 — Bloqueio de viagem impossível.
 * Se o país de login muda em menos de 2 horas → DROP_SESSION.
 * Dados do último login estão no Redis (sub-millisecond).
 */
@Component
public class ImpossibleTravelRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(ImpossibleTravelRule.class);
    private static final Duration MIN_TRAVEL_TIME = Duration.ofHours(2);

    private final PlayerActivityRepository playerRepo;

    public ImpossibleTravelRule(PlayerActivityRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    @Override public String ruleId()         { return "IMPOSSIBLE_TRAVEL"; }
    @Override public Set<String> eventTypes() { return Set.of("SESSION_LOGIN"); }
    @Override public int priority()          { return 8; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            String currentCountry = event.countryCode();
            if (currentCountry == null || currentCountry.isBlank()) {
                return RuleResult.approved(ruleId());
            }

            var lastLogin = playerRepo.getLastLogin(event.playerId());
            if (lastLogin.isEmpty()) {
                return RuleResult.approved(ruleId()); // Primeiro login — sem histórico
            }

            var last = lastLogin.get();
            String lastCountry = last.countryCode();
            Instant lastTime = last.timestamp();

            // Mesmo país → ok
            if (currentCountry.equalsIgnoreCase(lastCountry)) {
                return RuleResult.approved(ruleId());
            }

            // País diferente — verificar tempo
            Duration elapsed = Duration.between(lastTime, Instant.now());
            if (elapsed.compareTo(MIN_TRAVEL_TIME) < 0) {
                return RuleResult.blocked(ruleId(), RequiredAction.DROP_SESSION, RiskLevel.CRITICAL,
                    String.format("Impossible travel: %s → %s in %d minutes",
                        lastCountry, currentCountry, elapsed.toMinutes()));
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}
