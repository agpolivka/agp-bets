package com.agp.bets.goforbroke.etl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the schedule refresh, the stored-players stats refresh, the team defense refresh, the team
 * offense refresh, the PFR advanced rushing enrichment, the Next Gen Stats enrichment, the snap
 * count enrichment, the PFR advanced defense enrichment, the participation (coverage-scheme/
 * pass-rush) enrichment, and the team-strength (Elo) recompute when {@link StatsRefreshDueChecker}
 * says it's due - all ten change on the same signal (a game completed), so they share one
 * due-check instead of each needing their own. None of the ten scripts take a season argument
 * here; all default to nflverse's current season (or, for {@code import_schedules.R}, the current
 * calendar year - see that script) on their own.
 *
 * <p>Order matters at both ends: {@code import_schedules.R} runs first because everything else
 * (player stats' schedule join for game_date/weather/Vegas columns, and the Elo recompute's own
 * completed-game read) depends on {@code nfl_schedules} already reflecting this run's newest final
 * scores/posted lines - added 2026-08-20 together with {@code compute_team_strength_ratings.R}
 * (previously both had to be run manually, so the {@code /matchups} page and Elo ratings would go
 * stale as soon as new games were actually played). {@code import_pfr_defense_advanced.R} and
 * {@code import_participation_defense.R} both have to run after {@code import_team_defense.R} for
 * the reason already documented below - they only UPDATE rows that script already wrote. {@code
 * import_team_offense.R} (2026-08-28, Priority 5's style-matchup work) doesn't share that
 * dependency - it writes its own table - but runs alongside the other team-level imports for
 * grouping/consistency.
 */
@Service
public class StatsRefreshWorker {

  private static final Logger log = LoggerFactory.getLogger(StatsRefreshWorker.class);
  private static final String SCHEDULES_SCRIPT_NAME = "import_schedules.R";
  private static final String PLAYER_STATS_SCRIPT_NAME = "refresh_stored_players_weekly.R";
  private static final String TEAM_DEFENSE_SCRIPT_NAME = "import_team_defense.R";
  private static final String TEAM_OFFENSE_SCRIPT_NAME = "import_team_offense.R";
  private static final String PFR_ADVANCED_RUSHING_SCRIPT_NAME = "import_pfr_advanced_rushing.R";
  private static final String NEXTGEN_STATS_SCRIPT_NAME = "import_nextgen_stats.R";
  private static final String SNAP_COUNTS_SCRIPT_NAME = "import_snap_counts.R";
  private static final String PFR_ADVANCED_DEFENSE_SCRIPT_NAME = "import_pfr_defense_advanced.R";
  private static final String PARTICIPATION_DEFENSE_SCRIPT_NAME = "import_participation_defense.R";
  private static final String TEAM_STRENGTH_RATINGS_SCRIPT_NAME = "compute_team_strength_ratings.R";

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
        rScriptRunner.run(SCHEDULES_SCRIPT_NAME);
        rScriptRunner.run(PLAYER_STATS_SCRIPT_NAME);
        rScriptRunner.run(TEAM_DEFENSE_SCRIPT_NAME);
        rScriptRunner.run(TEAM_OFFENSE_SCRIPT_NAME);
        rScriptRunner.run(PFR_ADVANCED_RUSHING_SCRIPT_NAME);
        rScriptRunner.run(NEXTGEN_STATS_SCRIPT_NAME);
        rScriptRunner.run(SNAP_COUNTS_SCRIPT_NAME);
        rScriptRunner.run(PFR_ADVANCED_DEFENSE_SCRIPT_NAME);
        rScriptRunner.run(PARTICIPATION_DEFENSE_SCRIPT_NAME);
        rScriptRunner.run(TEAM_STRENGTH_RATINGS_SCRIPT_NAME);
      } else {
        log.debug("Stats refresh not due; skipping.");
      }
    } finally {
      refreshInFlight.set(false);
    }

    return CompletableFuture.completedFuture(null);
  }
}
