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

    worker.refreshIfDueAsync().get();

    verify(rScriptRunner).run("refresh_stored_players_weekly.R");
    verify(rScriptRunner).run("import_team_defense.R");
    verify(rScriptRunner).run("import_pfr_advanced_rushing.R");
  }

  @Test
  void refreshIfDueAsyncSkipsScriptWhenNotDue() throws Exception {
    when(statsRefreshDueChecker.isRefreshDue()).thenReturn(false);

    worker.refreshIfDueAsync().get();

    verify(rScriptRunner, never()).run(Mockito.anyString());
  }

  @Test
  void refreshIfDueAsyncSkipsReentrantCallWhileAlreadyRunning() throws Exception {
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
