package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.derived.model.PredictionInterval;

public record PredictionBandResponse(
    double mean,
    double lowerBound,
    double upperBound,
    double confidenceLevel) {

  public static PredictionBandResponse from(PredictionInterval interval) {
    return new PredictionBandResponse(
        interval.mean(),
        interval.lowerBound(),
        interval.upperBound(),
        interval.confidenceLevel());
  }
}
