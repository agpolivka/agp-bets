package com.agp.bets.goforbroke.team.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "agp.team-refresh.schedule", name = "enabled", havingValue = "true")
public class TeamRefreshScheduler {

  private final TeamSyncService teamSyncService;

  public TeamRefreshScheduler(TeamSyncService teamSyncService) {
    this.teamSyncService = teamSyncService;
  }

  @Scheduled(cron = "${agp.team-refresh.schedule.cron:0 0 */6 * * *}")
  public void refreshLinkedTeamsOnSchedule() {
    teamSyncService.syncReferencedTeams();
  }
}
