package com.agp.bets.goforbroke.team.web.dto;

import com.agp.bets.goforbroke.team.domain.Team;
import java.time.Instant;

public record TeamSyncResponse(
    TeamResponse team,
    String sourceType,
    String sourceValue,
    Instant syncedAt) {

  public static TeamSyncResponse from(Team team, String sourceType, String sourceValue, Instant syncedAt) {
    return new TeamSyncResponse(TeamResponse.from(team), sourceType, sourceValue, syncedAt);
  }
}
