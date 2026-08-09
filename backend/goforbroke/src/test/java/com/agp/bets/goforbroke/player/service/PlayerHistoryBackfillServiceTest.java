package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

class PlayerHistoryBackfillServiceTest {

  private final PlayerHistoryBackfillRunner runner = Mockito.mock(PlayerHistoryBackfillRunner.class);

  @Test
  void requestBackfillIfNeededRunsTheHistoryScriptForTheAthlete() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(runner, true);

    service.requestBackfillIfNeeded("4426338");

    verify(runner).run(Mockito.eq("4426338"), Mockito.any());
  }

  @Test
  void requestBackfillIfNeededDoesNothingWhenDisabled() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(runner, false);

    service.requestBackfillIfNeeded("4426338");

    verifyNoInteractions(runner);
  }

  @Test
  void requestBackfillIfNeededDoesNothingForBlankAthleteId() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(runner, true);

    service.requestBackfillIfNeeded(" ");

    verifyNoInteractions(runner);
  }

  @Test
  void requestBackfillIfNeededSkipsReentrantCallForAthleteAlreadyInFlight() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(runner, true);

    service.requestBackfillIfNeeded("4426338");
    service.requestBackfillIfNeeded("4426338");

    verify(runner, times(1)).run(Mockito.eq("4426338"), Mockito.any());
  }

  @Test
  void isBackfillInProgressReflectsInFlightStateSynchronously() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(runner, true);
    assertFalse(service.isBackfillInProgress("4426338"));

    service.requestBackfillIfNeeded("4426338");

    assertTrue(service.isBackfillInProgress("4426338"));
  }

  @Test
  void isBackfillInProgressClearsOnceTheRunnerCompletes() {
    PlayerHistoryBackfillService service = new PlayerHistoryBackfillService(runner, true);
    Mockito.doAnswer(
            (InvocationOnMock invocation) -> {
              Runnable onComplete = invocation.getArgument(1);
              onComplete.run();
              return null;
            })
        .when(runner)
        .run(Mockito.eq("4426338"), Mockito.any());

    service.requestBackfillIfNeeded("4426338");

    assertFalse(service.isBackfillInProgress("4426338"));
  }
}
