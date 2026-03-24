package com.ragnarok.antifraude.infrastructure.adapter.in.rest;

import com.ragnarok.antifraude.domain.port.out.PlayerActivityRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Endpoints de sync de estado — chamados pelo ragnarok-core de forma fire-and-forget.
 * Mantém o antifraude atualizado sobre ações do jogador.
 */
@RestController
@RequestMapping("/api/fraud/state/player")
public class PlayerStateController {

    private final PlayerActivityRepository playerRepo;

    public PlayerStateController(PlayerActivityRepository playerRepo) {
        this.playerRepo = playerRepo;
    }

    /** Jogador saiu do mapa → reseta timer de farm (Regra 3). */
    @PostMapping("/{id}/map-leave")
    public ResponseEntity<Void> mapLeave(@PathVariable Long id, @RequestParam String mapId) {
        playerRepo.resetFarmSession(id, mapId);
        return ResponseEntity.ok().build();
    }

    /** Jogador logou → atualiza país para Regra 8 (impossible travel). */
    @PostMapping("/{id}/login")
    public ResponseEntity<Void> login(@PathVariable Long id, @RequestParam String countryCode) {
        playerRepo.updateLoginCountry(id, countryCode);
        return ResponseEntity.ok().build();
    }

    /** Jogador entrou em instância → registra cooldown para Regra 7. */
    @PostMapping("/{id}/instance-enter")
    public ResponseEntity<Void> instanceEnter(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String mapId = (String) body.get("mapId");
        Number cooldownSeconds = (Number) body.get("cooldownSeconds");
        if (mapId != null && cooldownSeconds != null) {
            playerRepo.recordInstanceEntry(id, mapId, Duration.ofSeconds(cooldownSeconds.longValue()));
        }
        return ResponseEntity.ok().build();
    }

    /** Sync de dados de registro → CPF para Regra 6, flags para Regra 5. */
    @PostMapping("/{id}/registration")
    public ResponseEntity<Void> registration(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean emailVerified = Boolean.TRUE.equals(body.get("emailVerified"));
        boolean ageVerified = Boolean.TRUE.equals(body.get("ageVerified"));
        String document = body.get("document") != null ? body.get("document").toString() : "";
        playerRepo.saveRegistrationData(id, emailVerified, ageVerified, document);
        return ResponseEntity.ok().build();
    }
}
