package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class PlayerRefreshService {

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

  @Async
  public CompletableFuture<Void> refreshPlayerByAthleteIdAsync(String athleteId) {
    if (athleteId == null || athleteId.isBlank() || !inFlightAthleteIds.add(athleteId)) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      JsonNode athleteNode = espnPlayerClient.fetchAthleteById(athleteId);
      playerUpsertService.upsertAthlete(
          athleteNode, espnPlayerClient.buildAthleteUrl(athleteId));
      return CompletableFuture.completedFuture(null);
    } finally {
      inFlightAthleteIds.remove(athleteId);
    }
  }
}
