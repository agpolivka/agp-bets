package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.domain.Player;
import java.time.Instant;

public record PlayerSyncResponse(
    PlayerResponse player, String sourceType, String sourceValue, Instant syncedAt) {

  public static PlayerSyncResponse from(Player player, String sourceType, String sourceValue) {
    return new PlayerSyncResponse(PlayerResponse.from(player), sourceType, sourceValue, player.getUpdatedAt());
  }
}
