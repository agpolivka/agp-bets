package com.agp.bets.goforbroke.team.service;

public record TeamDefenseSummary(
    int games,
    double pointsAllowedPerGame,
    double totalYardsAllowedPerGame,
    double passingYardsAllowedPerGame,
    double receivingYardsAllowedPerGame,
    double rushingYardsAllowedPerGame,
    double turnoversForcedPerGame,
    double interceptionsPerGame,
    double fumbleRecoveriesPerGame,
    double sacksPerGame) {}
