package com.agp.bets.goforbroke.player.service;

public class PlayerNotFoundException extends RuntimeException {

  public PlayerNotFoundException(String message) {
    super(message);
  }
}
