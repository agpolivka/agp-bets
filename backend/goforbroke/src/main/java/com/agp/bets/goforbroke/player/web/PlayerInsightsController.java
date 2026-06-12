package com.agp.bets.goforbroke.player.web;

import com.agp.bets.goforbroke.player.service.PlayerInsightsService;
import com.agp.bets.goforbroke.player.web.dto.PlayerInsightsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/players/{espnAthleteId}/insights")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class PlayerInsightsController {

  private final PlayerInsightsService playerInsightsService;

  @GetMapping
  public PlayerInsightsResponse getInsights(@PathVariable String espnAthleteId) {
    return playerInsightsService.getInsightsForAthleteId(espnAthleteId);
  }
}
