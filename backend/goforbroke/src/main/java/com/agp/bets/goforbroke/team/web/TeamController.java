package com.agp.bets.goforbroke.team.web;

import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.service.TeamDefenseSummaryService;
import com.agp.bets.goforbroke.team.service.TeamSyncService;
import com.agp.bets.goforbroke.team.web.dto.TeamBulkSyncResponse;
import com.agp.bets.goforbroke.team.web.dto.TeamDefenseSummaryResponse;
import com.agp.bets.goforbroke.team.web.dto.TeamResponse;
import com.agp.bets.goforbroke.team.web.dto.TeamSyncResponse;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teams")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class TeamController {

  private final TeamSyncService teamSyncService;
  private final TeamDefenseSummaryService teamDefenseSummaryService;
  private final Clock clock = Clock.systemUTC();

  @GetMapping("/{espnTeamId}")
  public TeamResponse getTeam(@PathVariable String espnTeamId) {
    return TeamResponse.from(teamDefenseSummaryService.getTeamByEspnId(espnTeamId));
  }

  @GetMapping("/{espnTeamId}/defense-summary")
  public TeamDefenseSummaryResponse getDefenseSummary(@PathVariable String espnTeamId) {
    return TeamDefenseSummaryResponse.from(teamDefenseSummaryService.getDefenseSummary(espnTeamId));
  }

  @PostMapping("/sync/{espnTeamId}")
  public TeamSyncResponse syncTeam(@PathVariable String espnTeamId) {
    Team team = teamSyncService.syncTeamById(espnTeamId);
    return TeamSyncResponse.from(team, "espnTeamId", espnTeamId, clock.instant());
  }

  @PostMapping("/sync-linked-player-teams")
  public TeamBulkSyncResponse syncLinkedPlayerTeams() {
    return TeamBulkSyncResponse.from(teamSyncService.syncReferencedTeams());
  }
}
