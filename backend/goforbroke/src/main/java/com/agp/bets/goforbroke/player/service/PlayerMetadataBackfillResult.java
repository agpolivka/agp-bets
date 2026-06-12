package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import java.time.Instant;
import java.util.List;

public record PlayerMetadataBackfillResult(
    int candidatesChecked,
    List<Player> refreshedPlayers,
    List<String> failedAthleteIds,
    Instant completedAt) {

  public int refreshedCount() {
    return refreshedPlayers.size();
  }

  public int failedCount() {
    return failedAthleteIds.size();
  }
}
