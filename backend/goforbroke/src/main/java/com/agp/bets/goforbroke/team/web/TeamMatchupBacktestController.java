package com.agp.bets.goforbroke.team.web;

import com.agp.bets.goforbroke.team.service.TeamMatchupBacktestService;
import com.agp.bets.goforbroke.team.service.TeamMatchupBacktestService.SpreadBacktestSummary;
import com.agp.bets.goforbroke.team.service.TeamMatchupBacktestService.StyleCalibrationRow;
import com.agp.bets.goforbroke.team.service.TeamMatchupBacktestService.TeamMatchupBacktestSummary;
import com.agp.bets.goforbroke.team.service.TeamMatchupBacktestService.TotalsBacktestSummary;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backtest/team-matchups")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class TeamMatchupBacktestController {

  private final TeamMatchupBacktestService teamMatchupBacktestService;

  @GetMapping
  public TeamMatchupBacktestSummary results() {
    return teamMatchupBacktestService.runBacktest();
  }

  @GetMapping("/totals")
  public TotalsBacktestSummary totals() {
    return teamMatchupBacktestService.runTotalsBacktest();
  }

  @GetMapping("/spread")
  public SpreadBacktestSummary spread() {
    return teamMatchupBacktestService.runSpreadBacktest();
  }

  @GetMapping("/style-calibration")
  public List<StyleCalibrationRow> styleCalibration() {
    return teamMatchupBacktestService.runStyleCalibrationExport();
  }
}
