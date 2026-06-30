package com.agp.bets.goforbroke.team.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import org.springframework.stereotype.Service;

@Service
public class UpcomingOpponentLookupService {

  private final TeamRepository teamRepository;
  private final Clock clock = Clock.systemUTC();

  public UpcomingOpponentLookupService(TeamRepository teamRepository) {
    this.teamRepository = teamRepository;
  }

  public Optional<UpcomingOpponent> findUpcomingOpponent(String teamId) {
    if (teamId == null || teamId.isBlank()) {
      return Optional.empty();
    }

    LocalDate today = LocalDate.now(clock);
    return teamRepository
        .findByEspnTeamId(teamId)
        .filter(team -> team.getUpcomingGameDate() != null && !team.getUpcomingGameDate().isBefore(today))
        .map(
            team ->
                new UpcomingOpponent(
                    team.getUpcomingOpponentTeamId(),
                    team.getUpcomingOpponentName(),
                    team.getUpcomingGameDate()));
  }
}
