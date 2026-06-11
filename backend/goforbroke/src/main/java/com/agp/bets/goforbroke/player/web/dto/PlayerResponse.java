package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.domain.Player;
import java.time.Instant;

public record PlayerResponse(
    Long id,
    String espnAthleteId,
    String displayName,
    String firstName,
    String lastName,
    String position,
    String jerseyNumber,
    String teamName,
    String teamId,
    Boolean active,
    String sourceUrl,
    Instant fetchedAt,
    Instant createdAt,
    Instant updatedAt) {

  public static PlayerResponse from(Player player) {
    return new PlayerResponse(
        player.getId(),
        player.getEspnAthleteId(),
        player.getDisplayName(),
        player.getFirstName(),
        player.getLastName(),
        player.getPosition(),
        player.getJerseyNumber(),
        player.getTeamName(),
        player.getTeamId(),
        player.getActive(),
        player.getSourceUrl(),
        player.getFetchedAt(),
        player.getCreatedAt(),
        player.getUpdatedAt());
  }
}
