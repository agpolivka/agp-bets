package com.agp.bets.goforbroke.picks.web.dto;

import java.util.List;

public record SubmitPicksRequest(List<PickSubmission> picks) {

  public record PickSubmission(String gameId, String pickedTeamAbbreviation) {}
}
