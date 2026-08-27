package com.agp.bets.goforbroke.team.web;

import com.agp.bets.goforbroke.team.service.UpcomingTeamMatchupService;
import com.agp.bets.goforbroke.team.web.dto.UpcomingMatchupResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/team-matchups")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class TeamMatchupController {

  private final UpcomingTeamMatchupService upcomingTeamMatchupService;

  @GetMapping("/upcoming")
  public List<UpcomingMatchupResponse> upcoming() {
    return upcomingTeamMatchupService.upcomingMatchups();
  }

  // Full-season view (all weeks + playoffs), for admin/internal use - no auth gate yet, since this
  // app has no admin-role infrastructure at all right now (Priority 4's admin view is unbuilt, and
  // the login/premium-access placeholder UI was deliberately removed, not replaced). Kept as its
  // own path so real gating can be added here later without touching the scoped public endpoint.
  @GetMapping("/upcoming/all")
  public List<UpcomingMatchupResponse> upcomingAll() {
    return upcomingTeamMatchupService.allUpcomingMatchups();
  }
}
