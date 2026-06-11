package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class PlayerRefreshService {

  private final EspnPlayerClient espnPlayerClient;
  private final PlayerUpsertService playerUpsertService;

  public PlayerRefreshService(
      EspnPlayerClient espnPlayerClient, PlayerUpsertService playerUpsertService) {
    this.espnPlayerClient = espnPlayerClient;
    this.playerUpsertService = playerUpsertService;
  }

  @Async
  public CompletableFuture<Void> refreshPlayerByAthleteIdAsync(String athleteId) {
    JsonNode athleteNode = espnPlayerClient.fetchAthleteById(athleteId);
    playerUpsertService.upsertAthlete(
        athleteNode, espnPlayerClient.buildAthleteUrl(athleteId));
    return CompletableFuture.completedFuture(null);
  }
}
