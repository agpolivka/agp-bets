package com.agp.bets.goforbroke.team.web.dto;

import com.agp.bets.goforbroke.team.service.TeamDefenseSummary;

public record TeamDefenseSummaryResponse(
    int games,
    double pointsAllowedPerGame,
    double totalYardsAllowedPerGame,
    double passingYardsAllowedPerGame,
    double receivingYardsAllowedPerGame,
    double rushingYardsAllowedPerGame,
    double turnoversForcedPerGame,
    double interceptionsPerGame,
    double fumbleRecoveriesPerGame,
    double sacksPerGame) {

  public static TeamDefenseSummaryResponse from(TeamDefenseSummary summary) {
    return new TeamDefenseSummaryResponse(
        summary.games(),
        summary.pointsAllowedPerGame(),
        summary.totalYardsAllowedPerGame(),
        summary.passingYardsAllowedPerGame(),
        summary.receivingYardsAllowedPerGame(),
        summary.rushingYardsAllowedPerGame(),
        summary.turnoversForcedPerGame(),
        summary.interceptionsPerGame(),
        summary.fumbleRecoveriesPerGame(),
        summary.sacksPerGame());
  }
}
