package com.agp.bets.goforbroke.etl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StatsRefreshWorkerTest {

  private final RScriptRunner rScriptRunner = Mockito.mock(RScriptRunner.class);
  private final StatsRefreshDueChecker statsRefreshDueChecker = Mockito.mock(StatsRefreshDueChecker.class);
  private final StatsRefreshWorker worker = new StatsRefreshWorker(rScriptRunner, statsRefreshDueChecker);

  @Test
  void refreshIfDueAsyncRunsScriptWhenDue() throws Exception {
    when(statsRefreshDueChecker.isRefreshDue()).thenReturn(true);
    when(rScriptRunner.run(Mockito.anyString())).thenReturn(true);

    worker.refreshIfDueAsync().get();

    verify(rScriptRunner).run("import_schedules.R");
    verify(rScriptRunner).run("refresh_stored_players_weekly.R");
    verify(rScriptRunner).run("import_team_defense.R");
    verify(rScriptRunner).run("import_team_offense.R");
    verify(rScriptRunner).run("import_pfr_advanced_rushing.R");
    verify(rScriptRunner).run("import_nextgen_stats.R");
    verify(rScriptRunner).run("import_snap_counts.R");
    verify(rScriptRunner).run("import_pfr_defense_advanced.R");
    verify(rScriptRunner).run("import_participation_defense.R");
    verify(rScriptRunner).run("compute_team_strength_ratings.R");
  }

  @Test
  void refreshIfDueAsyncAbortsTheWholeChainWhenSchedulesImportFails() throws Exception {
    // 2026-08-30: the one hard, documented dependency - everything else assumes nfl_schedules is
    // already fresh, so a failed schedules import should stop the chain instead of letting the
    // other nine scripts run against stale schedule data.
    when(statsRefreshDueChecker.isRefreshDue()).thenReturn(true);
    when(rScriptRunner.run("import_schedules.R")).thenReturn(false);

    worker.refreshIfDueAsync().get();

    verify(rScriptRunner).run("import_schedules.R");
    verify(rScriptRunner, never()).run("refresh_stored_players_weekly.R");
    verify(rScriptRunner, never()).run("compute_team_strength_ratings.R");
  }

  @Test
  void refreshIfDueAsyncKeepsRunningTheRestOfTheChainWhenANonSchedulesScriptFails() throws Exception {
    // Unlike import_schedules.R above, the other nine are largely independent enrichments - one
    // failing (snap counts here) shouldn't block the rest, e.g. the Elo recompute at the very end.
    when(statsRefreshDueChecker.isRefreshDue()).thenReturn(true);
    when(rScriptRunner.run(Mockito.anyString())).thenReturn(true);
    when(rScriptRunner.run("import_snap_counts.R")).thenReturn(false);

    worker.refreshIfDueAsync().get();

    verify(rScriptRunner).run("import_pfr_defense_advanced.R");
    verify(rScriptRunner).run("import_participation_defense.R");
    verify(rScriptRunner).run("compute_team_strength_ratings.R");
  }

  @Test
  void refreshIfDueAsyncSkipsScriptWhenNotDue() throws Exception {
    when(statsRefreshDueChecker.isRefreshDue()).thenReturn(false);

    worker.refreshIfDueAsync().get();

    verify(rScriptRunner, never()).run(Mockito.anyString());
  }

  @Test
  void refreshIfDueAsyncSkipsReentrantCallWhileAlreadyRunning() throws Exception {
    when(rScriptRunner.run(Mockito.anyString())).thenReturn(true);
    when(statsRefreshDueChecker.isRefreshDue())
        .thenAnswer(
            invocation -> {
              CompletableFuture<Void> reentrant = worker.refreshIfDueAsync();
              assertFalse(reentrant.isCompletedExceptionally());
              return true;
            });

    worker.refreshIfDueAsync().get();

    verify(statsRefreshDueChecker, times(1)).isRefreshDue();
    verify(rScriptRunner, times(1)).run("refresh_stored_players_weekly.R");
    verify(rScriptRunner, times(1)).run("import_team_defense.R");
  }
}
