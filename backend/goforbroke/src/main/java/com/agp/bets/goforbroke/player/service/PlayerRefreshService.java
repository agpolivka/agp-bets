package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class PlayerRefreshService {

  private static final Logger log = LoggerFactory.getLogger(PlayerRefreshService.class);

  private final EspnPlayerClient espnPlayerClient;
  private final PlayerUpsertService playerUpsertService;
  private final Set<String> inFlightAthleteIds = ConcurrentHashMap.newKeySet();

  public PlayerRefreshService(
      EspnPlayerClient espnPlayerClient, PlayerUpsertService playerUpsertService) {
    this.espnPlayerClient = espnPlayerClient;
    this.playerUpsertService = playerUpsertService;
  }

  public void requestRefreshIfNeeded(String athleteId) {
    if (athleteId == null || athleteId.isBlank()) {
      return;
    }

    refreshPlayerByAthleteIdAsync(athleteId);
  }

  // See TeamRefreshWorker for why this catches/logs explicitly instead of letting the exception
  // propagate onto the discarded future - the caller never inspects it, so an uncaught failure
  // here would silently disappear.
  @Async
  public CompletableFuture<Void> refreshPlayerByAthleteIdAsync(String athleteId) {
    if (athleteId == null || athleteId.isBlank() || !inFlightAthleteIds.add(athleteId)) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      JsonNode athleteNode = espnPlayerClient.fetchAthleteById(athleteId);
      playerUpsertService.upsertAthlete(
          athleteNode, espnPlayerClient.buildAthleteUrl(athleteId));
    } catch (RuntimeException exception) {
      log.warn("Failed to refresh player metadata for athlete {}: {}", athleteId, exception.getMessage());
    } finally {
      inFlightAthleteIds.remove(athleteId);
    }

    return CompletableFuture.completedFuture(null);
  }
}
