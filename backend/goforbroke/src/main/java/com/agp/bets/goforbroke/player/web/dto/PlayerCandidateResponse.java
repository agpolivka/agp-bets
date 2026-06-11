package com.agp.bets.goforbroke.player.web.dto;

import com.agp.bets.goforbroke.player.service.AthleteCandidate;

public record PlayerCandidateResponse(
    String espnAthleteId,
    String displayName,
    String firstName,
    String lastName,
    String position,
    String jerseyNumber,
    String teamName,
    String teamId,
    Boolean active,
    double score,
    boolean stored) {

  public static PlayerCandidateResponse from(AthleteCandidate candidate, boolean stored) {
    return new PlayerCandidateResponse(
        candidate.espnAthleteId(),
        candidate.displayName(),
        candidate.firstName(),
        candidate.lastName(),
        candidate.position(),
        candidate.jerseyNumber(),
        candidate.teamName(),
        candidate.teamId(),
        candidate.active(),
        candidate.score(),
        stored);
  }
}
