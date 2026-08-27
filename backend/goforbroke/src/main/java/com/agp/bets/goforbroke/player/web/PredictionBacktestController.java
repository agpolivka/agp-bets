package com.agp.bets.goforbroke.player.web;

import com.agp.bets.goforbroke.player.service.PredictionBacktestService;
import com.agp.bets.goforbroke.player.service.PredictionBacktestService.CalibrationRow;
import com.agp.bets.goforbroke.player.service.PredictionBacktestService.MarketLineBacktestSummary;
import com.agp.bets.goforbroke.player.service.PredictionBacktestService.OutcomeBacktestSummary;
import java.util.List;
import java.util.Map;
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

  // One-off data export for offline coefficient calibration (see CalibrationRow's doc) - not
  // meant for regular/UI consumption, just a way to pull real inputs into R for a real regression.
  @GetMapping("/calibration-export")
  public Map<String, List<CalibrationRow>> calibrationExport() {
    return predictionBacktestService.runCalibrationExport();
  }
}
