package com.agp.bets.goforbroke.etl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link StatsRefreshWorker} on app startup and on a periodic poll, rather than a fixed
 * weekly cron slot. A wall-clock cron only fires if the process happens to be running at that
 * exact moment - not a safe assumption for local dev, and not actually tied to when games are
 * played anyway (Thursday/Saturday/Sunday/Monday). The startup trigger catches up whenever the
 * app is actually started; the poll catches games completed while it stays running.
 * StatsRefreshDueChecker/StatsRefreshWorker decide whether there's actually anything to do, so
 * triggering "too often" here is harmless.
 */
@Component
public class StatsRefreshScheduler {

  private final StatsRefreshWorker statsRefreshWorker;
  private final boolean enabled;

  public StatsRefreshScheduler(
      StatsRefreshWorker statsRefreshWorker,
      @Value("${agp.stats-refresh.enabled:true}") boolean enabled) {
    this.statsRefreshWorker = statsRefreshWorker;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void refreshOnStartup() {
    if (enabled) {
      statsRefreshWorker.refreshIfDueAsync();
    }
  }

  @Scheduled(fixedRateString = "${agp.stats-refresh.poll-interval-ms:14400000}")
  public void refreshOnPoll() {
    if (enabled) {
      statsRefreshWorker.refreshIfDueAsync();
    }
  }
}
