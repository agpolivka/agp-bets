package com.agp.bets.goforbroke.picks.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.picks.domain.UserPick;
import com.agp.bets.goforbroke.picks.repository.UserPickRepository;
import com.agp.bets.goforbroke.picks.web.dto.PickedGameResponse;
import com.agp.bets.goforbroke.picks.web.dto.SubmitPicksRequest.PickSubmission;
import com.agp.bets.goforbroke.picks.web.dto.WeekPicksResponse;
import com.agp.bets.goforbroke.team.domain.NflSchedule;
import com.agp.bets.goforbroke.team.repository.NflScheduleRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserPickServiceTest {

  private final NflScheduleRepository nflScheduleRepository = Mockito.mock(NflScheduleRepository.class);
  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
  private final UserPickRepository userPickRepository = Mockito.mock(UserPickRepository.class);
  private final UserPickService service = new UserPickService(nflScheduleRepository, teamRepository, userPickRepository);

  private NflSchedule game(String gameId, int week, String gameday, String home, String away, Integer homeScore, Integer awayScore) {
    NflSchedule game = new NflSchedule();
    game.setGameId(gameId);
    game.setSeason(2026);
    game.setGameType("REG");
    game.setWeek(week);
    game.setGameday(LocalDate.parse(gameday));
    game.setHomeTeam(home);
    game.setAwayTeam(away);
    game.setHomeScore(homeScore);
    game.setAwayScore(awayScore);
    return game;
  }

  @Test
  void getCurrentWeekReturnsTheFullSlateForTheEarliestUnplayedGamesWeekIncludingAlreadyDecidedGames() {
    // Thursday game (week 3) already final; Sunday games (same week) still unplayed - the whole
    // week's slate should come back, not just what's left to pick, so a Thursday pick already made
    // still shows its result.
    NflSchedule thursdayGame = game("2026_03_KC_LV", 3, "2026-09-17", "LV", "KC", 20, 27);
    NflSchedule sundayGame = game("2026_03_BUF_NYJ", 3, "2026-09-20", "NYJ", "BUF", null, null);

    when(nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(Mockito.anyList()))
        .thenReturn(List.of(sundayGame));
    when(nflScheduleRepository.findAllBySeasonAndGameTypeAndWeekOrderByGamedayAsc(2026, "REG", 3))
        .thenReturn(List.of(thursdayGame, sundayGame));
    when(teamRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of());
    when(userPickRepository.findAllByGameIdIn(Mockito.anyList())).thenReturn(List.of());
    when(userPickRepository.findAll()).thenReturn(List.of());

    WeekPicksResponse response = service.getCurrentWeek();

    assertEquals(2026, response.season());
    assertEquals(3, response.week());
    assertEquals(2, response.games().size());
    PickedGameResponse decided = response.games().get(0);
    assertEquals(20, decided.homeScore());
    assertEquals(27, decided.awayScore());
    assertNull(response.accuracy().accuracyPct());
  }

  @Test
  void submitPicksRejectsAPickForAGameWhoseKickoffHasAlreadyPassedEvenWithNoFinalScoreSyncedYet() {
    // 2026-08-31 real scenario: a Thursday game kicks off, but the next stats refresh (which would
    // populate homeScore/awayScore) hasn't run yet - locking must key off real kickoff time, not
    // "do we have a score in our own database yet," or a user could still submit a pick for a game
    // that's already over just because our data lags behind.
    NflSchedule pastKickoffGame = game("2026_03_KC_LV", 3, "2026-09-17", "LV", "KC", null, null);
    pastKickoffGame.setKickoffAt(java.time.Instant.now().minusSeconds(3600));
    when(nflScheduleRepository.findById("2026_03_KC_LV")).thenReturn(java.util.Optional.of(pastKickoffGame));
    when(userPickRepository.findAll()).thenReturn(List.of());

    service.submitPicks(List.of(new PickSubmission("2026_03_KC_LV", "KC")));

    Mockito.verify(userPickRepository, Mockito.never()).save(Mockito.any());
  }

  @Test
  void getCurrentWeekReportsLockedIndependentlyPerGameBasedOnRealKickoffTime() {
    // Same week, two games - one already kicked off (Thursday), one still ahead (Sunday) - locked
    // must be computed per game, not for the whole week at once.
    NflSchedule thursdayGame = game("2026_03_KC_LV", 3, "2026-09-17", "LV", "KC", null, null);
    thursdayGame.setKickoffAt(java.time.Instant.now().minusSeconds(3600));
    NflSchedule sundayGame = game("2026_03_BUF_NYJ", 3, "2026-09-20", "NYJ", "BUF", null, null);
    sundayGame.setKickoffAt(java.time.Instant.now().plusSeconds(3600 * 24 * 3));

    when(nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(Mockito.anyList()))
        .thenReturn(List.of(thursdayGame, sundayGame));
    when(nflScheduleRepository.findAllBySeasonAndGameTypeAndWeekOrderByGamedayAsc(2026, "REG", 3))
        .thenReturn(List.of(thursdayGame, sundayGame));
    when(teamRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of());
    when(userPickRepository.findAllByGameIdIn(Mockito.anyList())).thenReturn(List.of());
    when(userPickRepository.findAll()).thenReturn(List.of());

    WeekPicksResponse response = service.getCurrentWeek();

    PickedGameResponse thursday = response.games().stream().filter(g -> g.gameId().equals("2026_03_KC_LV")).findFirst().orElseThrow();
    PickedGameResponse sunday = response.games().stream().filter(g -> g.gameId().equals("2026_03_BUF_NYJ")).findFirst().orElseThrow();
    assertTrue(thursday.locked());
    assertTrue(!sunday.locked());
  }

  @Test
  void submitPicksCreatesThenUpdatesTheSamePickOnResubmission() {
    NflSchedule targetGame = game("2026_03_BUF_NYJ", 3, "2026-09-20", "NYJ", "BUF", null, null);
    when(nflScheduleRepository.findById("2026_03_BUF_NYJ")).thenReturn(Optional.of(targetGame));
    when(nflScheduleRepository.findAllBySeasonAndGameTypeAndWeekOrderByGamedayAsc(2026, "REG", 3))
        .thenReturn(List.of(targetGame));
    when(teamRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of());
    when(userPickRepository.findAllByGameIdIn(Mockito.anyList())).thenReturn(List.of());
    when(userPickRepository.findAll()).thenReturn(List.of());
    when(userPickRepository.findByGameId("2026_03_BUF_NYJ")).thenReturn(Optional.empty());

    service.submitPicks(List.of(new PickSubmission("2026_03_BUF_NYJ", "BUF")));

    ArgumentCaptor<UserPick> saved = ArgumentCaptor.forClass(UserPick.class);
    Mockito.verify(userPickRepository).save(saved.capture());
    UserPick created = saved.getValue();
    assertEquals("BUF", created.getPickedTeam());
    assertEquals(2026, created.getSeason());

    // Re-submitting a different pick for the same game should update, not duplicate.
    when(userPickRepository.findByGameId("2026_03_BUF_NYJ")).thenReturn(Optional.of(created));
    service.submitPicks(List.of(new PickSubmission("2026_03_BUF_NYJ", "NYJ")));

    Mockito.verify(userPickRepository, Mockito.times(2)).save(Mockito.any());
    assertEquals("NYJ", created.getPickedTeam());
  }

  @Test
  void submitPicksIgnoresAPickForATeamNotActuallyPlayingInThatGame() {
    NflSchedule targetGame = game("2026_03_BUF_NYJ", 3, "2026-09-20", "NYJ", "BUF", null, null);
    when(nflScheduleRepository.findById("2026_03_BUF_NYJ")).thenReturn(Optional.of(targetGame));
    when(userPickRepository.findAll()).thenReturn(List.of());

    service.submitPicks(List.of(new PickSubmission("2026_03_BUF_NYJ", "KC")));

    Mockito.verify(userPickRepository, Mockito.never()).save(Mockito.any());
  }

  @Test
  void accuracyCountsCorrectAndIncorrectPicksButExcludesTiesAndUngradedGames() {
    UserPick correctPick = new UserPick();
    correctPick.setGameId("g1");
    correctPick.setPickedTeam("KC");
    UserPick wrongPick = new UserPick();
    wrongPick.setGameId("g2");
    wrongPick.setPickedTeam("LV");
    UserPick tiedGamePick = new UserPick();
    tiedGamePick.setGameId("g3");
    tiedGamePick.setPickedTeam("BUF");
    UserPick notYetPlayedPick = new UserPick();
    notYetPlayedPick.setGameId("g4");
    notYetPlayedPick.setPickedTeam("NYJ");

    when(userPickRepository.findAll()).thenReturn(List.of(correctPick, wrongPick, tiedGamePick, notYetPlayedPick));
    when(nflScheduleRepository.findAllById(Mockito.anyList()))
        .thenReturn(
            List.of(
                game("g1", 1, "2026-09-13", "KC", "BAL", 27, 20), // KC won, pick was KC -> correct
                game("g2", 1, "2026-09-13", "LV", "DEN", 10, 24), // DEN won, pick was LV -> wrong
                game("g3", 1, "2026-09-13", "BUF", "MIA", 20, 20), // tie -> excluded
                game("g4", 1, "2026-09-13", "NYJ", "NE", null, null))); // not played -> excluded

    WeekPicksResponse response = service.getCurrentWeek();
    assertTrue(response.games().isEmpty()); // no unplayed games stubbed for this call

    // Accuracy comes back the same way from getCurrentWeek() and submitPicks() - checked directly here.
    var accuracy = response.accuracy();
    assertEquals(2, accuracy.gradedPicks());
    assertEquals(1, accuracy.correctPicks());
    assertEquals(50.0d, accuracy.accuracyPct(), 0.0001d);
  }
}
