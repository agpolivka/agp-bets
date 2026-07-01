package com.agp.bets.goforbroke.player.derived.model;

public record PredictionInterval(
    double mean,
    double lowerBound,
    double upperBound,
    double confidenceLevel) {}
