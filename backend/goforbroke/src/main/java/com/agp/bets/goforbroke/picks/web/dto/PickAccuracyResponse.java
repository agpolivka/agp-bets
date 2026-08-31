package com.agp.bets.goforbroke.picks.web.dto;

/**
 * Overall record across every graded pick ever made (not scoped to the current week) - {@code
 * accuracyPct} is null (not 0) when {@code gradedPicks} is 0, so the frontend shows a real "N/A"
 * for a brand new pick history instead of a misleading 0%.
 */
public record PickAccuracyResponse(int gradedPicks, int correctPicks, Double accuracyPct) {

  public static PickAccuracyResponse of(int gradedPicks, int correctPicks) {
    Double accuracyPct = gradedPicks == 0 ? null : (100.0d * correctPicks) / gradedPicks;
    return new PickAccuracyResponse(gradedPicks, correctPicks, accuracyPct);
  }
}
