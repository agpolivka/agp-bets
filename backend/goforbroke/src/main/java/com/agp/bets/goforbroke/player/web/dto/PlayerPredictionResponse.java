package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.derived.model.PlayerPrediction;
import java.time.Instant;
import java.util.Map;

public record PlayerPredictionResponse(
    String athleteId,
    String displayName,
    String position,
    Map<String, PredictionBandResponse> projections,
    double confidenceScore,
    Instant generatedAt) {

  public static PlayerPredictionResponse from(PlayerPrediction prediction) {
    return new PlayerPredictionResponse(
        prediction.athleteId(),
        prediction.displayName(),
        prediction.position(),
        prediction.projections().entrySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> PredictionBandResponse.from(entry.getValue()))),
        prediction.confidenceScore(),
        prediction.generatedAt());
  }
}
