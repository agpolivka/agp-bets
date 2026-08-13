package com.agp.bets.goforbroke.player.web;

import com.agp.bets.goforbroke.player.service.PredictionBacktestService;
import com.agp.bets.goforbroke.player.service.PredictionBacktestService.MarketLineBacktestSummary;
import com.agp.bets.goforbroke.player.service.PredictionBacktestService.OutcomeBacktestSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backtest")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class PredictionBacktestController {

  private final PredictionBacktestService predictionBacktestService;

  @GetMapping("/outcomes")
  public OutcomeBacktestSummary outcomes() {
    return predictionBacktestService.runOutcomeBacktest();
  }

  @GetMapping("/market-lines")
  public MarketLineBacktestSummary marketLines() {
    return predictionBacktestService.runMarketLineBacktest();
  }
}
