package com.agp.bets.goforbroke.player.web.dto;

import java.time.Instant;
import java.util.List;

public record PlayerPredictionResponse(
    String athleteId,
    String displayName,
    String position,
    List<PredictionSummaryResponse> projections,
    double confidenceScore,
    Instant generatedAt) {

  public record PredictionSummaryResponse(
      String metric,
      double mean,
      double lowerBound,
      double upperBound,
      int sampleSize,
      double opponentAdjustment,
      double conditionsAdjustment,
      double rushingQualityAdjustment,
      List<String> notes) {}
}
