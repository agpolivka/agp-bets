package com.agp.bets.goforbroke.team.service;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TeamRefreshWorker {

  private final TeamSyncService teamSyncService;
  private final Set<String> inFlightTeamIds = ConcurrentHashMap.newKeySet();

  public TeamRefreshWorker(TeamSyncService teamSyncService) {
    this.teamSyncService = teamSyncService;
  }

  @Async
  public CompletableFuture<Void> refreshTeamAndOpponentAsync(String teamId) {
    if (teamId == null || teamId.isBlank() || !inFlightTeamIds.add(teamId)) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      var team = teamSyncService.syncTeamByIdIfStale(teamId);
      String opponentTeamId = team.getUpcomingOpponentTeamId();
      if (opponentTeamId != null
          && !opponentTeamId.isBlank()
          && teamSyncService.isTeamMissingOrStale(opponentTeamId)
          && inFlightTeamIds.add(opponentTeamId)) {
        try {
          teamSyncService.syncTeamByIdIfStale(opponentTeamId);
        } finally {
          inFlightTeamIds.remove(opponentTeamId);
        }
      }
      return CompletableFuture.completedFuture(null);
    } finally {
      inFlightTeamIds.remove(teamId);
    }
  }
}
