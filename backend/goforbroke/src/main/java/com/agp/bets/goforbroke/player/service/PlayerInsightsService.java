package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.web.dto.PlayerGameStatResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerInsightsResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerInsightsResponse.PlayerInsightSplitResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerInsightsResponse.UpcomingOpponentInsightResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerResponse;
import com.agp.bets.goforbroke.team.service.TeamRefreshService;
import com.agp.bets.goforbroke.team.service.UpcomingOpponent;
import com.agp.bets.goforbroke.team.service.UpcomingOpponentLookupService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PlayerInsightsService {

  private static final int DEFAULT_RECENT_GAME_WINDOW = 5;

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final PlayerRefreshService playerRefreshService;
  private final UpcomingOpponentLookupService upcomingOpponentLookupService;
  private final TeamRefreshService teamRefreshService;
  private final Clock clock = Clock.systemUTC();

  public PlayerInsightsService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      PlayerRefreshService playerRefreshService,
      UpcomingOpponentLookupService upcomingOpponentLookupService,
      TeamRefreshService teamRefreshService) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.playerRefreshService = playerRefreshService;
    this.upcomingOpponentLookupService = upcomingOpponentLookupService;
    this.teamRefreshService = teamRefreshService;
  }

  public PlayerInsightsResponse getInsightsForAthleteId(String athleteId) {
    Player player =
        playerRepository
            .findByEspnAthleteId(athleteId)
            .orElseThrow(
                () -> new PlayerNotFoundException("No stored player found for ESPN athlete " + athleteId));

    if (needsPlayerMetadataRefresh(player)) {
      playerRefreshService.requestRefreshIfNeeded(player.getEspnAthleteId());
    }

    teamRefreshService.requestRefreshIfStale(player.getTeamId());

    List<PlayerGameStat> stats = playerGameStatRepository.findAllByPlayer_IdOrderByGameDateDesc(player.getId());
    List<PlayerGameStat> recentStats = stats.stream().limit(DEFAULT_RECENT_GAME_WINDOW).toList();

    PlayerStatInsightSummary overallSummary = summarize(stats);
    PlayerStatInsightSummary lastFiveSummary = summarize(recentStats);
    PlayerStatInsightSummary lastThreeSummary = summarize(stats.stream().limit(3).toList());
    UpcomingOpponentInsightResponse upcomingOpponent = buildUpcomingOpponentInsight(player, stats);

    List<PlayerInsightSplitResponse> homeAwaySplits =
        buildHomeAwaySplits(stats).stream().map(PlayerInsightSplitResponse::from).toList();
    List<PlayerInsightSplitResponse> opponentSplits =
        buildOpponentSplits(stats).stream().map(PlayerInsightSplitResponse::from).toList();

    return PlayerInsightsResponse.of(
        PlayerResponse.from(player),
        stats.size(),
        DEFAULT_RECENT_GAME_WINDOW,
        overallSummary,
        lastFiveSummary,
        lastThreeSummary,
        upcomingOpponent,
        homeAwaySplits,
        opponentSplits,
        recentStats.stream().map(PlayerGameStatResponse::from).toList(),
        clock.instant());
  }

  private UpcomingOpponentInsightResponse buildUpcomingOpponentInsight(
      Player player, List<PlayerGameStat> stats) {
    return upcomingOpponentLookupService
        .findUpcomingOpponent(player.getTeamId())
        .map(opponent -> toUpcomingOpponentInsight(opponent, stats))
        .orElse(null);
  }

  private UpcomingOpponentInsightResponse toUpcomingOpponentInsight(
      UpcomingOpponent opponent, List<PlayerGameStat> stats) {
    List<PlayerGameStat> matchingStats =
        stats.stream().filter(stat -> matchesOpponent(stat, opponent)).toList();
    List<PlayerGameStat> lastThreeMatchups = matchingStats.stream().limit(3).toList();

    return new UpcomingOpponentInsightResponse(
        opponent.opponentTeamId(),
        opponent.opponentName(),
        opponent.gameDate(),
        summarize(lastThreeMatchups),
        summarize(matchingStats));
  }

  private List<PlayerInsightSplit> buildHomeAwaySplits(List<PlayerGameStat> stats) {
    Map<String, List<PlayerGameStat>> grouped =
        stats.stream()
            .filter(stat -> stat.getHomeAway() != null && !stat.getHomeAway().isBlank())
            .collect(
                Collectors.groupingBy(
                    stat -> stat.getHomeAway().trim().toLowerCase(),
                    LinkedHashMap::new,
                    Collectors.toList()));

    List<PlayerInsightSplit> splits = new ArrayList<>();
    for (Map.Entry<String, List<PlayerGameStat>> entry : grouped.entrySet()) {
      splits.add(new PlayerInsightSplit("homeAway", entry.getKey(), summarize(entry.getValue())));
    }
    return splits;
  }

  private List<PlayerInsightSplit> buildOpponentSplits(List<PlayerGameStat> stats) {
    Map<String, List<PlayerGameStat>> grouped =
        stats.stream()
            .filter(stat -> stat.getOpponentName() != null && !stat.getOpponentName().isBlank())
            .collect(
                Collectors.groupingBy(
                    stat -> stat.getOpponentName().trim(),
                    LinkedHashMap::new,
                    Collectors.toList()));

    return grouped.entrySet().stream()
        .sorted(
            Comparator
                .comparingInt((Map.Entry<String, List<PlayerGameStat>> entry) -> entry.getValue().size())
                .reversed()
                .thenComparing(
                    entry ->
                        entry.getValue().stream()
                            .map(PlayerGameStat::getGameDate)
                            .filter(java.util.Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null),
                    Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(3)
        .map(entry -> new PlayerInsightSplit("opponent", entry.getKey(), summarize(entry.getValue())))
        .toList();
  }

  private boolean matchesOpponent(PlayerGameStat stat, UpcomingOpponent opponent) {
    if (opponent.opponentTeamId() != null
        && !opponent.opponentTeamId().isBlank()
        && stat.getOpponentTeamId() != null
        && opponent.opponentTeamId().equals(stat.getOpponentTeamId())) {
      return true;
    }

    return normalize(opponent.opponentName()).equals(normalize(stat.getOpponentName()));
  }

  private PlayerStatInsightSummary summarize(List<PlayerGameStat> stats) {
    int games = stats.size();
    return new PlayerStatInsightSummary(
        games,
        total(stats, PlayerGameStat::getPassingYards),
        average(stats, PlayerGameStat::getPassingYards),
        total(stats, PlayerGameStat::getRushingYards),
        average(stats, PlayerGameStat::getRushingYards),
        total(stats, PlayerGameStat::getReceivingYards),
        average(stats, PlayerGameStat::getReceivingYards),
        total(stats, PlayerGameStat::getTotalYards),
        average(stats, PlayerGameStat::getTotalYards),
        total(stats, PlayerGameStat::getPassingTouchdowns),
        average(stats, PlayerGameStat::getPassingTouchdowns),
        total(stats, PlayerGameStat::getRushingTouchdowns),
        average(stats, PlayerGameStat::getRushingTouchdowns),
        total(stats, PlayerGameStat::getReceivingTouchdowns),
        average(stats, PlayerGameStat::getReceivingTouchdowns),
        total(stats, PlayerGameStat::getTouchdowns),
        average(stats, PlayerGameStat::getTouchdowns),
        total(stats, PlayerGameStat::getTotalTouchdowns),
        average(stats, PlayerGameStat::getTotalTouchdowns),
        total(stats, PlayerGameStat::getInterceptions),
        average(stats, PlayerGameStat::getInterceptions),
        total(stats, PlayerGameStat::getFumbles),
        average(stats, PlayerGameStat::getFumbles),
        total(stats, PlayerGameStat::getFumblesLost),
        average(stats, PlayerGameStat::getFumblesLost),
        total(stats, PlayerGameStat::getTurnovers),
        average(stats, PlayerGameStat::getTurnovers),
        total(stats, PlayerGameStat::getSnapCount),
        average(stats, PlayerGameStat::getSnapCount),
        total(stats, PlayerGameStat::getCarries),
        average(stats, PlayerGameStat::getCarries),
        total(stats, PlayerGameStat::getReceivingTargets),
        average(stats, PlayerGameStat::getReceivingTargets),
        total(stats, PlayerGameStat::getReceptions),
        average(stats, PlayerGameStat::getReceptions),
        total(stats, PlayerGameStat::getDrops),
        average(stats, PlayerGameStat::getDrops));
  }

  private int total(List<PlayerGameStat> stats, Function<PlayerGameStat, Integer> accessor) {
    int sum = 0;
    for (PlayerGameStat stat : stats) {
      Integer value = accessor.apply(stat);
      if (value != null) {
        sum += value;
      }
    }
    return sum;
  }

  private double average(List<PlayerGameStat> stats, Function<PlayerGameStat, Integer> accessor) {
    if (stats.isEmpty()) {
      return 0.0d;
    }
    return (double) total(stats, accessor) / stats.size();
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }

    return value.trim().toLowerCase();
  }

  private boolean needsPlayerMetadataRefresh(Player player) {
    if (player == null || player.getEspnAthleteId() == null || player.getEspnAthleteId().isBlank()) {
      return false;
    }

    return player.getPosition() == null
        || player.getPosition().isBlank()
        || player.getTeamName() == null
        || player.getTeamName().isBlank()
        || player.getTeamId() == null
        || player.getTeamId().isBlank();
  }
}
