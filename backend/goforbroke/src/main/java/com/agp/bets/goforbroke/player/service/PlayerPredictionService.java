package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PlayerPredictionService {

  private static final int RECENT_GAME_WINDOW = 5;
  private static final Duration CACHE_TTL = Duration.ofMinutes(20);
  private static final int CACHE_MAX_ENTRIES = 100;

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final TeamRepository teamRepository;
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository;
  private final Clock clock = Clock.systemUTC();
  private final Map<String, CachedPrediction> predictionCache =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPrediction> eldest) {
              return size() > CACHE_MAX_ENTRIES;
            }
          });

  public PlayerPredictionService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      TeamRepository teamRepository,
      TeamDefenseGameStatRepository teamDefenseGameStatRepository) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.teamRepository = teamRepository;
    this.teamDefenseGameStatRepository = teamDefenseGameStatRepository;
  }

  public PlayerPredictionResponse getPredictionForAthleteId(String athleteId) {
    CachedPrediction cachedPrediction = predictionCache.get(athleteId);
    if (cachedPrediction != null && !cachedPrediction.isExpired(clock.instant())) {
      return cachedPrediction.response();
    }

    Player player =
        playerRepository
            .findByEspnAthleteId(athleteId)
            .orElseThrow(
                () -> new PlayerNotFoundException("No stored player found for ESPN athlete " + athleteId));

    List<PlayerGameStat> stats = playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(player.getId());
    List<PlayerGameStat> recentStats = stats.stream().limit(RECENT_GAME_WINDOW).toList();
    String position = normalizePosition(player.getPosition());

    Team currentTeam =
        player.getTeamId() == null ? null : teamRepository.findByEspnTeamId(player.getTeamId()).orElse(null);

    List<TeamDefenseGameStat> opponentDefenseHistory =
        currentTeam == null || currentTeam.getUpcomingOpponentTeamId() == null
            ? List.of()
            : teamDefenseGameStatRepository.findAllByTeam_IdOrderByGameDateDesc(
                teamRepository.findByEspnTeamId(currentTeam.getUpcomingOpponentTeamId()).map(Team::getId).orElse(-1L));

    List<String> metrics = metricsForPosition(position);
    List<PlayerPredictionResponse.PredictionSummaryResponse> projections =
        metrics.stream()
            .map(metric -> buildProjection(metric, recentStats, stats, position, opponentDefenseHistory))
            .toList();

    PlayerPredictionResponse response =
        new PlayerPredictionResponse(
        player.getEspnAthleteId(),
        player.getDisplayName(),
        player.getPosition(),
        projections,
        confidenceScore(stats, recentStats),
        clock.instant());

    predictionCache.put(athleteId, new CachedPrediction(response, clock.instant().plus(CACHE_TTL)));
    return response;
  }

  private PlayerPredictionResponse.PredictionSummaryResponse buildProjection(
      String metric,
      List<PlayerGameStat> recentStats,
      List<PlayerGameStat> allStats,
      String position,
      List<TeamDefenseGameStat> opponentDefenseHistory) {
    List<Double> recentValues = metricValues(recentStats, metric);
    List<Double> allValues = metricValues(allStats, metric);

    double recentAverage = recentValues.isEmpty() ? 0.0d : recentValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double seasonAverage = allValues.isEmpty() ? 0.0d : allValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double blendedMean = (0.65d * recentAverage) + (0.35d * seasonAverage);
    double opponentAdjustment = opponentAdjustment(metric, opponentDefenseHistory);
    double projectedMean = Math.max(0.0d, blendedMean + opponentAdjustment);

    double stdDev = standardDeviation(allValues);
    int sampleSize = Math.max(1, allValues.size());
    double margin = 1.28d * (stdDev / Math.sqrt(sampleSize));
    double lower = Math.max(0.0d, projectedMean - margin);
    double upper = projectedMean + margin;

    return new PlayerPredictionResponse.PredictionSummaryResponse(
        metric,
        projectedMean,
        lower,
        upper,
        sampleSize,
        opponentAdjustment,
        notesForMetric(position, metric));
  }

  private List<Double> metricValues(List<PlayerGameStat> stats, String metric) {
    List<Double> values = new ArrayList<>();
    for (PlayerGameStat stat : stats) {
      Integer value = switch (metric) {
        case "passingYards" -> stat.getPassingYards();
        case "rushingYards" -> stat.getRushingYards();
        case "receivingYards" -> stat.getReceivingYards();
        case "receptions" -> stat.getReceptions();
        case "touchdowns" -> stat.getTotalTouchdowns() != null ? stat.getTotalTouchdowns() : stat.getTouchdowns();
        case "passingTouchdowns" -> stat.getPassingTouchdowns();
        case "rushingTouchdowns" -> stat.getRushingTouchdowns();
        case "turnovers" -> stat.getTurnovers();
        default -> null;
      };
      if (value != null) {
        values.add(value.doubleValue());
      }
    }
    return values;
  }

  private double opponentAdjustment(String metric, List<TeamDefenseGameStat> defenseHistory) {
    if (defenseHistory.isEmpty()) {
      return 0.0d;
    }

    List<Integer> values =
        defenseHistory.stream()
            .map(
                stat ->
                switch (metric) {
                      case "passingYards" -> stat.getPassingYardsAllowed();
                      case "rushingYards" -> stat.getRushingYardsAllowed();
                      case "receivingYards" -> stat.getReceivingYardsAllowed();
                      case "receptions" -> stat.getReceivingYardsAllowed();
                      case "touchdowns" -> stat.getPointsAllowed();
                      default -> null;
                    })
            .filter(java.util.Objects::nonNull)
            .toList();

    if (values.isEmpty()) {
      return 0.0d;
    }

    double opponentAverage = values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0d);
    return switch (metric) {
      case "passingYards" -> (opponentAverage - 225.0d) * 0.10d;
      case "rushingYards" -> (opponentAverage - 110.0d) * 0.08d;
      case "receivingYards" -> (opponentAverage - 125.0d) * 0.08d;
      case "receptions" -> (opponentAverage - 125.0d) * 0.02d;
      case "touchdowns" -> (opponentAverage - 21.0d) * 0.03d;
      default -> 0.0d;
    };
  }

  private double confidenceScore(List<PlayerGameStat> allStats, List<PlayerGameStat> recentStats) {
    if (allStats.isEmpty()) {
      return 0.15d;
    }

    double sampleComponent = Math.min(1.0d, allStats.size() / 10.0d);
    double recencyComponent = Math.min(1.0d, recentStats.size() / 5.0d);
    double freshnessComponent =
        allStats.stream().map(PlayerGameStat::getGameDate).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder())
            .map(date -> 1.0d - Math.min(1.0d, ChronoUnit.DAYS.between(date, LocalDate.now(clock)) / 365.0d))
            .orElse(0.5d);
    return roundTwoDecimals((sampleComponent * 0.45d) + (recencyComponent * 0.30d) + (freshnessComponent * 0.25d));
  }

  private List<String> notesForMetric(String position, String metric) {
    if (position == null) {
      return List.of("Position unknown, using blended historical form.");
    }

    return switch (position) {
      case "QB" -> metric.equals("passingYards") || metric.equals("rushingYards") || metric.equals("passingTouchdowns") || metric.equals("turnovers")
          ? List.of("QB projection uses passing, rushing, and turnover form.")
          : List.of("QB projections exclude receiving metrics.");
      case "RB" -> metric.equals("rushingYards") || metric.equals("receivingYards")
          ? List.of("RB projection blends workload and yardage.")
          : List.of("RB scoring is based on recent touchdown rates.");
      case "WR", "TE" -> metric.equals("receivingYards") || metric.equals("receptions")
          ? List.of("Receiver projection blends targets, receptions, and yardage.")
          : List.of("Receiver scoring is based on recent touchdown rates.");
      default -> List.of("Projection uses available historical game logs.");
    };
  }

  private double standardDeviation(List<Double> values) {
    if (values.size() < 2) {
      return 0.0d;
    }

    double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double variance =
        values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum() / (values.size() - 1);
    return Math.sqrt(variance);
  }

  private double roundTwoDecimals(double value) {
    return Math.round(value * 100.0d) / 100.0d;
  }

  private List<String> metricsForPosition(String position) {
    return switch (position) {
      case "QB" -> List.of("passingYards", "rushingYards", "passingTouchdowns", "turnovers");
      case "RB" -> List.of("rushingYards", "receivingYards", "receptions", "touchdowns");
      case "WR", "TE", "FB" -> List.of("receivingYards", "receptions", "touchdowns");
      default -> List.of("rushingYards", "receivingYards", "receptions", "touchdowns");
    };
  }

  private String normalizePosition(String position) {
    return position == null ? null : position.trim().toUpperCase();
  }

  private record CachedPrediction(PlayerPredictionResponse response, Instant expiresAt) {
    private boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }
}
