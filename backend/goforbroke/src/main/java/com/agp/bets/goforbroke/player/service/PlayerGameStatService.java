package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlayerGameStatService {

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final PlayerHistoryBackfillService playerHistoryBackfillService;

  public PlayerGameStatService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      PlayerHistoryBackfillService playerHistoryBackfillService) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.playerHistoryBackfillService = playerHistoryBackfillService;
  }

  @Transactional(readOnly = true)
  public List<PlayerGameStat> listStatsForAthleteId(String athleteId) {
    Player player = getPlayerByAthleteId(athleteId);
    return playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(player.getId());
  }

  public List<PlayerGameStat> syncStatsForAthleteId(String athleteId) {
    Player player = getPlayerByAthleteId(athleteId);
    List<PlayerGameStat> existingStats =
        playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(player.getId());

    if (existingStats.isEmpty()) {
      playerHistoryBackfillService.requestBackfillIfNeeded(athleteId);
    }

    return existingStats;
  }

  public boolean isBackfillInProgress(String athleteId) {
    return playerHistoryBackfillService.isBackfillInProgress(athleteId);
  }

  private Player getPlayerByAthleteId(String athleteId) {
    return playerRepository
        .findByEspnAthleteId(athleteId)
        .orElseThrow(
            () -> new PlayerNotFoundException("No stored player found for ESPN athlete " + athleteId));
  }
}
