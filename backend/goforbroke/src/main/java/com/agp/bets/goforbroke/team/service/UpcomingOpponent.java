package com.agp.bets.goforbroke.team.service;

import java.time.LocalDate;

public record UpcomingOpponent(String opponentTeamId, String opponentName, LocalDate gameDate) {}
