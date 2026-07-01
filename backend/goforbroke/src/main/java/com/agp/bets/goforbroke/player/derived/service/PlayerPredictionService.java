package com.agp.bets.goforbroke.player.derived.service;

import com.agp.bets.goforbroke.player.derived.model.PlayerPrediction;

public interface PlayerPredictionService {
  PlayerPrediction buildPrediction(String athleteId);
}
