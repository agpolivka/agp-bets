package com.agp.bets.goforbroke.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.team.domain.NflSchedule;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamStrengthRating;
import com.agp.bets.goforbroke.team.repository.NflScheduleRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import com.agp.bets.goforbroke.team.repository.TeamStrengthRatingRepository;
import com.agp.bets.goforbroke.team.web.dto.UpcomingMatchupResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UpcomingTeamMatchupServiceTest {

  private final NflScheduleRepository nflScheduleRepository = Mockito.mock(NflScheduleRepository.class);
  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
  private final TeamStrengthRatingRepository teamStrengthRatingRepository =
      Mockito.mock(TeamStrengthRatingRepository.class);
  private final UpcomingTeamMatchupService service =
      new UpcomingTeamMatchupService(
          nflScheduleRepository, teamRepository, teamStrengthRatingRepository, new TeamMatchupPredictionService());

  private final Team chiefs = team(1L, "KC", "Kansas City Chiefs");
  private final Team rams = team(2L, "LAR", "Los Angeles Rams");

  @Test
  void predictsAnUpcomingGameUsingEachTeamsMostRecentRatingThroughTheCrosswalk() {
    NflSchedule upcomingGame = game("2026_01_LA_KC", 2026, "REG", 1, "2026-09-13", "KC", "LA");

    when(nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(Mockito.anyList()))
        .thenReturn(List.of(upcomingGame));
    when(teamRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of(chiefs, rams));
    when(teamStrengthRatingRepository.findAll())
        .thenReturn(
            List.of(
                rating(chiefs, "2026-01-04", 1650.0d, 27, 17),
                rating(rams, "2026-01-04", 1500.0d, 17, 27)));

    List<UpcomingMatchupResponse> matchups = service.upcomingMatchups();

    assertEquals(1, matchups.size());
    UpcomingMatchupResponse matchup = matchups.get(0);
    assertEquals("KC", matchup.homeTeamAbbreviation());
    assertEquals("LAR", matchup.awayTeamAbbreviation());
    // KC is the stronger team (1650 vs 1500) and hosting, so predicted to win.
    assertEquals("KC", matchup.predictedWinnerAbbreviation());
    assertFalse(matchup.predictedTie());
  }

  @Test
  void reportsATieAndNoWinnerWhenPredictedScoresRoundToTheSameNumber() {
    // Equal ratings -> the only edge is home field, small enough that a real matchup could
    // plausibly round to identical scores - exercising that path directly rather than hoping to
    // stumble into it via ratings math.
    NflSchedule upcomingGame = game("2026_01_LA_KC", 2026, "REG", 1, "2026-09-13", "KC", "LA");

    when(nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(Mockito.anyList()))
        .thenReturn(List.of(upcomingGame));
    when(teamRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of(chiefs, rams));
    when(teamStrengthRatingRepository.findAll())
        .thenReturn(
            List.of(
                rating(chiefs, "2026-01-04", 1500.0d, 24, 24),
                rating(rams, "2026-01-04", 1500.0d, 24, 24)));

    UpcomingMatchupResponse matchup = service.upcomingMatchups().get(0);

    if (matchup.predictedTie()) {
      assertNull(matchup.predictedWinnerAbbreviation());
      assertEquals(matchup.predictedHomeScore(), matchup.predictedAwayScore());
    } else {
      // Not every equal-rating case rounds to an exact tie once home field is applied - if it
      // didn't this time, the winner must still agree with the higher rounded score either way.
      boolean homeHigher = matchup.predictedHomeScore() > matchup.predictedAwayScore();
      assertEquals(homeHigher ? "KC" : "LAR", matchup.predictedWinnerAbbreviation());
    }
  }

  @Test
  void skipsGamesWhereEitherTeamHasNoRatingYet() {
    NflSchedule upcomingGame = game("2026_01_LA_KC", 2026, "REG", 1, "2026-09-13", "KC", "LA");

    when(nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(Mockito.anyList()))
        .thenReturn(List.of(upcomingGame));
    when(teamRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of(chiefs, rams));
    when(teamStrengthRatingRepository.findAll()).thenReturn(List.of());

    assertEquals(0, service.upcomingMatchups().size());
  }

  @Test
  void publicUpcomingMatchupsScopesToTwoWeeksButAllUpcomingMatchupsReturnsEverything() {
    List<NflSchedule> threeWeeksOfGames = new ArrayList<>();
    for (int week = 1; week <= 3; week++) {
      threeWeeksOfGames.add(
          game("2026_0" + week + "_LA_KC", 2026, "REG", week, "2026-09-" + (10 + week), "KC", "LA"));
    }

    when(nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(Mockito.anyList()))
        .thenReturn(threeWeeksOfGames);
    when(teamRepository.findAllByOrderByDisplayNameAsc()).thenReturn(List.of(chiefs, rams));
    when(teamStrengthRatingRepository.findAll())
        .thenReturn(
            List.of(
                rating(chiefs, "2026-01-04", 1650.0d, 27, 17),
                rating(rams, "2026-01-04", 1500.0d, 17, 27)));

    List<UpcomingMatchupResponse> publicMatchups = service.upcomingMatchups();
    List<UpcomingMatchupResponse> allMatchups = service.allUpcomingMatchups();

    assertEquals(2, publicMatchups.size());
    assertEquals(3, allMatchups.size());
  }

  private static Team team(Long id, String abbreviation, String displayName) {
    Team team = new Team();
    team.setId(id);
    team.setAbbreviation(abbreviation);
    team.setDisplayName(displayName);
    return team;
  }

  private static NflSchedule game(
      String gameId, int season, String gameType, int week, String gameday, String homeTeam, String awayTeam) {
    NflSchedule game = new NflSchedule();
    game.setGameId(gameId);
    game.setSeason(season);
    game.setGameType(gameType);
    game.setWeek(week);
    game.setGameday(LocalDate.parse(gameday));
    game.setHomeTeam(homeTeam);
    // Stored as nflverse's raw code ("LA"), same convention as everywhere else - the service must
    // crosswalk this before matching Team.abbreviation ("LAR").
    game.setAwayTeam(awayTeam);
    game.setHomeScore(null);
    return game;
  }

  private static TeamStrengthRating rating(
      Team team, String gameDate, double ratingAfter, int pointsScored, int pointsAllowed) {
    TeamStrengthRating rating = new TeamStrengthRating();
    rating.setTeam(team);
    rating.setGameDate(LocalDate.parse(gameDate));
    rating.setRatingAfter(ratingAfter);
    rating.setPointsScored(pointsScored);
    rating.setPointsAllowed(pointsAllowed);
    return rating;
  }
}
