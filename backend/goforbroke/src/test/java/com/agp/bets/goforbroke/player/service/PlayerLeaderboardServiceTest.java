package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse.PredictionSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerLeaderboardServiceTest {

  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final PlayerPredictionService playerPredictionService = Mockito.mock(PlayerPredictionService.class);
  private final PlayerLeaderboardService service = new PlayerLeaderboardService(playerRepository, playerPredictionService);

  private Player player(String athleteId, String name, String team) {
    Player player = new Player();
    player.setEspnAthleteId(athleteId);
    player.setDisplayName(name);
    player.setTeamName(team);
    return player;
  }

  private PlayerPredictionResponse predictionWithPassingYards(String displayName, double mean) {
    return new PlayerPredictionResponse(
        "id",
        displayName,
        "QB",
        List.of(new PredictionSummaryResponse("passingYards", mean, 0, 0, 10, 0, 0, 0, 0, 0, 0, List.of())),
        1.0d,
        Instant.now(),
        null,
        null);
  }

  @Test
  void findTopAthleteIdPicksTheCandidateWithTheHighestProjectedValueForThatPositionsHeadlineMetric() {
    Player lowerProjected = player("1", "Lower QB", "Team A");
    Player higherProjected = player("2", "Higher QB", "Team B");
    when(playerRepository.findActiveCandidatesByPosition("QB", 5L)).thenReturn(List.of(lowerProjected, higherProjected));
    when(playerPredictionService.getPredictionForAthleteId("1")).thenReturn(predictionWithPassingYards("Lower QB", 220.0d));
    when(playerPredictionService.getPredictionForAthleteId("2")).thenReturn(predictionWithPassingYards("Higher QB", 310.0d));

    assertEquals("2", service.findTopAthleteId("QB"));
  }

  @Test
  void findTopAthleteIdSkipsACandidateWhosePredictionThrowsInsteadOfFailingTheWholeLeaderboard() {
    Player broken = player("1", "Broken QB", "Team A");
    Player healthy = player("2", "Healthy QB", "Team B");
    when(playerRepository.findActiveCandidatesByPosition("QB", 5L)).thenReturn(List.of(broken, healthy));
    when(playerPredictionService.getPredictionForAthleteId("1")).thenThrow(new RuntimeException("stale metadata"));
    when(playerPredictionService.getPredictionForAthleteId("2")).thenReturn(predictionWithPassingYards("Healthy QB", 250.0d));

    assertEquals("2", service.findTopAthleteId("QB"));
  }

  @Test
  void findTopAthleteIdReturnsNullWhenNoCandidatesHaveARealProjection() {
    when(playerRepository.findActiveCandidatesByPosition("QB", 5L)).thenReturn(List.of());

    assertNull(service.findTopAthleteId("QB"));
  }

  @Test
  void currentValueForAlwaysReadsTheLivePredictionInsteadOfAStaleSnapshot() {
    // 2026-08-31 real bug this guards against: the homepage once showed 304.0 for a player whose
    // own page showed 291.5 moments later, because the leaderboard cached the projected VALUE
    // alongside the winner's identity. currentValueFor must always call
    // getPredictionForAthleteId fresh (which is itself cache-backed at the PlayerPredictionService
    // layer - the same cache a direct page visit would hit) rather than trusting an old number, so
    // two calls with a changed mock answer must reflect the change, not repeat the first value.
    when(playerRepository.findByEspnAthleteId("2")).thenReturn(Optional.of(player("2", "Jared Goff", "Detroit Lions")));
    when(playerPredictionService.getPredictionForAthleteId("2"))
        .thenReturn(predictionWithPassingYards("Jared Goff", 304.0d))
        .thenReturn(predictionWithPassingYards("Jared Goff", 291.5d));

    PlayerLeaderboardService.TopProjectedPlayer first = service.currentValueFor("2", "QB");
    PlayerLeaderboardService.TopProjectedPlayer second = service.currentValueFor("2", "QB");

    assertEquals(304.0d, first.value(), 0.0001d);
    assertEquals(291.5d, second.value(), 0.0001d);
  }

  @Test
  void currentValueForReturnsNullForANullAthleteIdInsteadOfCallingThePredictionService() {
    assertNull(service.currentValueFor(null, "QB"));
    Mockito.verifyNoInteractions(playerPredictionService);
  }
}
