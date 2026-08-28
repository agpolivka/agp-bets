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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces a per-stat game projection for a player - e.g. "127 rushing yards" - which the
 * frontend shows as the headline number a user would act on for a bet.
 *
 * <p><b>This is a hand-tuned heuristic (v1), not a trained model.</b> Every weight and coefficient
 * in this file was picked to be directionally reasonable, not fit against real outcomes. Treat
 * them as a starting point to calibrate once there's enough completed-game history to backtest
 * against (compare {@code projectedMean} to what actually happened that week), not as validated
 * parameters. The opponent-adjustment baseline itself (see {@link #leagueDefenseAverages}) IS
 * computed from this database's own stored team data, not hardcoded - but the coefficients that
 * decide how much a below/above-average defense should move the projection still are. The single
 * biggest lever for prediction quality right now is data completeness (full game history,
 * correct opponent context) more than these constants - see {@code WORKPLAN.md} for what's still
 * missing on that front.
 *
 * <p>Algorithm per stat (see {@link #buildProjection}):
 *
 * <ol>
 *   <li>Blend the player's last {@link #RECENT_GAME_WINDOW} games with their full stored history
 *       - recent form should matter more than a season-long average, but 5 games alone is noisy,
 *       so it's blended rather than used on its own.
 *   <li>Add a linear adjustment based on how the player's upcoming opponent has performed on
 *       defense this season (see {@link #opponentAdjustment}).
 *   <li>Add a role/opportunity adjustment for receivers based on recent target share (see {@link
 *       #targetShareAdjustment}) and a workload adjustment for RB/WR/TE based on recent offensive
 *       snap share against a real position-specific baseline (see {@link #usageAdjustment}).
 *   <li>Scale the result by the player's weekly game-status participation likelihood (see {@link
 *       #injuryStatusMultiplier}) - multiplicative, not additive, since availability affects
 *       whether a player plays at all, not just how well.
 *   <li>Floor the result at zero - a projection can't be negative.
 * </ol>
 *
 * <p>The response also carries a {@code lowerBound}/{@code upperBound} range and a
 * {@code confidenceScore} (see {@link #buildProjection} and {@link #confidenceScore}). As of the
 * 2026-08 UI pass, the player page intentionally does not display either - the product decision
 * was to show one decisive number instead of hedging it with a range or a confidence badge. They
 * stay in the API response because a future feature (e.g. surfacing "sleeper" picks where our own
 * modeled uncertainty diverges from a market line) would need exactly this kind of
 * uncertainty/variance signal - if nothing ends up consuming them, they're a reasonable thing to
 * prune later.
 */
@Service
@Transactional(readOnly = true)
public class PlayerPredictionService {

  // How many of the player's most recent games count as "recent form" in the blend below.
  // Package-private (not private): PredictionBacktestService reuses this and the other tuning
  // constants/helpers below so the backtest scores against the exact same math the live path uses,
  // instead of risking a second, drifting copy of the algorithm.
  static final int RECENT_GAME_WINDOW = 5;
  // Roughly one season (18 regular-season weeks + a playoff buffer) of an opponent's most recent
  // defensive games. Without this, opponentAdjustment blends every stored season together, so a
  // team's 2020 defense weighs exactly as much as their current one - capping to a recent window
  // keeps it reflecting the roster/scheme actually playing now.
  static final int DEFENSE_HISTORY_GAME_WINDOW = 20;
  // Same idea for the league-wide baseline, expressed as a date cutoff instead of a per-team game
  // count since it's a single query across every team, not ordered per-team.
  static final Duration LEAGUE_AVERAGES_LOOKBACK = Duration.ofDays(210);
  // Predictions do several repository reads per request; cache briefly so repeat page loads and
  // the frontend's retry-on-load-failure loop don't recompute the same projection every time.
  private static final Duration CACHE_TTL = Duration.ofMinutes(20);
  // A player whose backfill hasn't finished yet (or who is genuinely brand new) has very few
  // stored games, so the projection built from them is close to meaningless - e.g. one game of
  // passing yards standing in for a season average. Cache that result much more briefly than a
  // normal projection so the first request right after a new player loads doesn't lock in a
  // near-zero number for the full CACHE_TTL while their history is still backfilling in the
  // background.
  static final int THIN_SAMPLE_GAME_THRESHOLD = 3;
  private static final Duration THIN_SAMPLE_CACHE_TTL = Duration.ofSeconds(15);
  private static final int CACHE_MAX_ENTRIES = 100;
  // League-wide defense averages change slowly (they're a season-long aggregate across every
  // stored team), so this is cached separately and longer than a single player's prediction.
  private static final Duration LEAGUE_AVERAGES_TTL = Duration.ofHours(6);
  // Roughly the modern-NFL average Vegas game total. Used as the baseline gameConditionsAdjustment
  // compares a real total_line against - when no real total is known (the live path doesn't have
  // one yet, see gameConditionsAdjustment's doc), the "delta" against this baseline is zero, so the
  // adjustment cleanly becomes a no-op rather than needing a separate null-check branch.
  private static final double DEFAULT_GAME_TOTAL_LINE = 44.5d;
  // Wind below this (mph) isn't treated as materially affecting passing - roughly where
  // broadcasters/bettors start actually calling out wind as a real factor.
  private static final double WIND_EFFECT_THRESHOLD_MPH = 10.0d;
  // PFR-reported ballpark for league-wide yards-after-contact per carry (roughly where recent
  // seasons land); a hand-picked estimate, not computed from this database's own stored data like
  // leagueDefenseAverages is - flagged the same way the class doc flags every other constant here.
  private static final double LEAGUE_AVERAGE_AFTER_CONTACT_YARDS_PER_CARRY = 2.6d;
  // Small on purpose (see gameConditionsAdjustment/opponentAdjustment for the same pattern) - this
  // is a quality tilt on top of the yardage the blend already projects, not a second independent
  // predictor of rushing volume.
  private static final double AFTER_CONTACT_QUALITY_COEFFICIENT = 0.35d;
  // Real-calibrated (2026-08-28), the first genuine temporal train/test validation any coefficient
  // in this file has had - every prior calibration (including the ones below this block, before
  // today) was fit and evaluated on the exact same full historical dataset, never held out. Method:
  // fit on season <= cutoff, evaluate held-out MAE on season > cutoff, repeated across 6
  // independent cutoffs (2018-2023) to confirm the finding replicates rather than trusting one
  // split. passingYards/touchdowns/passingTouchdowns/receivingYards all showed real, consistently
  // *improving* held-out MAE across every single cutoff (passingYards +1.27% to +1.74%; touchdowns
  // +2.20% to +4.10%; passingTouchdowns +0.18% to +0.38%; receivingYards +0.96% to +1.81%) - final
  // values below are fit on the full 2014-2025 dataset for maximum precision, now that the method
  // itself is validated. targetShareAdjustment (WOPR) was ALSO tested this way and failed badly -
  // held-out MAE got worse in every replication despite looking "significant" in-sample (fitted
  // fraction ~9x, which would have undone the 2026-08-20 correction) - left untouched, a real
  // caught-before-shipping near miss, not applied. rushingYards/receptions showed no real signal
  // either way - also left untouched. See WORKPLAN.md for the full numbers and methodology.
  //
  // CPOE (completion_percentage_above_expectation) is already expressed relative to a league-wide
  // expectation baseline by nflverse's own model - unlike the coefficients above, no separate
  // baseline lookup or subtraction is needed here, just a scale from "percentage points" to yards/
  // touchdowns.
  private static final double CPOE_PASSING_YARDS_COEFFICIENT = 0.0273d; // was 3.0d; fraction=0.0091 (p=0.93, not significant alone - but part of a jointly-validated refit, see block above), full n=4,959
  private static final double CPOE_PASSING_TOUCHDOWNS_COEFFICIENT = 0.0054d; // was 0.02d; fraction=0.2695 (p=0.166), full n=4,959
  // Same shape as AFTER_CONTACT_QUALITY_COEFFICIENT - a quality tilt on receivingYards, not a
  // second independent predictor of receiving volume. Sign flip (2026-08-28): consistently
  // negative across all 6 replication cutoffs (-0.19 to -0.61), not just the full-dataset fit -
  // real evidence a hot recent YAC-over-expectation stretch predicts *regression toward the mean*
  // for future receivingYards, not more of the same. fraction=-0.1296 (p=0.056), full n=27,382.
  private static final double RECEIVING_QUALITY_COEFFICIENT = -0.0648d; // was 0.5d
  // More pressure allowed by a defense suppresses the opposing QB - negative on purpose, unlike
  // the yardage-allowed coefficients above (which are positive because more yards/points allowed
  // means an easier matchup).
  private static final double PRESSURE_PASSING_YARDS_COEFFICIENT = -1.3408d; // was -2.0d; fraction=0.6704 (p=0.094), full n=4,959
  private static final double PRESSURE_PASSING_TOUCHDOWNS_COEFFICIENT = -0.0235d; // was -0.02d; fraction=1.1726 (p=0.055), full n=4,959
  // missedTacklePct deltas are small fractions (e.g. 0.03 = 3 points worse than average), so this
  // coefficient is scaled up accordingly rather than the ~0.08-0.10 used for whole-yard deltas
  // above - a defense missing 3 more tackles per 100 than average allows roughly a few extra
  // yards/game on this estimate, not a large swing. rushingYards showed no real train/test signal
  // (2026-08-28) - left untouched here.
  private static final double MISSED_TACKLE_YARDS_COEFFICIENT = 100.0d;
  // receivingYards's own copy (2026-08-28) - was sharing MISSED_TACKLE_YARDS_COEFFICIENT with
  // rushingYards above, but the two showed different real train/test behavior (rushingYards: no
  // signal; receivingYards: real, replicated need to scale down - fraction=0.2432, p=0.0099, full
  // n=27,382, fit on the opponentAdjustment composite MINUS the separately-and-already-validated
  // zoneCoverageRateDelta contribution below, so this correction doesn't double-count that work).
  private static final double MISSED_TACKLE_RECEIVING_YARDS_COEFFICIENT = 24.32d; // was sharing 100.0d
  // Base opponentAdjustment coefficients (yardage/points-allowed vs. league average), real-
  // calibrated with the same 2026-08-28 train/test methodology (see the block above) - extracted
  // from inline literals in opponentAdjustment's switch into named constants now that they're
  // genuinely calibrated, not just hand-picked. rushingYards/receptions showed no real train/test
  // signal and keep their original inline hand-picked values (0.035d/0.0015d) unchanged.
  private static final double PASSING_YARDS_OPPONENT_COEFFICIENT = 0.0469d; // was 0.07d; fraction=0.6704 (p=0.094), full n=4,959
  private static final double TOUCHDOWNS_OPPONENT_COEFFICIENT = 0.0044d; // was 0.005d; fraction=0.8794 (p<0.0001), full n=27,382
  private static final double RECEIVING_YARDS_OPPONENT_COEFFICIENT = 0.00365d; // was 0.015d; fraction=0.2432 (p=0.0099), full n=27,382 (fit excluding the zoneCoverageRateDelta contribution, see MISSED_TACKLE_RECEIVING_YARDS_COEFFICIENT's doc)
  // Vegas game-total-line coefficients for touchdown-metrics, real-calibrated 2026-08-28 - split
  // out of a single shared 0.03d constant (touchdowns/passingTouchdowns/rushingTouchdowns all used
  // the same value) once real train/test evidence showed touchdowns and passingTouchdowns actually
  // want meaningfully different magnitudes. rushingTouchdowns isn't a live-predicted metric in this
  // app (see metricsForPosition) so it isn't independently tested - grouped with touchdowns' value
  // in gameConditionsAdjustment's switch as the closer analogue (both non-passing scoring).
  private static final double TOUCHDOWNS_TOTAL_LINE_COEFFICIENT = 0.0045d; // was sharing 0.03d; fraction=0.1512 (p<0.0001), full n=27,382
  private static final double PASSING_TOUCHDOWNS_TOTAL_LINE_COEFFICIENT = 0.0213d; // was sharing 0.03d; fraction=0.7086 (p<0.0001), full n=4,959
  // Real-calibrated (2026-08-26), same regression-against-the-raw-predictor method as SNAP_PCT_*
  // below (not the current-coefficient-as-input method opponentAdjustment's other terms use, since
  // no coefficient existed yet): fit actual ~ recentAverage + seasonAverage + opponentAdjustment +
  // conditionsAdjustment + rushingQualityAdjustment + advancedMetricAdjustment +
  // targetShareAdjustment + zoneCoverageRateDelta per metric, against every backtestable game with
  // real trailing 2023-2025 participation history - controlling for opponentAdjustment (which
  // already includes the PFR-sourced pressures/missedTacklePct signals), so a significant
  // coefficient here is evidence of something genuinely incremental, not a repeat of an
  // already-wired effect. receivingYards: coefficient 8.4465, p=0.019, n=14,473. receptions:
  // coefficient 1.2441, p=2.94e-06 (highly significant), n=14,473. Direction is football-sane: more
  // zone coverage than league average associates with more receptions and receiving yards allowed
  // (zone tends to concede more underneath completions than man). avgPassRushersDelta and
  // avgDefendersInBoxDelta were also tested this same way (passingYards/passingTouchdowns and
  // rushingYards respectively) and did NOT hold up (p=0.10-0.97) - real, checked negative results,
  // not wired in. See WORKPLAN.md for the full writeup, including the same-game correlation check
  // that originally found this signal (a materially different, weaker claim than what's confirmed
  // here).
  private static final double ZONE_COVERAGE_RECEIVING_YARDS_COEFFICIENT = 8.4465d;
  private static final double ZONE_COVERAGE_RECEPTIONS_COEFFICIENT = 1.2441d;
  // Rough, hand-picked participation-likelihood estimates for injuryStatusMultiplier - see that
  // method's doc for why these are multiplicative rather than additive, and why they're not
  // fit against how often a "Questionable"/"Doubtful" player actually ends up suiting up.
  private static final double QUESTIONABLE_PARTICIPATION_MULTIPLIER = 0.85d;
  private static final double DOUBTFUL_PARTICIPATION_MULTIPLIER = 0.25d;
  // Real computed average (not a guessed ballpark like AFTER_CONTACT's) - avg wopr across every
  // stored WR/TE/RB player-game in the 2025 season with target_share > 0 (4,451 rows), queried
  // directly against this database on 2026-08-20 right after backfilling the column. Unlike
  // leagueDefenseAverages, this isn't recomputed live/cached - a one-time real query, same
  // treatment as the 8.5 pressures/9% missed-tackle Phase 4 fallbacks.
  private static final double LEAGUE_AVERAGE_WOPR = 0.28d;
  // Real-calibrated (2026-08-20, later), same regression method and multiply-by-the-fitted-
  // coefficient direction as opponentAdjustment's doc: fit against every backtestable game using
  // the original 40.0/3.0 values as the regression input, this term came back overscaled, same as
  // opponentAdjustment/conditionsAdjustment were - only 11.4% of its magnitude for receivingYards
  // (coefficient 0.1145, p=0.015, n=27,380) and 8.2% for receptions (coefficient 0.082, p=0.065,
  // n=27,380) is actually supported by real data. (An earlier version of this comment/change
  // mistakenly divided by the fitted coefficient instead of multiplying, concluding the opposite -
  // "underscaled by ~9x" - and shipped a doubled coefficient; that was caught immediately because
  // the live outcome backtest got measurably worse, not better, which is exactly the kind of thing
  // verifying against real data after a change is supposed to catch.) Checked collinearity (WOPR is
  // volume-correlated with blendedMean) via a variance inflation factor check regardless: VIF ~1.15
  // for both metrics, well below the conventional ~5 concern threshold, so the finding itself isn't
  // a correlation artifact - it's real, same as opponentAdjustment's.
  private static final double WOPR_RECEIVING_YARDS_COEFFICIENT = 4.58d;
  private static final double WOPR_RECEPTIONS_COEFFICIENT = 0.25d;
  // Real position-specific averages (not a single flat guess) - queried directly against this
  // database's own stored 2025-season offense_snap_pct data on 2026-08-20/22: RB 0.3551 (n=1,646),
  // WR 0.5125 (n=2,627), TE 0.5167 (n=1,343). A workhorse RB and a rotational WR have very
  // different "normal" snap shares, which is exactly why offenseSnapPct was left unwired at first
  // (see its field doc on PlayerGameStat) until a real, position-aware baseline existed.
  private static final Map<String, Double> LEAGUE_AVERAGE_SNAP_PCT_BY_POSITION =
      Map.of("RB", 0.3551d, "WR", 0.5125d, "TE", 0.5167d);
  // Real-calibrated (2026-08-20/22): fit rushingYards ~ recentRush + seasonRush + snapPctDelta for
  // RBs (coefficient 37.5455, p<0.0001, n=1,493) and receivingYards ~ recentRecv + seasonRecv +
  // snapPctDelta for WR/TE (coefficient 18.6292, p<0.0001, n=3,594) - both real, statistically
  // robust, and independent of what recent/season yardage averages already capture (e.g. a RB
  // coming off an injury has a small recent-yardage sample but a real snap-share increase already
  // signals more volume is coming). Unlike targetShareAdjustment's calibration, these come from a
  // regression against the RAW predictor directly (not a rescale of an already-scaled term), so
  // they're used as-is here, not multiplied against a prior guess.
  private static final double SNAP_PCT_RUSHING_YARDS_COEFFICIENT = 37.5455d;
  private static final double SNAP_PCT_RECEIVING_YARDS_COEFFICIENT = 18.6292d;

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final TeamRepository teamRepository;
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository;
  private final WeatherForecastClient weatherForecastClient;
  private final Clock clock = Clock.systemUTC();
  // Simple TTL + LRU cache, keyed by ESPN athlete id. LinkedHashMap in access-order mode with
  // removeEldestEntry gives LRU eviction for free once the cache exceeds CACHE_MAX_ENTRIES.
  private final Map<String, CachedPrediction> predictionCache =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPrediction> eldest) {
              return size() > CACHE_MAX_ENTRIES;
            }
          });
  private volatile CachedLeagueAverages cachedLeagueAverages;

  public PlayerPredictionService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      TeamRepository teamRepository,
      TeamDefenseGameStatRepository teamDefenseGameStatRepository,
      WeatherForecastClient weatherForecastClient) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.teamRepository = teamRepository;
    this.teamDefenseGameStatRepository = teamDefenseGameStatRepository;
    this.weatherForecastClient = weatherForecastClient;
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

    // No upcoming opponent on file (e.g. stale/unsynced team row, or offseason) -> opponent
    // adjustment below just falls back to 0 rather than failing the whole projection.
    List<TeamDefenseGameStat> opponentDefenseHistory =
        currentTeam == null || currentTeam.getUpcomingOpponentTeamId() == null
            ? List.of()
            : teamDefenseGameStatRepository
                .findAllByTeam_IdOrderByGameDateDesc(
                    teamRepository.findByEspnTeamId(currentTeam.getUpcomingOpponentTeamId()).map(Team::getId).orElse(-1L))
                .stream()
                .limit(DEFENSE_HISTORY_GAME_WINDOW)
                .toList();

    LeagueDefenseAverages leagueAverages = leagueDefenseAverages();
    GameConditions conditions = resolveLiveGameConditions(currentTeam);
    List<String> metrics = metricsForPosition(position);
    List<PlayerPredictionResponse.PredictionSummaryResponse> projections =
        metrics.stream()
            .map(
                metric ->
                    buildProjection(
                        metric,
                        recentStats,
                        stats,
                        position,
                        opponentDefenseHistory,
                        leagueAverages,
                        conditions,
                        player.getGameStatus()))
            .toList();

    PlayerPredictionResponse response =
        new PlayerPredictionResponse(
        player.getEspnAthleteId(),
        player.getDisplayName(),
        player.getPosition(),
        projections,
        confidenceScore(stats, recentStats),
        clock.instant(),
        player.getGameStatus(),
        player.getGameStatusDetail());

    Duration ttl = stats.size() < THIN_SAMPLE_GAME_THRESHOLD ? THIN_SAMPLE_CACHE_TTL : CACHE_TTL;
    predictionCache.put(athleteId, new CachedPrediction(response, clock.instant().plus(ttl)));
    return response;
  }

  /**
   * Resolves what {@link #gameConditionsAdjustment} can actually know about a player's upcoming
   * game: the host stadium's roof (from {@code Team.venueIndoor}, static/known in advance) and, for
   * outdoor games within the forecast window, a live wind forecast. Falls back to {@link
   * GameConditions#UNKNOWN} whenever any piece is missing (stale/unsynced team, no upcoming game
   * on file, host team not resolvable) rather than guessing - an unknown condition is a no-op
   * adjustment, not a wrong one.
   */
  private GameConditions resolveLiveGameConditions(Team currentTeam) {
    if (currentTeam == null
        || currentTeam.getUpcomingGameIsHome() == null
        || currentTeam.getUpcomingGameTime() == null) {
      return GameConditions.UNKNOWN;
    }

    Team hostTeam =
        Boolean.TRUE.equals(currentTeam.getUpcomingGameIsHome())
            ? currentTeam
            : (currentTeam.getUpcomingOpponentTeamId() == null
                ? null
                : teamRepository.findByEspnTeamId(currentTeam.getUpcomingOpponentTeamId()).orElse(null));
    if (hostTeam == null) {
      return GameConditions.UNKNOWN;
    }

    // ESPN's venueIndoor doesn't distinguish "dome" from "retractable, currently closed" - treating
    // anything not explicitly indoor as outdoors is an approximation, same spirit as this file's
    // other hand-picked constants.
    String roof = Boolean.TRUE.equals(hostTeam.getVenueIndoor()) ? "dome" : "outdoors";
    GameConditions baseConditions = new GameConditions(roof, null, null);
    if (!baseConditions.isOutdoors() || hostTeam.getAbbreviation() == null) {
      return baseConditions;
    }

    WeatherForecastClient.Forecast forecast =
        weatherForecastClient.forecastFor(hostTeam.getAbbreviation(), currentTeam.getUpcomingGameTime());
    return forecast == null ? baseConditions : new GameConditions(roof, forecast.windMph(), null);
  }

  /**
   * Core projection for one stat (e.g. "rushingYards"). See the class doc for the algorithm in
   * prose; {@code blendedMean} is the 65/35 recent/season split, {@code opponentAdjustment}
   * nudges it toward how the upcoming opponent's defense has performed. The 0.65/0.35 split is
   * arbitrary (weights recent form higher without letting a small 5-game sample dominate) and
   * not derived from any backtest.
   *
   * <p>{@code gameStatus} (see {@link #injuryStatusMultiplier}) is applied last, as a scale on the
   * whole summed projection rather than another additive term - see that method's doc for why.
   */
  PlayerPredictionResponse.PredictionSummaryResponse buildProjection(
      String metric,
      List<PlayerGameStat> recentStats,
      List<PlayerGameStat> allStats,
      String position,
      List<TeamDefenseGameStat> opponentDefenseHistory,
      LeagueDefenseAverages leagueAverages,
      GameConditions conditions,
      String gameStatus) {
    List<Double> recentValues = metricValues(recentStats, metric);
    List<Double> allValues = metricValues(allStats, metric);

    double recentAverage = recentValues.isEmpty() ? 0.0d : recentValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double seasonAverage = allValues.isEmpty() ? 0.0d : allValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double blendedMean = (0.65d * recentAverage) + (0.35d * seasonAverage);
    double opponentAdjustment = opponentAdjustment(metric, opponentDefenseHistory, leagueAverages);
    double conditionsAdjustment = gameConditionsAdjustment(metric, conditions);
    double rushingQualityAdjustment = rushingQualityAdjustment(metric, recentStats);
    double advancedMetricAdjustment = advancedMetricAdjustment(metric, recentStats);
    double targetShareAdjustment = targetShareAdjustment(metric, recentStats);
    // usageAdjustment (offenseSnapPct-based) is deliberately NOT summed in here - see its own doc
    // for why: real standalone significance, but live A/B testing on the full formula (not just
    // recentAverage+seasonAverage in isolation) showed no net improvement, in fact very slightly
    // worse (rushingYards MAE +0.0095, receivingYards MAE +0.022) - see WORKPLAN.md. Still computed
    // and surfaced on the response (not included in projectedMean) for transparency, same treatment
    // as this file's other stored-but-unwired signals.
    double usageAdjustment = usageAdjustment(metric, position, recentStats);
    double injuryStatusMultiplier = injuryStatusMultiplier(gameStatus);
    double projectedMean =
        Math.max(
            0.0d,
            (blendedMean
                    + opponentAdjustment
                    + conditionsAdjustment
                    + rushingQualityAdjustment
                    + advancedMetricAdjustment
                    + targetShareAdjustment)
                * injuryStatusMultiplier);

    // Not surfaced in the UI today (see class doc) but kept for API consumers / future use.
    // stdDev uses the full season sample (not just the recent window) since 5 games is too few
    // to estimate variance from on its own. margin uses a z-score of 1.28, i.e. roughly an 80%
    // interval around projectedMean under a normal approximation - a simplification, since the
    // interval doesn't account for the opponent/conditions adjustments' own uncertainty. Also
    // scaled by injuryStatusMultiplier so a likely-out player's interval shrinks toward zero along
    // with the mean, instead of implying they might play after all.
    double stdDev = standardDeviation(allValues);
    int sampleSize = Math.max(1, allValues.size());
    double margin = 1.28d * (stdDev / Math.sqrt(sampleSize)) * injuryStatusMultiplier;
    double lower = Math.max(0.0d, projectedMean - margin);
    double upper = projectedMean + margin;

    return new PlayerPredictionResponse.PredictionSummaryResponse(
        metric,
        projectedMean,
        lower,
        upper,
        sampleSize,
        opponentAdjustment,
        conditionsAdjustment,
        rushingQualityAdjustment,
        advancedMetricAdjustment,
        targetShareAdjustment,
        usageAdjustment,
        notesForMetric(position, metric));
  }

  List<Double> metricValues(List<PlayerGameStat> stats, String metric) {
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

  /**
   * Nudges a projection based on the upcoming opponent's defensive history this season. For each
   * metric: average what the opponent has allowed per game, compare it to the real league-average
   * baseline computed from every stored team's defensive history (see {@link
   * #leagueDefenseAverages}), and scale the difference by a small coefficient. Also folds in
   * {@link #advancedDefenseAdjustment} (PFR advanced defense, Phase 4) - conceptually the same
   * "how good/bad is this opponent's defense" signal as the rest of this method, just sourced from
   * richer per-defender data aggregated to team-game level, so it lives here rather than as a
   * separate top-level adjustment field.
   *
   * <p><b>Real-calibrated, twice.</b> First pass (2026-08-20): fit {@code actual ~ recentAverage +
   * seasonAverage + opponentAdjustment + ...} per metric in R against every backtestable historical
   * game (see {@code PredictionBacktestService#runCalibrationExport}), full-dataset only - no
   * held-out validation yet at that point. Every metric came back overscaled: rushingYards needed
   * 44%, receivingYards 18%, receptions 7%, touchdowns 16%, passingYards 69% (weaker evidence,
   * p=0.051). <b>Second pass (2026-08-28)</b>: redid passingYards/touchdowns/receivingYards with a
   * real temporal train/test split this time (fit on season&lt;=cutoff, evaluate held-out on
   * season&gt;cutoff, replicated across 6 independent cutoffs 2018-2023 before trusting it) -
   * receivingYards's fit was done on the composite MINUS the already-separately-validated
   * zoneCoverageRateDelta contribution, so that term's own real calibration isn't double-counted.
   * rushingYards/receptions showed no real held-out signal this second pass and keep their
   * first-pass values unchanged. See each constant's own doc comment for exact fractions/p-values.
   */
  double opponentAdjustment(
      String metric, List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
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

    double baseAdjustment = 0.0d;
    if (!values.isEmpty()) {
      double opponentAverage = values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0d);
      baseAdjustment = switch (metric) {
        case "passingYards" -> (opponentAverage - leagueAverages.passingYardsAllowed()) * PASSING_YARDS_OPPONENT_COEFFICIENT;
        case "rushingYards" -> (opponentAverage - leagueAverages.rushingYardsAllowed()) * 0.035d;
        case "receivingYards" -> (opponentAverage - leagueAverages.receivingYardsAllowed()) * RECEIVING_YARDS_OPPONENT_COEFFICIENT;
        case "receptions" -> (opponentAverage - leagueAverages.receivingYardsAllowed()) * 0.0015d;
        case "touchdowns" -> (opponentAverage - leagueAverages.pointsAllowed()) * TOUCHDOWNS_OPPONENT_COEFFICIENT;
        default -> 0.0d;
      };
    }

    return baseAdjustment + advancedDefenseAdjustment(metric, defenseHistory, leagueAverages);
  }

  /**
   * Phase 4: nudges {@code passingYards}/{@code passingTouchdowns} using the opponent's recent
   * pass-rush pressure (PFR advanced defense, summed per game across every defender - see {@code
   * TeamDefenseGameStat.pressures}'s doc for why a sum, not an average of per-defender rates) and
   * {@code rushingYards}/{@code receivingYards} using their recent missed-tackle rate. Same
   * real-baseline-vs-opponent-average shape as the rest of {@link #opponentAdjustment}, just a
   * second, independent pair of signals not derivable from the basic yards/points-allowed numbers
   * already used there. Also folds in a real-calibrated zone-coverage-rate nudge on {@code
   * receivingYards}/{@code receptions} (participation-sourced - see {@code
   * ZONE_COVERAGE_RECEIVING_YARDS_COEFFICIENT}'s doc for the regression). Returns 0 for every
   * other metric, and for opponents with no relevant advanced-defense data yet (PFR charting only
   * goes back to 2018, participation charting is only reliable from 2023).
   */
  private double advancedDefenseAdjustment(
      String metric, List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
    return switch (metric) {
      case "passingYards" -> pressureDelta(defenseHistory, leagueAverages) * PRESSURE_PASSING_YARDS_COEFFICIENT;
      case "passingTouchdowns" ->
          pressureDelta(defenseHistory, leagueAverages) * PRESSURE_PASSING_TOUCHDOWNS_COEFFICIENT;
      case "rushingYards" -> missedTackleDelta(defenseHistory, leagueAverages) * MISSED_TACKLE_YARDS_COEFFICIENT;
      case "receivingYards" ->
          missedTackleDelta(defenseHistory, leagueAverages) * MISSED_TACKLE_RECEIVING_YARDS_COEFFICIENT
              + zoneCoverageRateDelta(defenseHistory, leagueAverages) * ZONE_COVERAGE_RECEIVING_YARDS_COEFFICIENT;
      case "receptions" ->
          zoneCoverageRateDelta(defenseHistory, leagueAverages) * ZONE_COVERAGE_RECEPTIONS_COEFFICIENT;
      default -> 0.0d;
    };
  }

  private double pressureDelta(List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
    List<Integer> pressures =
        defenseHistory.stream().map(TeamDefenseGameStat::getPressures).filter(java.util.Objects::nonNull).toList();
    if (pressures.isEmpty()) {
      return 0.0d;
    }
    double average = pressures.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0d);
    return average - leagueAverages.pressuresPerGame();
  }

  private double missedTackleDelta(List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
    return averageDoubleDelta(defenseHistory, TeamDefenseGameStat::getMissedTacklePct, leagueAverages.missedTacklePct());
  }

  // Trailing-average-minus-league-baseline deltas for the participation-sourced signals (see
  // TeamDefenseGameStat's doc). All three were exported raw via PredictionBacktestService
  // #runCalibrationExport and regressed against real outcomes, controlling for
  // opponentAdjustment (which already includes pressures/missedTacklePct above) - see
  // ZONE_COVERAGE_RECEIVING_YARDS_COEFFICIENT's doc for the real numbers. Only
  // zoneCoverageRateDelta held up (wired into advancedDefenseAdjustment above);
  // avgPassRushersDelta/avgDefendersInBoxDelta did not (p=0.10-0.97) - kept here, unwired, as
  // real checked negative results rather than deleted, same treatment as
  // rushingYardsOverExpectedPerAtt/team_implied_spread got.
  double zoneCoverageRateDelta(List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
    return averageDoubleDelta(defenseHistory, TeamDefenseGameStat::getZoneCoverageRate, leagueAverages.zoneCoverageRate());
  }

  double avgPassRushersDelta(List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
    return averageDoubleDelta(defenseHistory, TeamDefenseGameStat::getAvgPassRushers, leagueAverages.avgPassRushers());
  }

  double avgDefendersInBoxDelta(List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
    return averageDoubleDelta(
        defenseHistory, TeamDefenseGameStat::getAvgDefendersInBox, leagueAverages.avgDefendersInBox());
  }

  private double averageDoubleDelta(
      List<TeamDefenseGameStat> defenseHistory,
      java.util.function.Function<TeamDefenseGameStat, Double> accessor,
      double leagueAverage) {
    List<Double> values = defenseHistory.stream().map(accessor).filter(java.util.Objects::nonNull).toList();
    if (values.isEmpty()) {
      return 0.0d;
    }
    double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    return average - leagueAverage;
  }

  /**
   * A specific game's own weather/scoring-environment context - the target game being backtested,
   * or (live path) the player's upcoming game. {@code roof}/{@code windMph}/{@code gameTotalLine}
   * being null just means "unknown," which {@link #gameConditionsAdjustment} treats as no effect,
   * not an error - true for most of the live path today (see that method's doc for why).
   */
  record GameConditions(String roof, Double windMph, Double gameTotalLine) {
    static final GameConditions UNKNOWN = new GameConditions(null, null, null);

    boolean isOutdoors() {
      return roof == null || "outdoors".equalsIgnoreCase(roof) || "open".equalsIgnoreCase(roof);
    }
  }

  /**
   * Nudges a projection based on this specific game's own conditions - wind (passing metrics only,
   * and only when the game isn't in a dome/closed-roof stadium) and the Vegas game total (a proxy
   * for expected overall scoring volume, vs. {@link #DEFAULT_GAME_TOTAL_LINE} when the real one
   * isn't known).
   *
   * <p><b>Real-calibrated, twice.</b> First pass (2026-08-20): fit {@code actual ~ ... +
   * conditionsAdjustment} per metric using the then-current hand-picked coefficients as the
   * regression input - full-dataset only, no held-out validation. passingYards needed 51% of its
   * magnitude, receivingYards 12%, rushingYards showed no measurable effect (kept as a small
   * residual, not removed). The touchdown-metrics case (shared across passingTouchdowns/
   * rushingTouchdowns/touchdowns) wasn't isolated that pass. <b>Second pass (2026-08-28)</b>
   * redid passingYards/receivingYards/passingTouchdowns/touchdowns properly - a real temporal
   * train/test split (fit on season&lt;=cutoff, evaluate held-out on season&gt;cutoff), replicated
   * across 6 independent cutoffs to confirm it generalizes before trusting it (see the constants'
   * own doc comments for exact fractions/p-values/citations - this is also where the shared
   * touchdown-metrics constant finally got split into its own passingTouchdowns/touchdowns
   * values, since real evidence showed they want different magnitudes). rushingYards's total-line
   * coefficient is untouched both passes - still no real signal.
   *
   * <p>Backtesting uses real historical values from the target game's own stored {@code
   * PlayerGameStat} row (nflverse-sourced, via {@code nfl_schedules}). The live path only reliably
   * knows {@code roof} in advance (a static property of the host stadium, from {@code
   * Team.venueIndoor}) plus a live {@code windMph} forecast (via {@link WeatherForecastClient},
   * when the game is outdoors and within the forecastable window) - {@code gameTotalLine} isn't
   * available live yet (no game-level Vegas odds source wired up - Priority 1's odds-api.io
   * ingestion covers player props, not game lines), so live predictions get the wind/dome
   * adjustment but not the scoring-environment one until that's closed.
   */
  double gameConditionsAdjustment(String metric, GameConditions conditions) {
    double adjustment = 0.0d;

    if (conditions.isOutdoors() && conditions.windMph() != null) {
      double windAboveThreshold = Math.max(0.0d, conditions.windMph() - WIND_EFFECT_THRESHOLD_MPH);
      adjustment += switch (metric) {
        case "passingYards" -> -windAboveThreshold * 0.6214d; // was 0.6d; 2026-08-28 train/test fraction=1.0356
        case "passingTouchdowns" -> -windAboveThreshold * 0.00567d; // was 0.008d; 2026-08-28 train/test fraction=0.7086
        default -> 0.0d;
      };
    }

    double totalLine = conditions.gameTotalLine() != null ? conditions.gameTotalLine() : DEFAULT_GAME_TOTAL_LINE;
    double totalDelta = totalLine - DEFAULT_GAME_TOTAL_LINE;
    adjustment += switch (metric) {
      case "passingYards" -> totalDelta * 1.5534d; // was 1.5d; 2026-08-28 train/test fraction=1.0356, p<0.0001, full n=4,959
      case "rushingYards" -> totalDelta * 0.1d;
      case "receivingYards" -> totalDelta * 0.2443d; // was 0.24d; 2026-08-28 train/test fraction=1.0181, p<0.0001, full n=27,382
      case "passingTouchdowns" -> totalDelta * PASSING_TOUCHDOWNS_TOTAL_LINE_COEFFICIENT;
      case "rushingTouchdowns", "touchdowns" -> totalDelta * TOUCHDOWNS_TOTAL_LINE_COEFFICIENT;
      default -> 0.0d;
    };

    return adjustment;
  }

  /**
   * Nudges {@code rushingYards} using PFR's advanced rushing charting ({@code
   * PlayerGameStat.rushingYardsAfterContact}/{@code carries}): a player's own recent
   * yards-after-contact-per-carry, compared against a league-average baseline, as a
   * quality/reliability signal independent of the blend above. Before-contact + after-contact
   * yards already roughly sum to the rushing yards the blend already projects - this isn't a
   * second volume predictor, just a small tilt (see {@link #AFTER_CONTACT_QUALITY_COEFFICIENT})
   * scaled by how many carries the player actually gets. Returns 0 for every other metric, and for
   * players with no PFR advanced-rushing data yet (charting only goes back to 2018 and isn't
   * published for every game).
   */
  double rushingQualityAdjustment(String metric, List<PlayerGameStat> recentStats) {
    if (!"rushingYards".equals(metric)) {
      return 0.0d;
    }

    int totalCarries = 0;
    int totalAfterContactYards = 0;
    int gamesWithData = 0;
    for (PlayerGameStat stat : recentStats) {
      Integer afterContactYards = stat.getRushingYardsAfterContact();
      Integer carries = stat.getCarries();
      if (afterContactYards != null && carries != null && carries > 0) {
        totalAfterContactYards += afterContactYards;
        totalCarries += carries;
        gamesWithData++;
      }
    }

    if (gamesWithData == 0 || totalCarries == 0) {
      return 0.0d;
    }

    double playerRate = totalAfterContactYards / (double) totalCarries;
    double averageCarriesPerGame = totalCarries / (double) gamesWithData;
    return (playerRate - LEAGUE_AVERAGE_AFTER_CONTACT_YARDS_PER_CARRY)
        * averageCarriesPerGame
        * AFTER_CONTACT_QUALITY_COEFFICIENT;
  }

  /**
   * Nudges {@code passingYards}/{@code passingTouchdowns}/{@code receivingYards} using nflverse
   * Next Gen Stats ({@code PlayerGameStat.passingCpoe}/{@code receivingYacAboveExpectation}) - a
   * player's own recent performance relative to NGS's own expectation model, not a second,
   * independently-derived baseline the way {@link #opponentAdjustment} needs one. Deliberately
   * does NOT touch {@code rushingYards}: NGS also publishes a rushing-yards-over-expected metric,
   * but wiring it in here would stack a second, independent rushing-quality signal on top of
   * {@link #rushingQualityAdjustment} (PFR after-contact yards) without any evidence the two are
   * additive rather than redundant - see {@code PlayerGameStat.rushingYardsOverExpectedPerAtt}'s
   * doc. Returns 0 for every other metric, and for players with no NGS data yet (charting only
   * goes back to 2016 and only exists for tracked skill positions).
   */
  double advancedMetricAdjustment(String metric, List<PlayerGameStat> recentStats) {
    return switch (metric) {
      case "passingYards" -> passingAccuracyNudge(recentStats) * CPOE_PASSING_YARDS_COEFFICIENT;
      case "passingTouchdowns" -> passingAccuracyNudge(recentStats) * CPOE_PASSING_TOUCHDOWNS_COEFFICIENT;
      case "receivingYards" -> receivingQualityNudge(recentStats);
      default -> 0.0d;
    };
  }

  private double passingAccuracyNudge(List<PlayerGameStat> recentStats) {
    List<Double> cpoeValues =
        recentStats.stream()
            .map(PlayerGameStat::getPassingCpoe)
            .filter(java.util.Objects::nonNull)
            .toList();
    return cpoeValues.isEmpty()
        ? 0.0d
        : cpoeValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
  }

  private double receivingQualityNudge(List<PlayerGameStat> recentStats) {
    double receptionsWeightedYacTotal = 0.0d;
    int totalReceptions = 0;
    int gamesWithData = 0;
    for (PlayerGameStat stat : recentStats) {
      Double yacAboveExpectationPerCatch = stat.getReceivingYacAboveExpectation();
      Integer receptions = stat.getReceptions();
      if (yacAboveExpectationPerCatch != null && receptions != null && receptions > 0) {
        receptionsWeightedYacTotal += yacAboveExpectationPerCatch * receptions;
        totalReceptions += receptions;
        gamesWithData++;
      }
    }

    if (gamesWithData == 0 || totalReceptions == 0) {
      return 0.0d;
    }

    double playerRate = receptionsWeightedYacTotal / totalReceptions;
    double averageReceptionsPerGame = totalReceptions / (double) gamesWithData;
    return playerRate * averageReceptionsPerGame * RECEIVING_QUALITY_COEFFICIENT;
  }

  /**
   * Nudges {@code receivingYards}/{@code receptions} using the player's own recent average {@code
   * PlayerGameStat.wopr} (Weighted Opportunity Rating - nflverse's own composite of target share
   * and air yards share, roughly {@code 1.5*target_share + 0.7*air_yards_share}) against {@link
   * #LEAGUE_AVERAGE_WOPR}, a real average computed from this database's own stored 2025 data (see
   * that constant's doc), not a guessed ballpark. A genuine opportunity/role signal - correlated
   * with, but not fully redundant with, the recent-yardage average {@code blendedMean} already
   * captures: WOPR reflects how big a role the offense is giving a player independent of whether
   * recent games happened to convert those opportunities into yards (a bad QB day or tough
   * matchup can suppress yards without shrinking role). Deliberately stores {@code target_share}/
   * {@code air_yards_share} separately too but doesn't wire them in on top of this - they'd be
   * double-counting the same signal WOPR already composites, same "don't stack redundant nudges"
   * reasoning as {@link #advancedMetricAdjustment}'s doc. Returns 0 for every other metric, and
   * for players with no recent target-share data (e.g. before the 2026-08-20 backfill, or a
   * position that doesn't get targeted).
   */
  double targetShareAdjustment(String metric, List<PlayerGameStat> recentStats) {
    if (!"receivingYards".equals(metric) && !"receptions".equals(metric)) {
      return 0.0d;
    }

    List<Double> woprValues =
        recentStats.stream().map(PlayerGameStat::getWopr).filter(java.util.Objects::nonNull).toList();
    if (woprValues.isEmpty()) {
      return 0.0d;
    }

    double recentAverageWopr = woprValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double delta = recentAverageWopr - LEAGUE_AVERAGE_WOPR;
    return switch (metric) {
      case "receivingYards" -> delta * WOPR_RECEIVING_YARDS_COEFFICIENT;
      case "receptions" -> delta * WOPR_RECEPTIONS_COEFFICIENT;
      default -> 0.0d;
    };
  }

  /**
   * Nudges {@code rushingYards} (RB) / {@code receivingYards} (WR, TE) using the player's own
   * recent offensive snap share ({@code PlayerGameStat.offenseSnapPct}, from {@code
   * import_snap_counts.R}) against a real, position-specific baseline (see {@link
   * #LEAGUE_AVERAGE_SNAP_PCT_BY_POSITION}). Unlike {@link #targetShareAdjustment} (a receiving-role
   * signal via WOPR), snap share is a genuine opportunity/workload signal confirmed to predict
   * volume independent of what recent/season yardage averages already capture - see {@link
   * #SNAP_PCT_RUSHING_YARDS_COEFFICIENT}'s doc for the real regression this coefficient comes from.
   * A position-aware baseline is required here in a way WOPR didn't need one: a workhorse RB and a
   * rotational WR have very different "normal" snap shares, so a single flat league-average would
   * risk being actively wrong, not just imprecise - exactly why this field was left unwired until a
   * real per-position baseline existed. Returns 0 for every other metric/position, and for players
   * with no snap-count data yet.
   */
  double usageAdjustment(String metric, String position, List<PlayerGameStat> recentStats) {
    // Map.of(...).get(null) throws NPE rather than returning null (confirmed live 2026-08-20/22 -
    // players with an unknown/blank position, already a real handled case elsewhere in this file,
    // reach here with position == null) - guard explicitly instead of relying on containsKey/get.
    if (position == null) {
      return 0.0d;
    }

    Double baseline = LEAGUE_AVERAGE_SNAP_PCT_BY_POSITION.get(position);
    if (baseline == null) {
      return 0.0d;
    }

    List<Double> snapPctValues =
        recentStats.stream().map(PlayerGameStat::getOffenseSnapPct).filter(java.util.Objects::nonNull).toList();
    if (snapPctValues.isEmpty()) {
      return 0.0d;
    }

    double recentAverageSnapPct = snapPctValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double delta = recentAverageSnapPct - baseline;

    return switch (position) {
      case "RB" -> "rushingYards".equals(metric) ? delta * SNAP_PCT_RUSHING_YARDS_COEFFICIENT : 0.0d;
      case "WR", "TE" -> "receivingYards".equals(metric) ? delta * SNAP_PCT_RECEIVING_YARDS_COEFFICIENT : 0.0d;
      default -> 0.0d;
    };
  }

  /**
   * Weekly-status participation likelihood (see {@code Player.gameStatus}, sourced by {@code
   * InjuryStatusRefreshWorker} from ESPN's league-wide injuries feed - distinct from the
   * roster-level {@code Player.injuryStatus}). Applied as a multiplicative scale on the whole
   * projected value in {@link #buildProjection}, unlike every other adjustment in this file: those
   * nudge how well a player is expected to perform in a game they're still expected to play, while
   * a weekly status changes whether they play at all - an additive nudge can't represent "probably
   * won't suit up" the way a multiplier close to zero can.
   *
   * <p>These are rough, hand-picked participation-rate estimates (not fit against how often a
   * "Questionable"/"Doubtful" player actually ends up playing), same honesty caveat as every other
   * constant in this file. "Out"/"Injured Reserve"/"PUP"/"Suspended"/"Did Not Report" all floor to
   * 0 - meaningfully different from the small nudges above, since these genuinely mean "not
   * playing," not "playing but expected to do a bit worse." An unrecognized status string is
   * treated as a no-op (1.0) rather than guessed at.
   *
   * <p>Deliberately not applied in {@code PredictionBacktestService}: nothing in this database
   * records what a player's weekly status <em>was</em> for a historical game, only the
   * live-fetched current designation exists - so unlike {@link #gameConditionsAdjustment} (which
   * has a real historical counterpart via stored {@code PlayerGameStat} columns), this is a
   * live-path-only signal with no backtestable half at all.
   */
  static double injuryStatusMultiplier(String gameStatus) {
    if (gameStatus == null || gameStatus.isBlank()) {
      return 1.0d;
    }

    return switch (gameStatus.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "questionable" -> QUESTIONABLE_PARTICIPATION_MULTIPLIER;
      case "doubtful" -> DOUBTFUL_PARTICIPATION_MULTIPLIER;
      case "out",
          "injured reserve",
          "ir",
          "physically unable to perform",
          "pup",
          "suspended",
          "did not report" ->
          0.0d;
      default -> 1.0d;
    };
  }

  /**
   * Per-game defensive averages across every stored team's {@code TeamDefenseGameStat} history -
   * the real baseline "opponentAdjustment" compares an upcoming opponent against, instead of a
   * hardcoded guess. Cached ({@link #LEAGUE_AVERAGES_TTL}) since it's identical for every player
   * and only changes as more team-defense data accumulates. Falls back to rough NFL ballpark
   * figures (225 passing yards, 110 rushing, 125 receiving, 21 points allowed per game) only if
   * no team-defense data is stored at all yet - so a fresh/empty database still produces a
   * reasonable adjustment instead of dividing by zero or always returning 0.
   */
  private LeagueDefenseAverages leagueDefenseAverages() {
    CachedLeagueAverages cached = cachedLeagueAverages;
    if (cached != null && !cached.isExpired(clock.instant())) {
      return cached.averages();
    }

    LeagueDefenseAverages averages = leagueDefenseAverages(LocalDate.now(clock));
    cachedLeagueAverages = new CachedLeagueAverages(averages, clock.instant().plus(LEAGUE_AVERAGES_TTL));
    return averages;
  }

  /**
   * Historical-date twin of the live, cached {@link #leagueDefenseAverages()} above - same
   * averaging logic and fallbacks, but scoped to {@code [asOfDate - LEAGUE_AVERAGES_LOOKBACK,
   * asOfDate]} instead of "now", and deliberately uncached since {@link PredictionBacktestService}
   * calls this with many different dates (a single-entry cache would just thrash).
   */
  LeagueDefenseAverages leagueDefenseAverages(LocalDate asOfDate) {
    List<TeamDefenseGameStat> allDefenseGames =
        teamDefenseGameStatRepository.findAllByGameDateBetween(
            asOfDate.minusDays(LEAGUE_AVERAGES_LOOKBACK.toDays()), asOfDate);
    return leagueDefenseAveragesFrom(allDefenseGames);
  }

  /**
   * Same averaging/fallback logic as {@link #leagueDefenseAverages(LocalDate)}, but over an
   * already-fetched list instead of issuing its own query - lets {@link PredictionBacktestService}
   * preload every {@code TeamDefenseGameStat} once and filter in memory per backtested game,
   * instead of one DB round-trip per game (confirmed directly: the naive per-game-query version of
   * this backtest took over two minutes against only 1,252 stored defense rows).
   */
  LeagueDefenseAverages leagueDefenseAveragesFrom(List<TeamDefenseGameStat> defenseGames) {
    return new LeagueDefenseAverages(
        averageOrDefault(defenseGames, TeamDefenseGameStat::getPassingYardsAllowed, 225.0d),
        averageOrDefault(defenseGames, TeamDefenseGameStat::getRushingYardsAllowed, 110.0d),
        averageOrDefault(defenseGames, TeamDefenseGameStat::getReceivingYardsAllowed, 125.0d),
        averageOrDefault(defenseGames, TeamDefenseGameStat::getPointsAllowed, 21.0d),
        // 8.5 pressures/game and a 9% missed-tackle rate are real computed averages from this
        // session's live PFR pull (2023-2025, ~7,900 rows) - see WORKPLAN.md's Phase 4 entry -
        // used as the fallback the same way 225/110/125/21 above are, not derived from this
        // database's own stored data like the primary averageOrDefault path is.
        averageOrDefault(defenseGames, TeamDefenseGameStat::getPressures, 8.5d),
        averageOrDefaultDouble(defenseGames, TeamDefenseGameStat::getMissedTacklePct, 0.09d),
        // Real computed averages (not guesses), same treatment as the two fallbacks above -
        // queried directly against this database's own 2023-2025 participation backfill
        // (1,710 team-games) on 2026-08-26, right after that backfill landed. Notably, real zone
        // coverage rate came back well under the ~55-65% folk-wisdom figure - confirmed directly
        // against the raw MAN_COVERAGE/ZONE_COVERAGE play counts, not a computation bug (see
        // import_participation_defense.R's header comment for the real zero-sentinel bug that WAS
        // found and fixed in the same pass, affecting avgPassRushers/avgDefendersInBox only).
        averageOrDefaultDouble(defenseGames, TeamDefenseGameStat::getZoneCoverageRate, 0.2885d),
        averageOrDefaultDouble(defenseGames, TeamDefenseGameStat::getAvgPassRushers, 4.3117d),
        averageOrDefaultDouble(defenseGames, TeamDefenseGameStat::getAvgDefendersInBox, 6.2013d));
  }

  private double averageOrDefault(
      List<TeamDefenseGameStat> defenseGames,
      java.util.function.Function<TeamDefenseGameStat, Integer> accessor,
      double defaultValue) {
    List<Integer> values = defenseGames.stream().map(accessor).filter(java.util.Objects::nonNull).toList();
    return values.isEmpty()
        ? defaultValue
        : values.stream().mapToDouble(Integer::doubleValue).average().orElse(defaultValue);
  }

  private double averageOrDefaultDouble(
      List<TeamDefenseGameStat> defenseGames,
      java.util.function.Function<TeamDefenseGameStat, Double> accessor,
      double defaultValue) {
    List<Double> values = defenseGames.stream().map(accessor).filter(java.util.Objects::nonNull).toList();
    return values.isEmpty()
        ? defaultValue
        : values.stream().mapToDouble(Double::doubleValue).average().orElse(defaultValue);
  }

  /**
   * Heuristic "how much should you trust this" score (0-1), separate from the statistical
   * interval above. Not currently displayed in the UI (see class doc) but kept for API
   * consumers. Blends three components, each capped at 1.0:
   *
   * <ul>
   *   <li>sample size adequacy (45% weight) - maxes out once the player has 10+ stored games
   *   <li>recent-data adequacy (30% weight) - maxes out once at least 5 recent games are present
   *   <li>freshness (25% weight) - decays linearly over a year since the last stored game date
   * </ul>
   *
   * <p>The 45/30/25 split and the 10-game/5-game/365-day caps are hand-picked, not derived from
   * how well the score actually predicts projection accuracy.
   */
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

  // Positions that actually post the offensive skill-position stats this service projects.
  // Package-private (not private): PredictionBacktestService's outcome backtest relies on
  // metricsForPosition returning an empty list for real non-skill positions (see below) to avoid
  // diluting its aggregate MAE with structurally-near-zero comparisons for kickers/linemen/etc.
  private static final java.util.Set<String> SKILL_POSITIONS = java.util.Set.of("QB", "RB", "WR", "TE", "FB");

  // Which stats we bother projecting per position - e.g. QBs don't get a receiving projection,
  // and non-QBs don't get a passing one. A position that's null/blank (genuinely unknown - could
  // still be a skill player with unsynced data) falls back to a reasonable default rather than
  // projecting nothing. A non-blank position that isn't in SKILL_POSITIONS (OL/DL/LB/DB/K/P/LS/
  // etc.) is a real, known non-skill position - these players never meaningfully post
  // rushing/receiving/passing stats, so they get no projections at all rather than a manufactured
  // fallback (this used to hand every non-skill player the RB metric list, which is what diluted
  // the outcome-MAE backtest once its player set expanded past a handful of skill players - see
  // WORKPLAN.md).
  List<String> metricsForPosition(String position) {
    if (position == null || position.isBlank()) {
      return List.of("rushingYards", "receivingYards", "receptions", "touchdowns");
    }
    if (!SKILL_POSITIONS.contains(position)) {
      return List.of();
    }
    return switch (position) {
      case "QB" -> List.of("passingYards", "rushingYards", "passingTouchdowns", "turnovers");
      case "RB" -> List.of("rushingYards", "receivingYards", "receptions", "touchdowns");
      default -> List.of("receivingYards", "receptions", "touchdowns"); // WR, TE, FB
    };
  }

  String normalizePosition(String position) {
    return position == null ? null : position.trim().toUpperCase();
  }

  private record CachedPrediction(PlayerPredictionResponse response, Instant expiresAt) {
    private boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }

  record LeagueDefenseAverages(
      double passingYardsAllowed,
      double rushingYardsAllowed,
      double receivingYardsAllowed,
      double pointsAllowed,
      double pressuresPerGame,
      double missedTacklePct,
      double zoneCoverageRate,
      double avgPassRushers,
      double avgDefendersInBox) {}

  private record CachedLeagueAverages(LeagueDefenseAverages averages, Instant expiresAt) {
    private boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }
}
