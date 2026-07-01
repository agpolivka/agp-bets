package com.agp.bets.goforbroke.player.derived.model;

import java.time.Instant;
import java.util.Map;

public record PlayerPrediction(
    String athleteId,
    String displayName,
    String position,
    Map<String, PredictionInterval> projections,
    double confidenceScore,
    Instant generatedAt) {}
