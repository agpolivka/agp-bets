package com.agp.bets.goforbroke.odds.web;

import com.agp.bets.goforbroke.odds.service.PlayerPropOddsIngestionService;
import com.agp.bets.goforbroke.odds.service.PlayerPropOddsIngestionService.RecrosswalkResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/odds")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class OddsController {

  private final PlayerPropOddsIngestionService ingestionService;

  @PostMapping("/recrosswalk")
  public RecrosswalkResult recrosswalk() {
    return ingestionService.recrosswalkUnmatchedLines();
  }
}
