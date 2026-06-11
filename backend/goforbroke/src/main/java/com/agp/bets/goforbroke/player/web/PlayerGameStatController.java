package com.agp.bets.goforbroke.player.web;

import com.agp.bets.goforbroke.player.service.PlayerGameStatService;
import com.agp.bets.goforbroke.player.web.dto.PlayerGameStatResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerGameStatSyncResponse;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/players/{espnAthleteId}/stats")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class PlayerGameStatController {

  private final PlayerGameStatService playerGameStatService;
  private final Clock clock = Clock.systemUTC();

  @GetMapping
  public List<PlayerGameStatResponse> listStats(@PathVariable String espnAthleteId) {
    return playerGameStatService.listStatsForAthleteId(espnAthleteId).stream()
        .map(PlayerGameStatResponse::from)
        .toList();
  }

  @PostMapping("/sync")
  public PlayerGameStatSyncResponse syncStats(@PathVariable String espnAthleteId) {
    int syncedCount = playerGameStatService.syncStatsForAthleteId(espnAthleteId).size();
    return new PlayerGameStatSyncResponse(espnAthleteId, syncedCount, clock.instant());
  }
}
