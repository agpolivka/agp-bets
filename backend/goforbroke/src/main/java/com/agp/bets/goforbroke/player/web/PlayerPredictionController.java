package com.agp.bets.goforbroke.player.web;

import com.agp.bets.goforbroke.player.service.PlayerPredictionService;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/players/{espnAthleteId}/predictions")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class PlayerPredictionController {

  private final PlayerPredictionService playerPredictionService;

  @GetMapping
  public PlayerPredictionResponse getPrediction(@PathVariable String espnAthleteId) {
    return playerPredictionService.getPredictionForAthleteId(espnAthleteId);
  }
}
