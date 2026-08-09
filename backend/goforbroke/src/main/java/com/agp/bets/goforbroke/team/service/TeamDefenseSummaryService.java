package com.agp.bets.goforbroke.team.service;

import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TeamDefenseSummaryService {

  // Roughly one season - see PlayerPredictionService.DEFENSE_HISTORY_GAME_WINDOW for why this
  // isn't unbounded: without a cap this blends every stored season together, so an old roster's
  // defense weighs the same as the current one.
  private static final int RECENT_GAME_WINDOW = 20;

  private final TeamRepository teamRepository;
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository;

  public TeamDefenseSummaryService(
      TeamRepository teamRepository,
      TeamDefenseGameStatRepository teamDefenseGameStatRepository) {
    this.teamRepository = teamRepository;
    this.teamDefenseGameStatRepository = teamDefenseGameStatRepository;
  }

  public Team getTeamByEspnId(String espnTeamId) {
    return teamRepository
        .findByEspnTeamId(espnTeamId)
        .orElseThrow(() -> new TeamNotFoundException("No stored team found for ESPN team " + espnTeamId));
  }

  public TeamDefenseSummary getDefenseSummary(String espnTeamId) {
    Team team = getTeamByEspnId(espnTeamId);
    List<TeamDefenseGameStat> stats =
        teamDefenseGameStatRepository.findAllByTeam_IdOrderByGameDateDesc(team.getId()).stream()
            .limit(RECENT_GAME_WINDOW)
            .toList();

    return new TeamDefenseSummary(
        stats.size(),
        average(stats, TeamDefenseGameStat::getPointsAllowed),
        average(stats, TeamDefenseGameStat::getTotalYardsAllowed),
        average(stats, TeamDefenseGameStat::getPassingYardsAllowed),
        average(stats, TeamDefenseGameStat::getReceivingYardsAllowed),
        average(stats, TeamDefenseGameStat::getRushingYardsAllowed),
        average(stats, TeamDefenseGameStat::getTurnoversForced),
        average(stats, TeamDefenseGameStat::getInterceptions),
        average(stats, TeamDefenseGameStat::getFumbleRecoveries),
        average(stats, TeamDefenseGameStat::getSacks));
  }

  private double average(List<TeamDefenseGameStat> stats, Function<TeamDefenseGameStat, Integer> accessor) {
    if (stats.isEmpty()) {
      return 0.0d;
    }

    int total = 0;
    for (TeamDefenseGameStat stat : stats) {
      Integer value = accessor.apply(stat);
      if (value != null) {
        total += value;
      }
    }

    return (double) total / stats.size();
  }
}
