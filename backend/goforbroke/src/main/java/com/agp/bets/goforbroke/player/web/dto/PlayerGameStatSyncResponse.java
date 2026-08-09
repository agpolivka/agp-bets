package com.agp.bets.goforbroke.player.web.dto;

import java.time.Instant;

public record PlayerGameStatSyncResponse(
    String espnAthleteId, int gamesSynced, boolean backfillInProgress, Instant syncedAt) {}
