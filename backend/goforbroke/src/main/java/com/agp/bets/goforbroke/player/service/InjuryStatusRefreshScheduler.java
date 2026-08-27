package com.agp.bets.goforbroke.player.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link InjuryStatusRefreshWorker} on app startup and on a periodic poll - same
 * startup-plus-poll shape as {@code PlayerCatalogRefreshScheduler}, since weekly status changes
 * (a new questionable/doubtful/out designation, or a recovered player coming off the list) aren't
 * tied to any schedule this app already tracks.
 */
@Component
public class InjuryStatusRefreshScheduler {

  private final InjuryStatusRefreshWorker injuryStatusRefreshWorker;
  private final boolean enabled;

  public InjuryStatusRefreshScheduler(
      InjuryStatusRefreshWorker injuryStatusRefreshWorker,
      @Value("${agp.injury-refresh.enabled:true}") boolean enabled) {
    this.injuryStatusRefreshWorker = injuryStatusRefreshWorker;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void refreshOnStartup() {
    if (enabled) {
      injuryStatusRefreshWorker.refreshAsync();
    }
  }

  @Scheduled(fixedRateString = "${agp.injury-refresh.poll-interval-ms:21600000}")
  public void refreshOnPoll() {
    if (enabled) {
      injuryStatusRefreshWorker.refreshAsync();
    }
  }
}
