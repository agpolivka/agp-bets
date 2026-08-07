package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.etl.RScriptRunner;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerHistoryBackfillServiceTest {

  private final RScriptRunner rScriptRunner = Mockito.mock(RScriptRunner.class);

  @Test
  void requestBackfillIfNeededRunsTheHistoryScriptForTheAthlete() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(rScriptRunner, true);
    when(rScriptRunner.run("import_player_history.R", "4426338")).thenReturn(true);

    service.requestBackfillIfNeeded("4426338");

    verify(rScriptRunner).run("import_player_history.R", "4426338");
  }

  @Test
  void requestBackfillIfNeededDoesNothingWhenDisabled() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(rScriptRunner, false);

    service.requestBackfillIfNeeded("4426338");

    verifyNoInteractions(rScriptRunner);
  }

  @Test
  void requestBackfillIfNeededDoesNothingForBlankAthleteId() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(rScriptRunner, true);

    service.requestBackfillIfNeeded(" ");

    verifyNoInteractions(rScriptRunner);
  }

  @Test
  void backfillAsyncSkipsReentrantCallForAthleteAlreadyInFlight() throws Exception {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(rScriptRunner, true);
    when(rScriptRunner.run("import_player_history.R", "4426338"))
        .thenAnswer(
            invocation -> {
              CompletableFuture<Void> reentrant = service.backfillAsync("4426338");
              assertFalse(reentrant.isCompletedExceptionally());
              return true;
            });

    service.backfillAsync("4426338").get();

    verify(rScriptRunner, times(1)).run("import_player_history.R", "4426338");
  }
}
