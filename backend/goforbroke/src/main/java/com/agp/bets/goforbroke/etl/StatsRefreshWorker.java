package com.agp.bets.goforbroke.etl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Runs the stored-players stats refresh script when {@link StatsRefreshDueChecker} says it's due. */
@Service
public class StatsRefreshWorker {

  private static final Logger log = LoggerFactory.getLogger(StatsRefreshWorker.class);
  private static final String SCRIPT_NAME = "refresh_stored_players_weekly.R";

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
        rScriptRunner.run(SCRIPT_NAME);
      } else {
        log.debug("Stats refresh not due; skipping.");
      }
    } finally {
      refreshInFlight.set(false);
    }

    return CompletableFuture.completedFuture(null);
  }
}
