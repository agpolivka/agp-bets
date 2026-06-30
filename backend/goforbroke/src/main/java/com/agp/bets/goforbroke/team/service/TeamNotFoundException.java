package com.agp.bets.goforbroke.team.service;

public class TeamNotFoundException extends RuntimeException {

  public TeamNotFoundException(String message) {
    super(message);
  }
}
