package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import java.time.Instant;
import java.time.LocalDate;

public record PlayerGameStatResponse(
    Long id,
    LocalDate gameDate,
    Integer season,
    Integer week,
    String homeAway,
    String opponentName,
    String opponentTeamId,
    Integer gamesPlayed,
    Integer passingYards,
    Integer rushingYards,
    Integer totalYards,
    Integer passingTouchdowns,
    Integer rushingTouchdowns,
    Integer receivingTouchdowns,
    Integer touchdowns,
    Integer totalTouchdowns,
    Integer interceptions,
    Integer fumbles,
    Integer fumblesLost,
    Integer turnovers,
    Integer snapCount,
    Integer carries,
    Integer receivingTargets,
    Integer receptions,
    Integer receivingYards,
    Integer drops,
    Instant fetchedAt,
    Instant updatedAt) {

  public static PlayerGameStatResponse from(PlayerGameStat stat) {
    return new PlayerGameStatResponse(
        stat.getId(),
        stat.getGameDate(),
        stat.getSeason(),
        stat.getWeek(),
        stat.getHomeAway(),
        stat.getOpponentName(),
        stat.getOpponentTeamId(),
        stat.getGamesPlayed(),
        stat.getPassingYards(),
        stat.getRushingYards(),
        stat.getTotalYards(),
        stat.getPassingTouchdowns(),
        stat.getRushingTouchdowns(),
        stat.getReceivingTouchdowns(),
        stat.getTouchdowns(),
        stat.getTotalTouchdowns(),
        stat.getInterceptions(),
        stat.getFumbles(),
        stat.getFumblesLost(),
        stat.getTurnovers(),
        stat.getSnapCount(),
        stat.getCarries(),
        stat.getReceivingTargets(),
        stat.getReceptions(),
        stat.getReceivingYards(),
        stat.getDrops(),
        stat.getFetchedAt(),
        stat.getUpdatedAt());
  }
}
