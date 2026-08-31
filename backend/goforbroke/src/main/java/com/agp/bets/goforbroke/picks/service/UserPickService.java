package com.agp.bets.goforbroke.picks.service;

import com.agp.bets.goforbroke.picks.domain.UserPick;
import com.agp.bets.goforbroke.picks.repository.UserPickRepository;
import com.agp.bets.goforbroke.picks.web.dto.PickAccuracyResponse;
import com.agp.bets.goforbroke.picks.web.dto.PickedGameResponse;
import com.agp.bets.goforbroke.picks.web.dto.SubmitPicksRequest.PickSubmission;
import com.agp.bets.goforbroke.picks.web.dto.WeekPicksResponse;
import com.agp.bets.goforbroke.team.domain.NflSchedule;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.repository.NflScheduleRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import com.agp.bets.goforbroke.team.service.NflverseTeamAbbreviations;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tracks a real, user-made weekly pick record against real game outcomes - deliberately
 * independent of this app's own prediction model (see {@code UserPick}'s doc). Built 2026-08-31 so
 * the "could a person just pick 75% of games" question discussed that session has a real, tracked
 * answer instead of an untested self-assessment - same real-data-over-assumption discipline this
 * whole app already applies to the model itself.
 */
@Service
@Transactional(readOnly = true)
public class UserPickService {

  private static final List<String> NON_PRESEASON_GAME_TYPES = List.of("REG", "WC", "DIV", "CON", "SB");

  private final NflScheduleRepository nflScheduleRepository;
  private final TeamRepository teamRepository;
  private final UserPickRepository userPickRepository;
  private final Clock clock = Clock.systemUTC();

  public UserPickService(
      NflScheduleRepository nflScheduleRepository, TeamRepository teamRepository, UserPickRepository userPickRepository) {
    this.nflScheduleRepository = nflScheduleRepository;
    this.teamRepository = teamRepository;
    this.userPickRepository = userPickRepository;
  }

  public WeekPicksResponse getCurrentWeek() {
    List<NflSchedule> nextUnplayed =
        nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(NON_PRESEASON_GAME_TYPES);
    if (nextUnplayed.isEmpty()) {
      return new WeekPicksResponse(null, null, null, List.of(), computeAccuracy());
    }

    NflSchedule earliest = nextUnplayed.get(0);
    return buildWeekResponse(earliest.getSeason(), earliest.getGameType(), earliest.getWeek());
  }

  @Transactional
  public WeekPicksResponse submitPicks(List<PickSubmission> submissions) {
    Instant now = Instant.now();
    Integer season = null;
    String gameType = null;
    Integer week = null;

    for (PickSubmission submission : submissions) {
      if (submission.gameId() == null || submission.pickedTeamAbbreviation() == null || submission.pickedTeamAbbreviation().isBlank()) {
        continue;
      }

      NflSchedule game = nflScheduleRepository.findById(submission.gameId()).orElse(null);
      if (game == null) {
        continue;
      }
      // Only accept a pick for one of the two real teams actually playing this game - guards
      // against a stale/mistyped client payload silently recording nonsense.
      if (!submission.pickedTeamAbbreviation().equals(game.getHomeTeam())
          && !submission.pickedTeamAbbreviation().equals(game.getAwayTeam())) {
        continue;
      }
      // 2026-08-31: reject a pick once real kickoff has passed, not just once a final score has
      // synced (which can lag kickoff by however long until the next stats refresh runs) - a
      // Thursday-night game must lock independently of the rest of its week's Sunday games.
      if (isLocked(game)) {
        continue;
      }

      UserPick pick = userPickRepository.findByGameId(submission.gameId()).orElseGet(UserPick::new);
      boolean isNew = pick.getId() == null;
      pick.setGameId(submission.gameId());
      pick.setSeason(game.getSeason());
      pick.setGameType(game.getGameType());
      pick.setWeek(game.getWeek());
      pick.setPickedTeam(submission.pickedTeamAbbreviation());
      pick.setUpdatedAt(now);
      if (isNew) {
        pick.setCreatedAt(now);
      }
      userPickRepository.save(pick);

      season = game.getSeason();
      gameType = game.getGameType();
      week = game.getWeek();
    }

    if (season == null) {
      return getCurrentWeek();
    }
    return buildWeekResponse(season, gameType, week);
  }

  private WeekPicksResponse buildWeekResponse(Integer season, String gameType, Integer week) {
    List<NflSchedule> weekGames = nflScheduleRepository.findAllBySeasonAndGameTypeAndWeekOrderByGamedayAsc(season, gameType, week);

    Map<String, Team> teamsByEspnAbbreviation =
        teamRepository.findAllByOrderByDisplayNameAsc().stream()
            .collect(Collectors.toMap(Team::getAbbreviation, Function.identity(), (first, second) -> first));

    List<String> gameIds = weekGames.stream().map(NflSchedule::getGameId).toList();
    Map<String, UserPick> picksByGameId =
        userPickRepository.findAllByGameIdIn(gameIds).stream()
            .collect(Collectors.toMap(UserPick::getGameId, Function.identity()));

    List<PickedGameResponse> games = new ArrayList<>();
    for (NflSchedule game : weekGames) {
      Team homeTeam = teamsByEspnAbbreviation.get(NflverseTeamAbbreviations.toEspnAbbreviation(game.getHomeTeam()));
      Team awayTeam = teamsByEspnAbbreviation.get(NflverseTeamAbbreviations.toEspnAbbreviation(game.getAwayTeam()));
      UserPick pick = picksByGameId.get(game.getGameId());
      String pickedTeam = pick == null ? null : pick.getPickedTeam();

      Boolean correct = null;
      if (pickedTeam != null && game.getHomeScore() != null && game.getAwayScore() != null) {
        String winner = winnerOf(game);
        correct = winner != null && winner.equals(pickedTeam);
      }

      games.add(
          new PickedGameResponse(
              game.getGameId(),
              game.getSeason(),
              game.getGameType(),
              game.getWeek(),
              game.getGameday(),
              game.getKickoffAt(),
              isLocked(game),
              game.getHomeTeam(),
              homeTeam == null ? game.getHomeTeam() : homeTeam.getDisplayName(),
              homeTeam == null ? null : homeTeam.getLogoUrl(),
              game.getAwayTeam(),
              awayTeam == null ? game.getAwayTeam() : awayTeam.getDisplayName(),
              awayTeam == null ? null : awayTeam.getLogoUrl(),
              game.getHomeScore(),
              game.getAwayScore(),
              pickedTeam,
              correct));
    }

    return new WeekPicksResponse(season, gameType, week, games, computeAccuracy());
  }

  // Fails open (not locked) when kickoffAt is missing rather than blocking a real pick over an
  // absent value - real risk is low since import_schedules.R has populated this for every current/
  // future game since 2026-08-31, but a missing value is a data gap, not evidence the game already
  // started.
  private boolean isLocked(NflSchedule game) {
    return game.getKickoffAt() != null && !clock.instant().isBefore(game.getKickoffAt());
  }

  // Null for a tie (rare in the NFL, but real - neither team "won", so a pick on a tied game is
  // never gradeable as right or wrong).
  private String winnerOf(NflSchedule game) {
    if (game.getHomeScore() > game.getAwayScore()) {
      return game.getHomeTeam();
    }
    if (game.getAwayScore() > game.getHomeScore()) {
      return game.getAwayTeam();
    }
    return null;
  }

  private PickAccuracyResponse computeAccuracy() {
    List<UserPick> allPicks = userPickRepository.findAll();
    if (allPicks.isEmpty()) {
      return PickAccuracyResponse.of(0, 0);
    }

    Map<String, NflSchedule> gamesById =
        nflScheduleRepository.findAllById(allPicks.stream().map(UserPick::getGameId).toList()).stream()
            .collect(Collectors.toMap(NflSchedule::getGameId, Function.identity()));

    int graded = 0;
    int correct = 0;
    for (UserPick pick : allPicks) {
      NflSchedule game = gamesById.get(pick.getGameId());
      if (game == null || game.getHomeScore() == null || game.getAwayScore() == null) {
        continue;
      }
      String winner = winnerOf(game);
      if (winner == null) {
        continue;
      }
      graded++;
      if (winner.equals(pick.getPickedTeam())) {
        correct++;
      }
    }
    return PickAccuracyResponse.of(graded, correct);
  }
}
