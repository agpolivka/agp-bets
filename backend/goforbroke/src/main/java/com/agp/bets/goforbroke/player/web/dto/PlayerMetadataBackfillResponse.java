package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.service.PlayerMetadataBackfillResult;
import java.time.Instant;
import java.util.List;

public record PlayerMetadataBackfillResponse(
    int candidatesChecked,
    int refreshedCount,
    int failedCount,
    List<PlayerResponse> refreshedPlayers,
    List<String> failedAthleteIds,
    Instant completedAt) {

  public static PlayerMetadataBackfillResponse from(PlayerMetadataBackfillResult result) {
    return new PlayerMetadataBackfillResponse(
        result.candidatesChecked(),
        result.refreshedCount(),
        result.failedCount(),
        result.refreshedPlayers().stream().map(PlayerResponse::from).toList(),
        result.failedAthleteIds(),
        result.completedAt());
  }
}
