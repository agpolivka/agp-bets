package com.agp.bets.goforbroke.picks.web.dto;

import java.util.List;

public record WeekPicksResponse(
    Integer season, String gameType, Integer week, List<PickedGameResponse> games, PickAccuracyResponse accuracy) {}
