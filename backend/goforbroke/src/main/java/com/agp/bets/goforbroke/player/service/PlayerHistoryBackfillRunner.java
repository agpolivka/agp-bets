package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.etl.RScriptRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs the actual R backfill script in the background. Split out from {@link
 * PlayerHistoryBackfillService} purely so {@code @Async} works - see that class's doc comment.
 */
@Component
class PlayerHistoryBackfillRunner {

  private static final String SCRIPT_NAME = "import_player_history.R";

  private final RScriptRunner rScriptRunner;

  PlayerHistoryBackfillRunner(RScriptRunner rScriptRunner) {
    this.rScriptRunner = rScriptRunner;
  }

  @Async
  public void run(String athleteId, Runnable onComplete) {
    try {
      rScriptRunner.run(SCRIPT_NAME, athleteId);
    } finally {
      onComplete.run();
    }
  }
}
