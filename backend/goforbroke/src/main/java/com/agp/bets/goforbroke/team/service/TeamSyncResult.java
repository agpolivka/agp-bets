package com.agp.bets.goforbroke.team.service;

import java.time.Instant;
import java.util.List;

public record TeamSyncResult(
    int teamsRequested,
    int teamsSynced,
    int defensiveGamesSynced,
    List<String> failedTeamIds,
    Instant syncedAt) {}
