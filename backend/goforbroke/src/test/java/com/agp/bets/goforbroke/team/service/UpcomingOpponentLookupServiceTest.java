package com.agp.bets.goforbroke.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UpcomingOpponentLookupServiceTest {

  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);

  @Test
  void readsStoredUpcomingOpponentFromTeamData() {
    Team team = new Team();
    team.setEspnTeamId("7");
    team.setUpcomingOpponentTeamId("26");
    team.setUpcomingOpponentName("Seattle Seahawks");
    team.setUpcomingGameDate(LocalDate.parse("2099-10-02"));

    when(teamRepository.findByEspnTeamId("7")).thenReturn(Optional.of(team));

    UpcomingOpponentLookupService service = new UpcomingOpponentLookupService(teamRepository);

    Optional<UpcomingOpponent> opponent = service.findUpcomingOpponent("7");

    assertTrue(opponent.isPresent());
    assertEquals("26", opponent.get().opponentTeamId());
    assertEquals("Seattle Seahawks", opponent.get().opponentName());
    assertEquals("2099-10-02", opponent.get().gameDate().toString());
  }
}
