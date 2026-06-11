package com.agp.bets.goforbroke.player.service;

public class EspnLookupException extends RuntimeException {

  public EspnLookupException(String message) {
    super(message);
  }

  public EspnLookupException(String message, Throwable cause) {
    super(message, cause);
  }
}
