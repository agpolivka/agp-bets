package com.agp.bets.goforbroke.team.service;

import org.springframework.stereotype.Service;

@Service
public class TeamRefreshService {

  private final TeamSyncService teamSyncService;
  private final TeamRefreshWorker teamRefreshWorker;

  public TeamRefreshService(TeamSyncService teamSyncService, TeamRefreshWorker teamRefreshWorker) {
    this.teamSyncService = teamSyncService;
    this.teamRefreshWorker = teamRefreshWorker;
  }

  public void requestRefreshIfStale(String teamId) {
    if (teamId == null || teamId.isBlank() || !teamSyncService.isTeamMissingOrStale(teamId)) {
      return;
    }

    teamRefreshWorker.refreshTeamAndOpponentAsync(teamId);
  }
}
