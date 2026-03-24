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

import java.util.Set;

/**
 * Regra 4 — Detector de bot por velocidade de clique.
 * APS > threshold + desvio padrão de latência muito baixo (timing robótico).
 *   APS > 15 → SHOW_CAPTCHA
 *   APS > 25 ou stddev < 5ms → FLAG_FOR_REVIEW (bot confirmado)
 */
@Component
public class BotClickSpeedRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(BotClickSpeedRule.class);
    private static final double APS_CAPTCHA_THRESHOLD = 15.0;
    private static final double APS_BOT_THRESHOLD = 25.0;
    private static final double STDDEV_BOT_THRESHOLD = 5.0; // ms — humanos variam mais

    private final PlayerActivityRepository playerRepo;

    public BotClickSpeedRule(PlayerActivityRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    @Override public String ruleId()         { return "BOT_CLICK_SPEED"; }
    @Override public Set<String> eventTypes() { return Set.of("CLICK_ACTION"); }
    @Override public int priority()          { return 4; }

    @Override
    public RuleResult evaluate(FraudEvent event) {
        try {
            Double aps = event.payloadValue("actionsPerSecond", Double.class);
            Double networkLatencyMs = event.payloadValue("networkLatencyMs", Double.class);

            if (aps == null) return RuleResult.approved(ruleId());

            // Registra click e calcula stddev
            double stddev = playerRepo.recordClickAndGetStdDev(
                event.playerId(), System.currentTimeMillis());

            // Bot confirmado: APS absurdo OU timing muito regular
            if (aps > APS_BOT_THRESHOLD || (stddev > 0 && stddev < STDDEV_BOT_THRESHOLD)) {
                return RuleResult.blocked(ruleId(), RequiredAction.FLAG_FOR_REVIEW, RiskLevel.HIGH,
                    String.format("Bot detected: APS=%.1f, stddev=%.1fms", aps, stddev));
            }

            // Suspeito: APS alto
            if (aps > APS_CAPTCHA_THRESHOLD) {
                return RuleResult.challenge(ruleId(), RequiredAction.SHOW_CAPTCHA, RiskLevel.MEDIUM,
                    String.format("Suspicious click speed: APS=%.1f", aps));
            }

            return RuleResult.approved(ruleId());
        } catch (Exception e) {
            log.warn("[{}] Error evaluating — fail-open: {}", ruleId(), e.getMessage());
            return RuleResult.approved(ruleId());
        }
    }
}
