package com.agp.bets.goforbroke.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Year;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TeamSyncServiceTest {

  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository =
      Mockito.mock(TeamDefenseGameStatRepository.class);
  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final EspnTeamClient espnTeamClient = Mockito.mock(EspnTeamClient.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TeamSyncService service =
      new TeamSyncService(teamRepository, teamDefenseGameStatRepository, playerRepository, espnTeamClient, objectMapper);

  private JsonNode json(String raw) throws Exception {
    return objectMapper.readTree(raw);
  }

  private JsonNode teamProfileJson() throws Exception {
    return json(
        """
        {
          "team": {
            "id": "8",
            "displayName": "Detroit Lions",
            "location": "Detroit",
            "name": "Lions",
            "abbreviation": "DET",
            "isActive": true,
            "franchise": { "venue": {} }
          }
        }
        """);
  }

  private JsonNode emptyScheduleJson() throws Exception {
    return json("{ \"events\": [] }");
  }

  private JsonNode scheduleWithFutureOpponentJson(String isoDate) throws Exception {
    return json(
        """
        {
          "events": [
            {
              "date": "%s",
              "status": { "type": { "completed": false } },
              "competitions": [
                {
                  "status": { "type": { "completed": false } },
                  "competitors": [
                    { "team": { "id": "8" }, "homeAway": "home" },
                    { "team": { "id": "18", "displayName": "New Orleans Saints" }, "homeAway": "away" }
                  ]
                }
              ]
            }
          ]
        }
        """
            .formatted(isoDate));
  }

  @Test
  void syncTeamByIdFindsTheUpcomingOpponentFromWhicheverOfTheFetchedSchedulesActuallyHasOne() throws Exception {
    // 2026-08-31 real bug: a bare `?season=YYYY` request (no seasonType) silently returned only
    // preseason games, so once those completed there was nothing left to find. The fix fetches
    // regular season (2) and postseason (3) separately and merges them - this test simulates that
    // by having 3 of the 4 season/seasonType combinations return no events at all, and only the
    // current year's regular-season fetch return the real future game, proving the merge (not just
    // "whichever fetch runs last") is what finds it.
    int currentYear = Year.now().getValue();
    when(teamRepository.findByEspnTeamId("8")).thenReturn(Optional.empty());
    when(teamRepository.save(Mockito.any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(espnTeamClient.fetchTeamById("8")).thenReturn(teamProfileJson());
    when(espnTeamClient.buildTeamUrl("8")).thenReturn("https://example.test/teams/8");

    when(espnTeamClient.fetchTeamSchedule("8", currentYear - 1, 2)).thenReturn(emptyScheduleJson());
    when(espnTeamClient.fetchTeamSchedule("8", currentYear - 1, 3)).thenReturn(emptyScheduleJson());
    when(espnTeamClient.fetchTeamSchedule("8", currentYear, 3)).thenReturn(emptyScheduleJson());
    when(espnTeamClient.fetchTeamSchedule("8", currentYear, 2))
        .thenReturn(scheduleWithFutureOpponentJson(currentYear + "-12-25T17:00Z"));

    Team result = service.syncTeamById("8");

    assertEquals("18", result.getUpcomingOpponentTeamId());
    assertEquals("New Orleans Saints", result.getUpcomingOpponentName());
    assertEquals(Boolean.TRUE, result.getUpcomingGameIsHome());
  }
}
