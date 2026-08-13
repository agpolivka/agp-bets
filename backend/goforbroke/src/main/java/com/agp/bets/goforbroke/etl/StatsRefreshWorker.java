package com.agp.bets.goforbroke.etl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the stored-players stats refresh, the team defense refresh, and the PFR advanced rushing
 * enrichment when {@link StatsRefreshDueChecker} says it's due - all three change on the same
 * signal (a game completed), so they share one due-check instead of each needing their own. None
 * of the three scripts take a season argument here; all default to nflverse's current season on
 * their own.
 */
@Service
public class StatsRefreshWorker {

  private static final Logger log = LoggerFactory.getLogger(StatsRefreshWorker.class);
  private static final String PLAYER_STATS_SCRIPT_NAME = "refresh_stored_players_weekly.R";
  private static final String TEAM_DEFENSE_SCRIPT_NAME = "import_team_defense.R";
  private static final String PFR_ADVANCED_RUSHING_SCRIPT_NAME = "import_pfr_advanced_rushing.R";

  private final RScriptRunner rScriptRunner;
  private final StatsRefreshDueChecker statsRefreshDueChecker;
  private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

  public StatsRefreshWorker(RScriptRunner rScriptRunner, StatsRefreshDueChecker statsRefreshDueChecker) {
    this.rScriptRunner = rScriptRunner;
    this.statsRefreshDueChecker = statsRefreshDueChecker;
  }

  @Async
  public CompletableFuture<Void> refreshIfDueAsync() {
    if (!refreshInFlight.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      if (statsRefreshDueChecker.isRefreshDue()) {
        rScriptRunner.run(PLAYER_STATS_SCRIPT_NAME);
        rScriptRunner.run(TEAM_DEFENSE_SCRIPT_NAME);
        rScriptRunner.run(PFR_ADVANCED_RUSHING_SCRIPT_NAME);
      } else {
        log.debug("Stats refresh not due; skipping.");
      }
    } finally {
      refreshInFlight.set(false);
    }

    return CompletableFuture.completedFuture(null);
  }
}
