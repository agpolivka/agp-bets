package com.agp.bets.goforbroke.player.derived.model;

import java.time.Instant;

public record PlayerDerivedSnapshot(
    String athleteId,
    String displayName,
    String position,
    int gamesTracked,
    Instant generatedAt) {}
