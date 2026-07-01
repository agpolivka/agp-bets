package com.agp.bets.goforbroke.player.derived.service;

import java.util.List;

public record DerivedFeatureSet(
    String athleteId,
    String position,
    int gamesTracked,
    List<Double> recentPassingYards,
    List<Double> recentRushingYards,
    List<Double> recentReceivingYards,
    List<Double> recentTouchdowns,
    List<Double> opponentAllowedPassingYards,
    List<Double> opponentAllowedRushingYards) {}
