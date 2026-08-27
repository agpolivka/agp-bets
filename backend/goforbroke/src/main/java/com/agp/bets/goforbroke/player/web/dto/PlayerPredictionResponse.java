package com.agp.bets.goforbroke.player.web.dto;

import java.time.Instant;
import java.util.List;

public record PlayerPredictionResponse(
    String athleteId,
    String displayName,
    String position,
    List<PredictionSummaryResponse> projections,
    double confidenceScore,
    Instant generatedAt,
    // Weekly game-status designation (e.g. "Questionable", "Out") from ESPN's injuries feed, if
    // any - see InjuryStatusRefreshWorker. Player-level, not per-metric, since it doesn't vary by
    // stat: whether/how much a player is expected to play affects every projection uniformly, via
    // PlayerPredictionService#injuryStatusMultiplier scaling each PredictionSummaryResponse.mean.
    String gameStatus,
    String gameStatusDetail) {

  public record PredictionSummaryResponse(
      String metric,
      double mean,
      double lowerBound,
      double upperBound,
      int sampleSize,
      double opponentAdjustment,
      double conditionsAdjustment,
      double rushingQualityAdjustment,
      double advancedMetricAdjustment,
      double targetShareAdjustment,
      double usageAdjustment,
      List<String> notes) {}
}
