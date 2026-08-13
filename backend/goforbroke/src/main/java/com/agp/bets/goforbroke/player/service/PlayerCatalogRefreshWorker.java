package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.etl.RScriptRunner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the full-catalog player refresh (etl/r/import_players.R) when {@link
 * PlayerCatalogRefreshDueChecker} says it's due.
 */
@Service
public class PlayerCatalogRefreshWorker {

  private static final Logger log = LoggerFactory.getLogger(PlayerCatalogRefreshWorker.class);
  private static final String SCRIPT_NAME = "import_players.R";

  private final RScriptRunner rScriptRunner;
  private final PlayerCatalogRefreshDueChecker dueChecker;
  private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

  public PlayerCatalogRefreshWorker(RScriptRunner rScriptRunner, PlayerCatalogRefreshDueChecker dueChecker) {
    this.rScriptRunner = rScriptRunner;
    this.dueChecker = dueChecker;
  }

  @Async
  public CompletableFuture<Void> refreshIfDueAsync() {
    if (!refreshInFlight.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      if (dueChecker.isRefreshDue()) {
        rScriptRunner.run(SCRIPT_NAME);
      } else {
        log.debug("Player catalog refresh not due; skipping.");
      }
    } finally {
      refreshInFlight.set(false);
    }

    return CompletableFuture.completedFuture(null);
  }
}
