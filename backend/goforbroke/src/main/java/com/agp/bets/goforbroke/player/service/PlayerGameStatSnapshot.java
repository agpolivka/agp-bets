package com.agp.bets.goforbroke.player.service;

import java.time.Instant;
import java.time.LocalDate;

public record PlayerGameStatSnapshot(
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
    String sourceUrl,
    String rawPayload,
    Instant fetchedAt) {}
