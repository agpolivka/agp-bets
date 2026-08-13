package com.agp.bets.goforbroke.player.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link PlayerCatalogRefreshWorker} on app startup and on a periodic poll, the same
 * shape as {@code StatsRefreshScheduler}. The startup trigger catches up whenever the app is
 * actually started (important since roster changes aren't tied to a schedule); the poll catches
 * staleness while it stays running. PlayerCatalogRefreshDueChecker/Worker decide whether there's
 * actually anything to do, so triggering "too often" here is harmless.
 */
@Component
public class PlayerCatalogRefreshScheduler {

  private final PlayerCatalogRefreshWorker playerCatalogRefreshWorker;
  private final boolean enabled;

  public PlayerCatalogRefreshScheduler(
      PlayerCatalogRefreshWorker playerCatalogRefreshWorker,
      @Value("${agp.player-catalog-refresh.enabled:true}") boolean enabled) {
    this.playerCatalogRefreshWorker = playerCatalogRefreshWorker;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void refreshOnStartup() {
    if (enabled) {
      playerCatalogRefreshWorker.refreshIfDueAsync();
    }
  }

  @Scheduled(fixedRateString = "${agp.player-catalog-refresh.poll-interval-ms:14400000}")
  public void refreshOnPoll() {
    if (enabled) {
      playerCatalogRefreshWorker.refreshIfDueAsync();
    }
  }
}
