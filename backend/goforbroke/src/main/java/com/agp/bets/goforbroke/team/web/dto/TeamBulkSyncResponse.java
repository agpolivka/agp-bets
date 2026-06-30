package com.agp.bets.goforbroke.team.web.dto;

import com.agp.bets.goforbroke.team.service.TeamSyncResult;
import java.time.Instant;
import java.util.List;

public record TeamBulkSyncResponse(
    int teamsRequested,
    int teamsSynced,
    int defensiveGamesSynced,
    List<String> failedTeamIds,
    Instant syncedAt) {

  public static TeamBulkSyncResponse from(TeamSyncResult result) {
    return new TeamBulkSyncResponse(
        result.teamsRequested(),
        result.teamsSynced(),
        result.defensiveGamesSynced(),
        result.failedTeamIds(),
        result.syncedAt());
  }
}
