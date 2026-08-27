package com.agp.bets.goforbroke.player.service;

import java.time.Instant;

/**
 * One player's current weekly game-status entry from {@link EspnInjuryClient}'s league-wide feed
 * (e.g. {@code status = "Questionable"}), sourced fresh on every {@link
 * InjuryStatusRefreshWorker} run. {@code reportedAt} is ESPN's own timestamp for when this
 * specific status was last updated, not when this app polled for it.
 */
record InjuryReport(
    String espnAthleteId, String status, String shortComment, String longComment, Instant reportedAt) {}
