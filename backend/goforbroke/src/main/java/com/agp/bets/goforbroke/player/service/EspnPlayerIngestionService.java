package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
  private final ConcurrentMap<String, CachedCandidates> candidateCache = new ConcurrentHashMap<>();
  private static final long SEARCH_CACHE_TTL_MINUTES = 5;

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
    String cacheKey = normalize(playerName);
    CachedCandidates cached = candidateCache.get(cacheKey);
    if (cached != null && !cached.isExpired(clock.instant())) {
      return cached.candidates();
    }

    List<AthleteCandidate> localCandidates = findLocalPlayerCandidates(playerName);
    if (hasStrongLocalMatch(localCandidates)) {
      cacheCandidates(cacheKey, localCandidates);
      return localCandidates;
    }

    List<AthleteCandidate> espnCandidates = espnPlayerClient.searchAthletesByQuery(playerName, 10);
    if (espnCandidates.isEmpty()) {
      cacheCandidates(cacheKey, localCandidates);
      return localCandidates;
    }

    List<AthleteCandidate> merged = mergeCandidates(localCandidates, espnCandidates, 5);
    cacheCandidates(cacheKey, merged);
    return merged;
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

  // MIN_CANDIDATE_SCORE mirrors EspnAthleteMapper's threshold - now that this scores every stored
  // player (not just SQL-LIKE substring pre-filtered ones), a floor keeps unrelated low-confidence
  // names out of the result set.
  private static final double MIN_LOCAL_CANDIDATE_SCORE = 0.35d;

  private List<AthleteCandidate> findLocalPlayerCandidates(String playerName) {
    if (playerName == null || playerName.isBlank()) {
      return List.of();
    }

    return playerRepository.findAllByOrderByDisplayNameAsc().stream()
        .map(player -> toLocalCandidate(player, playerName))
        .filter(Objects::nonNull)
        .filter(candidate -> candidate.score() >= MIN_LOCAL_CANDIDATE_SCORE)
        .sorted(
            java.util.Comparator.comparingDouble(AthleteCandidate::score)
                .reversed()
                .thenComparing(candidate -> candidate.active() != null && candidate.active() ? 0 : 1)
                .thenComparing(candidate -> candidate.position() == null || candidate.position().isBlank() ? 1 : 0)
                .thenComparing(AthleteCandidate::displayName, String.CASE_INSENSITIVE_ORDER))
        .limit(5)
        .toList();
  }

  private AthleteCandidate toLocalCandidate(Player player, String query) {
    if (player == null || player.getEspnAthleteId() == null || player.getEspnAthleteId().isBlank()) {
      return null;
    }

    double score =
        com.agp.bets.goforbroke.common.text.NameSimilarity.similarity(query, player.getDisplayName());
    return new AthleteCandidate(
        player.getEspnAthleteId(),
        player.getDisplayName(),
        player.getFirstName(),
        player.getLastName(),
        player.getPosition(),
        player.getJerseyNumber(),
        player.getTeamName(),
        player.getTeamId(),
        player.getActive(),
        score);
  }

  private boolean hasStrongLocalMatch(List<AthleteCandidate> candidates) {
    if (candidates.isEmpty()) {
      return false;
    }

    AthleteCandidate topCandidate = candidates.get(0);
    return topCandidate.score() >= 0.95d;
  }

  private void cacheCandidates(String cacheKey, List<AthleteCandidate> candidates) {
    if (cacheKey == null || cacheKey.isBlank()) {
      return;
    }

    candidateCache.put(cacheKey, new CachedCandidates(List.copyOf(candidates), clock.instant()));
  }

  private List<AthleteCandidate> mergeCandidates(
      List<AthleteCandidate> left, List<AthleteCandidate> right, int maxCandidates) {
    return java.util.stream.Stream.concat(left.stream(), right.stream())
        .filter(Objects::nonNull)
        .collect(java.util.stream.Collectors.toMap(
            AthleteCandidate::espnAthleteId,
            candidate -> candidate,
            (existing, replacement) -> replacement.score() >= existing.score() ? replacement : existing))
        .values()
        .stream()
        .sorted(
            java.util.Comparator.comparingDouble(AthleteCandidate::score)
                .reversed()
                .thenComparing(AthleteCandidate::displayName, String.CASE_INSENSITIVE_ORDER))
        .limit(maxCandidates)
        .toList();
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }

    return value.trim().toLowerCase(Locale.ROOT);
  }

  private record CachedCandidates(List<AthleteCandidate> candidates, java.time.Instant cachedAt) {
    private boolean isExpired(java.time.Instant now) {
      return cachedAt == null || now.isAfter(cachedAt.plus(SEARCH_CACHE_TTL_MINUTES, ChronoUnit.MINUTES));
    }
  }
}
