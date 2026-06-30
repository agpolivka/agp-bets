package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
@Transactional
public class EspnPlayerIngestionService {

  private final PlayerRepository playerRepository;
  private final EspnPlayerClient espnPlayerClient;
  private final PlayerUpsertService playerUpsertService;
  private final PlayerRefreshService playerRefreshService;
  private final Clock clock;

  @Autowired
  public EspnPlayerIngestionService(
      PlayerRepository playerRepository,
      EspnPlayerClient espnPlayerClient,
      PlayerUpsertService playerUpsertService,
      PlayerRefreshService playerRefreshService) {
    this(playerRepository, espnPlayerClient, playerUpsertService, playerRefreshService, Clock.systemUTC());
  }

  EspnPlayerIngestionService(
      PlayerRepository playerRepository,
      EspnPlayerClient espnPlayerClient,
      PlayerUpsertService playerUpsertService,
      PlayerRefreshService playerRefreshService,
      Clock clock) {
    this.playerRepository = playerRepository;
    this.espnPlayerClient = espnPlayerClient;
    this.playerUpsertService = playerUpsertService;
    this.playerRefreshService = playerRefreshService;
    this.clock = clock;
  }

  public Player findOrLoadPlayerByName(String playerName) {
    Player existingPlayer =
        playerRepository
            .findFirstByDisplayNameIgnoreCase(playerName)
            .orElse(null);

    if (existingPlayer != null) {
      return loadOrRefreshPlayer(existingPlayer);
    }

    Player loadedPlayer = syncPlayerByName(playerName);
    queueBackgroundRefresh(loadedPlayer);
    return loadedPlayer;
  }

  @Transactional(readOnly = true)
  public List<AthleteCandidate> findPlayerCandidatesByName(String playerName) {
    return espnPlayerClient.findAthleteCandidatesByDisplayName(playerName, 10);
  }

  public Player syncPlayerByName(String playerName) {
    AthleteCandidate candidate =
        espnPlayerClient
            .findBestAthleteCandidateByDisplayName(playerName)
            .orElseThrow(() -> new PlayerNotFoundException("No ESPN athlete found for " + playerName));
    JsonNode athleteNode = espnPlayerClient.fetchAthleteById(candidate.espnAthleteId());
    return playerUpsertService.upsertAthlete(
        athleteNode, espnPlayerClient.buildAthleteUrl(candidate.espnAthleteId()));
  }

  public Player syncPlayerByEspnAthleteId(String athleteId) {
    JsonNode athleteNode = espnPlayerClient.fetchAthleteById(athleteId);
    return playerUpsertService.upsertAthlete(
        athleteNode, espnPlayerClient.buildAthleteUrl(athleteId));
  }

  @Transactional(readOnly = true)
  public List<Player> listPlayers() {
    return playerRepository.findAllByOrderByDisplayNameAsc();
  }

  public Player getPlayerByEspnAthleteId(String athleteId) {
    Player player =
        playerRepository
            .findByEspnAthleteId(athleteId)
            .orElseThrow(() -> new PlayerNotFoundException("No stored player found for ESPN athlete " + athleteId));
    return loadOrRefreshPlayer(player);
  }

  @Transactional(readOnly = true)
  public boolean isPlayerStored(String athleteId) {
    return playerRepository.findByEspnAthleteId(athleteId).isPresent();
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public PlayerMetadataBackfillResult backfillPlayerMetadata() {
    List<Player> stalePlayers = playerRepository.findPlayersNeedingMetadataBackfill();
    List<Player> refreshedPlayers = new ArrayList<>();
    List<String> failedAthleteIds = new ArrayList<>();

    for (Player player : stalePlayers) {
      String athleteId = player.getEspnAthleteId();
      if (athleteId == null || athleteId.isBlank()) {
        continue;
      }

      try {
        refreshedPlayers.add(syncPlayerByEspnAthleteId(athleteId));
      } catch (RuntimeException exception) {
        failedAthleteIds.add(athleteId);
      }
    }

    return new PlayerMetadataBackfillResult(
        stalePlayers.size(), refreshedPlayers, failedAthleteIds, clock.instant());
  }

  private void queueBackgroundRefreshIfNeeded(Player player) {
    if (player.getEspnAthleteId() == null || player.getEspnAthleteId().isBlank()) {
      return;
    }

    if (player.getFetchedAt() == null || player.getFetchedAt().isBefore(clock.instant().minus(6, ChronoUnit.HOURS))) {
      queueBackgroundRefresh(player);
    }
  }

  private void queueBackgroundRefresh(Player player) {
    if (player.getEspnAthleteId() == null || player.getEspnAthleteId().isBlank()) {
      return;
    }

    playerRefreshService.refreshPlayerByAthleteIdAsync(player.getEspnAthleteId());
  }

  private Player loadOrRefreshPlayer(Player player) {
    if (needsImmediateRefresh(player)) {
      queueBackgroundRefresh(player);
      return player;
    }

    queueBackgroundRefreshIfNeeded(player);
    return player;
  }

  private boolean needsImmediateRefresh(Player player) {
    if (player == null) {
      return false;
    }

    if (player.getEspnAthleteId() == null || player.getEspnAthleteId().isBlank()) {
      return false;
    }

    return player.getPosition() == null
        || player.getPosition().isBlank()
        || player.getTeamName() == null
        || player.getTeamName().isBlank();
  }
}
