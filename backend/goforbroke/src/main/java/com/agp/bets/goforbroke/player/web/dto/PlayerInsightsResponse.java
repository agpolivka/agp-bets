package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.service.PlayerInsightSplit;
import com.agp.bets.goforbroke.player.service.PlayerStatInsightSummary;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public record PlayerInsightsResponse(
    PlayerResponse player,
    int gamesLoaded,
    int recentGameWindow,
    PlayerStatInsightSummary overallSummary,
    PlayerStatInsightSummary lastFiveSummary,
    PlayerStatInsightSummary lastThreeSummary,
    UpcomingOpponentInsightResponse upcomingOpponent,
    List<PlayerInsightSplitResponse> homeAwaySplits,
    List<PlayerInsightSplitResponse> opponentSplits,
    List<PlayerGameStatResponse> recentGames,
    Instant generatedAt) {

  public static PlayerInsightsResponse of(
      PlayerResponse player,
      int gamesLoaded,
      int recentGameWindow,
      PlayerStatInsightSummary overallSummary,
      PlayerStatInsightSummary lastFiveSummary,
      PlayerStatInsightSummary lastThreeSummary,
      UpcomingOpponentInsightResponse upcomingOpponent,
      List<PlayerInsightSplitResponse> homeAwaySplits,
      List<PlayerInsightSplitResponse> opponentSplits,
      List<PlayerGameStatResponse> recentGames,
      Instant generatedAt) {
    return new PlayerInsightsResponse(
        player,
        gamesLoaded,
        recentGameWindow,
        overallSummary,
        lastFiveSummary,
        lastThreeSummary,
        upcomingOpponent,
        homeAwaySplits,
        opponentSplits,
        recentGames,
        generatedAt);
  }

  public record UpcomingOpponentInsightResponse(
      String opponentTeamId,
      String opponentName,
      LocalDate gameDate,
      PlayerStatInsightSummary lastThreeSummary,
      PlayerStatInsightSummary allTimeSummary) {}

  public record PlayerInsightSplitResponse(
      String splitType, String splitValue, PlayerStatInsightSummary summary) {

    public static PlayerInsightSplitResponse from(PlayerInsightSplit split) {
      return new PlayerInsightSplitResponse(split.splitType(), split.splitValue(), split.summary());
    }
  }
}
