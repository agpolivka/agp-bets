package com.agp.bets.goforbroke.player.service;

public record AthleteCandidate(
    String espnAthleteId,
    String displayName,
    String firstName,
    String lastName,
    String position,
    String jerseyNumber,
    String teamName,
    String teamId,
    Boolean active,
    double score) {}
