package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlayerGameStatService {

  private static final int HISTORICAL_SEASON_BACKFILL_WINDOW = 12;
  private static final long STAT_REFRESH_WINDOW_HOURS = 6;

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final EspnPlayerClient espnPlayerClient;
  private final ObjectMapper objectMapper;
  private final PlayerHistoryBackfillService playerHistoryBackfillService;
  private final Clock clock = Clock.systemUTC();

  public PlayerGameStatService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      EspnPlayerClient espnPlayerClient,
      ObjectMapper objectMapper,
      PlayerHistoryBackfillService playerHistoryBackfillService) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.espnPlayerClient = espnPlayerClient;
    this.objectMapper = objectMapper;
    this.playerHistoryBackfillService = playerHistoryBackfillService;
  }

  @Transactional(readOnly = true)
  public List<PlayerGameStat> listStatsForAthleteId(String athleteId) {
    Player player = getPlayerByAthleteId(athleteId);
    return playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(player.getId());
  }

  // Game-log history now comes from the R/nflverse backfill (see PlayerHistoryBackfillService)
  // instead of the ESPN season-loop below. loadHistoricalSnapshots/upsertGameStat/
  // shouldRefreshStats are kept temporarily as a rollback path until the R data is proven out in
  // real use, but are no longer called from here.
  public List<PlayerGameStat> syncStatsForAthleteId(String athleteId) {
    Player player = getPlayerByAthleteId(athleteId);
    List<PlayerGameStat> existingStats =
        playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(player.getId());

    if (existingStats.isEmpty()) {
      playerHistoryBackfillService.requestBackfillIfNeeded(athleteId);
    }

    return existingStats;
  }

  private List<PlayerGameStatSnapshot> loadHistoricalSnapshots(
      Player player, String athleteId, Instant fetchedAt) {
    List<PlayerGameStatSnapshot> snapshots = new ArrayList<>();
    int currentSeason = Year.now(clock).getValue();

    for (int season = currentSeason;
        season >= currentSeason - HISTORICAL_SEASON_BACKFILL_WINDOW;
        season--) {
      String sourceUrl = espnPlayerClient.buildAthleteStatisticsLogUrl(athleteId, season);
      List<PlayerGameStatSnapshot> seasonSnapshots =
          EspnPlayerGameStatMapper.toSnapshots(
              player, espnPlayerClient.fetchAthleteStatisticsLog(athleteId, season), sourceUrl, fetchedAt);

      if (seasonSnapshots.isEmpty()) {
        String gameLogUrl = espnPlayerClient.buildAthleteGameLogUrl(athleteId, player.getDisplayName(), season);
        seasonSnapshots =
            EspnPlayerGameStatMapper.toSnapshotsFromGameLogPage(
                player,
                espnPlayerClient.fetchAthleteGameLogPage(athleteId, player.getDisplayName(), season),
                gameLogUrl,
                fetchedAt,
                objectMapper);
      }

      snapshots.addAll(seasonSnapshots);
    }

    return snapshots;
  }

  private PlayerGameStat upsertGameStat(Player player, PlayerGameStatSnapshot snapshot, Instant now) {
    Optional<PlayerGameStat> existing =
        playerGameStatRepository.findByPlayer_IdAndGameDate(player.getId(), snapshot.gameDate());

    PlayerGameStat entity = existing.orElseGet(PlayerGameStat::new);
    entity.setPlayer(player);
    entity.setGameDate(snapshot.gameDate());
    entity.setSeason(snapshot.season());
    entity.setWeek(snapshot.week());
    entity.setHomeAway(snapshot.homeAway());
    entity.setOpponentName(snapshot.opponentName());
    entity.setOpponentTeamId(snapshot.opponentTeamId());
    entity.setGamesPlayed(snapshot.gamesPlayed() == null ? 1 : snapshot.gamesPlayed());
    entity.setPassingYards(snapshot.passingYards());
    entity.setRushingYards(snapshot.rushingYards());
    entity.setTotalYards(snapshot.totalYards());
    entity.setPassingTouchdowns(snapshot.passingTouchdowns());
    entity.setRushingTouchdowns(snapshot.rushingTouchdowns());
    entity.setReceivingTouchdowns(snapshot.receivingTouchdowns());
    entity.setTouchdowns(snapshot.touchdowns());
    entity.setTotalTouchdowns(snapshot.totalTouchdowns());
    entity.setInterceptions(snapshot.interceptions());
    entity.setFumbles(snapshot.fumbles());
    entity.setFumblesLost(snapshot.fumblesLost());
    entity.setTurnovers(snapshot.turnovers());
    entity.setSnapCount(snapshot.snapCount());
    entity.setCarries(snapshot.carries());
    entity.setReceivingTargets(snapshot.receivingTargets());
    entity.setReceptions(snapshot.receptions());
    entity.setReceivingYards(snapshot.receivingYards());
    entity.setDrops(snapshot.drops());
    entity.setSourceUrl(snapshot.sourceUrl());
    entity.setRawPayload(snapshot.rawPayload());
    entity.setFetchedAt(snapshot.fetchedAt());
    entity.setCreatedAt(entity.getCreatedAt() == null ? now : entity.getCreatedAt());
    entity.setUpdatedAt(now);

    return playerGameStatRepository.save(entity);
  }

  private Player getPlayerByAthleteId(String athleteId) {
    return playerRepository
        .findByEspnAthleteId(athleteId)
        .orElseThrow(
            () -> new PlayerNotFoundException("No stored player found for ESPN athlete " + athleteId));
  }

  private boolean shouldRefreshStats(Long playerId, List<PlayerGameStat> existingStats) {
    if (existingStats.isEmpty()) {
      return true;
    }

    return playerGameStatRepository
        .findFirstByPlayer_IdOrderByFetchedAtDesc(playerId)
        .map(PlayerGameStat::getFetchedAt)
        .filter(fetchedAt -> fetchedAt.isAfter(clock.instant().minus(STAT_REFRESH_WINDOW_HOURS, ChronoUnit.HOURS)))
        .isEmpty();
  }
}
