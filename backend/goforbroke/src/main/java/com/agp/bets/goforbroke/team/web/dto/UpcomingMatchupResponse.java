package com.agp.bets.goforbroke.team.web.dto;

import java.time.LocalDate;

public record UpcomingMatchupResponse(
    String gameId,
    Integer season,
    String gameType,
    Integer week,
    LocalDate gameDate,
    String homeTeamAbbreviation,
    String homeTeamName,
    String awayTeamAbbreviation,
    String awayTeamName,
    double predictedMargin,
    double homeWinProbability,
    long predictedHomeScore,
    long predictedAwayScore,
    boolean predictedTie,
    // Null when predictedTie is true - the winner is derived from the same rounded scores above,
    // so this field and the displayed score can never disagree.
    String predictedWinnerAbbreviation) {}
