package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.etl.RScriptRunner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Triggers the R/nflverse per-player history backfill (etl/r/import_player_history.R) the first
 * time a player has no stored game stats, mirroring how {@link PlayerRefreshService} backfills
 * ESPN player metadata in the background.
 */
@Service
public class PlayerHistoryBackfillService {

  private static final String SCRIPT_NAME = "import_player_history.R";

  private final RScriptRunner rScriptRunner;
  private final boolean enabled;
  private final Set<String> inFlightAthleteIds = ConcurrentHashMap.newKeySet();

  public PlayerHistoryBackfillService(
      RScriptRunner rScriptRunner,
      @Value("${agp.player-history-backfill.enabled:true}") boolean enabled) {
    this.rScriptRunner = rScriptRunner;
    this.enabled = enabled;
  }

  public void requestBackfillIfNeeded(String athleteId) {
    if (!enabled || athleteId == null || athleteId.isBlank()) {
      return;
    }

    backfillAsync(athleteId);
  }

  @Async
  public CompletableFuture<Void> backfillAsync(String athleteId) {
    if (athleteId == null || athleteId.isBlank() || !inFlightAthleteIds.add(athleteId)) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      rScriptRunner.run(SCRIPT_NAME, athleteId);
    } finally {
      inFlightAthleteIds.remove(athleteId);
    }

    return CompletableFuture.completedFuture(null);
  }
}
