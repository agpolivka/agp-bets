package com.agp.bets.goforbroke.player.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Triggers the R/nflverse per-player history backfill (etl/r/import_player_history.R) the first
 * time a player has no stored game stats, mirroring how {@link PlayerRefreshService} backfills
 * ESPN player metadata in the background.
 *
 * <p>The actual script execution lives on {@link PlayerHistoryBackfillRunner}, a separate
 * {@code @Async} bean, rather than a method here - Spring's async proxy only intercepts calls
 * made from other beans, so a same-class async call would silently run synchronously. Splitting
 * the classes also means {@code inFlightAthleteIds} is updated synchronously, on the calling
 * thread, before {@code requestBackfillIfNeeded} returns - so {@link #isBackfillInProgress} is
 * accurate for a caller checking it immediately after requesting a backfill, not just on a later
 * poll.
 */
@Service
public class PlayerHistoryBackfillService {

  private final PlayerHistoryBackfillRunner runner;
  private final boolean enabled;
  private final Set<String> inFlightAthleteIds = ConcurrentHashMap.newKeySet();

  public PlayerHistoryBackfillService(
      PlayerHistoryBackfillRunner runner,
      @Value("${agp.player-history-backfill.enabled:true}") boolean enabled) {
    this.runner = runner;
    this.enabled = enabled;
  }

  public void requestBackfillIfNeeded(String athleteId) {
    if (!enabled || athleteId == null || athleteId.isBlank() || !inFlightAthleteIds.add(athleteId)) {
      return;
    }

    runner.run(athleteId, () -> inFlightAthleteIds.remove(athleteId));
  }

  /** Whether a backfill for this athlete is currently running in the background. */
  public boolean isBackfillInProgress(String athleteId) {
    return athleteId != null && inFlightAthleteIds.contains(athleteId);
  }
}
