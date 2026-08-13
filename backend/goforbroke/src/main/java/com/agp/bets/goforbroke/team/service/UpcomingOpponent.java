package com.agp.bets.goforbroke.team.service;

import java.time.Instant;
import java.time.LocalDate;

public record UpcomingOpponent(
    String opponentTeamId, String opponentName, LocalDate gameDate, Instant gameTime, Boolean isHomeGame) {}
