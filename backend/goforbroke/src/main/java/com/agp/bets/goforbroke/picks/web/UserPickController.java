package com.agp.bets.goforbroke.picks.web;

import com.agp.bets.goforbroke.picks.service.UserPickService;
import com.agp.bets.goforbroke.picks.web.dto.SubmitPicksRequest;
import com.agp.bets.goforbroke.picks.web.dto.WeekPicksResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/picks")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class UserPickController {

  private final UserPickService userPickService;

  @GetMapping("/current-week")
  public WeekPicksResponse currentWeek() {
    return userPickService.getCurrentWeek();
  }

  @PostMapping
  public WeekPicksResponse submitPicks(@RequestBody SubmitPicksRequest request) {
    return userPickService.submitPicks(request.picks());
  }
}
