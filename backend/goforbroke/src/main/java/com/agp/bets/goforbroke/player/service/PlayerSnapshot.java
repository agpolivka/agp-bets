package com.agp.bets.goforbroke.player.service;

import java.time.Instant;

public record PlayerSnapshot(
    String espnAthleteId,
    String displayName,
    String firstName,
    String lastName,
    String position,
    String jerseyNumber,
    String teamName,
    String teamId,
    Boolean active,
    String sourceUrl,
    String rawPayload,
    Instant fetchedAt) {}
