package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlayerGameStatService {

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final EspnPlayerClient espnPlayerClient;
  private final ObjectMapper objectMapper;
  private final Clock clock = Clock.systemUTC();

  public PlayerGameStatService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      EspnPlayerClient espnPlayerClient,
      ObjectMapper objectMapper) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.espnPlayerClient = espnPlayerClient;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<PlayerGameStat> listStatsForAthleteId(String athleteId) {
    Player player = getPlayerByAthleteId(athleteId);
    return playerGameStatRepository.findAllByPlayer_IdOrderByGameDateDesc(player.getId());
  }

  public List<PlayerGameStat> syncStatsForAthleteId(String athleteId) {
    Player player = getPlayerByAthleteId(athleteId);
    Instant now = clock.instant();
    String sourceUrl = espnPlayerClient.buildAthleteGameLogUrl(athleteId, player.getDisplayName());
    String pageHtml = espnPlayerClient.fetchAthleteGameLogPage(athleteId, player.getDisplayName());

    List<PlayerGameStatSnapshot> snapshots =
        EspnPlayerGameStatMapper.toSnapshotsFromGameLogPage(
            player, pageHtml, sourceUrl, now, objectMapper);

    playerGameStatRepository.deleteAllByPlayer_Id(player.getId());
    List<PlayerGameStat> entities =
        snapshots.stream()
            .map(snapshot -> EspnPlayerGameStatMapper.toEntity(player, snapshot, now))
            .toList();
    return playerGameStatRepository.saveAll(entities);
  }

  private Player getPlayerByAthleteId(String athleteId) {
    return playerRepository
        .findByEspnAthleteId(athleteId)
        .orElseThrow(() -> new IllegalStateException("No stored player found for ESPN athlete " + athleteId));
  }
}
