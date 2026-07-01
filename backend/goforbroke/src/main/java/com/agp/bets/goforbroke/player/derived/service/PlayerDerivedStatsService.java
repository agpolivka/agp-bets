package com.agp.bets.goforbroke.player.derived.service;

import com.agp.bets.goforbroke.player.derived.model.PlayerDerivedSnapshot;

public interface PlayerDerivedStatsService {
  PlayerDerivedSnapshot buildSnapshot(String athleteId);
}
