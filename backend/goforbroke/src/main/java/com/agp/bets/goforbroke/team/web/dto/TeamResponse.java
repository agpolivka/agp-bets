package com.agp.bets.goforbroke.team.web.dto;

import com.agp.bets.goforbroke.team.domain.Team;
import java.time.Instant;
import java.time.LocalDate;

public record TeamResponse(
    Long id,
    String espnTeamId,
    String displayName,
    String location,
    String name,
    String abbreviation,
    String logoUrl,
    String recordSummary,
    String standingSummary,
    String upcomingOpponentTeamId,
    String upcomingOpponentName,
    LocalDate upcomingGameDate,
    String venueName,
    Boolean venueIndoor,
    Boolean venueGrass,
    String baseDefensiveScheme,
    Instant fetchedAt,
    Instant createdAt,
    Instant updatedAt) {

  public static TeamResponse from(Team team) {
    return new TeamResponse(
        team.getId(),
        team.getEspnTeamId(),
        team.getDisplayName(),
        team.getLocation(),
        team.getName(),
        team.getAbbreviation(),
        team.getLogoUrl(),
        team.getRecordSummary(),
        team.getStandingSummary(),
        team.getUpcomingOpponentTeamId(),
        team.getUpcomingOpponentName(),
        team.getUpcomingGameDate(),
        team.getVenueName(),
        team.getVenueIndoor(),
        team.getVenueGrass(),
        team.getBaseDefensiveScheme(),
        team.getFetchedAt(),
        team.getCreatedAt(),
        team.getUpdatedAt());
  }
}
