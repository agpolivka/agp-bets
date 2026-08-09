package com.agp.bets.goforbroke.team.service;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TeamRefreshWorker {

  private static final Logger log = LoggerFactory.getLogger(TeamRefreshWorker.class);

  private final TeamSyncService teamSyncService;
  private final Set<String> inFlightTeamIds = ConcurrentHashMap.newKeySet();

  public TeamRefreshWorker(TeamSyncService teamSyncService) {
    this.teamSyncService = teamSyncService;
  }

  // @Async methods returning CompletableFuture propagate exceptions onto the returned future
  // rather than through the calling thread. TeamRefreshService.requestRefreshIfStale (the only
  // caller) fires this and discards the future, so a failure in here used to vanish completely -
  // e.g. a team stuck stale for over a month because ESPN was 403ing every attempt, with nothing
  // in the logs to show it. Catch and log explicitly so that failure mode is visible again.
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
        } catch (RuntimeException exception) {
          log.warn("Failed to refresh upcoming opponent team {}: {}", opponentTeamId, exception.getMessage());
        } finally {
          inFlightTeamIds.remove(opponentTeamId);
        }
      }
    } catch (RuntimeException exception) {
      log.warn("Failed to refresh team {}: {}", teamId, exception.getMessage());
    } finally {
      inFlightTeamIds.remove(teamId);
    }

    return CompletableFuture.completedFuture(null);
  }
}
