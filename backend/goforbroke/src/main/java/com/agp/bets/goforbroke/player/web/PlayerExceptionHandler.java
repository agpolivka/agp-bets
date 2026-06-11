package com.agp.bets.goforbroke.player.web;

import com.agp.bets.goforbroke.player.service.EspnLookupException;
import com.agp.bets.goforbroke.player.service.PlayerNotFoundException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PlayerExceptionHandler {

  @ExceptionHandler(PlayerNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handlePlayerNotFound(PlayerNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            Map.of(
                "error", "player_not_found",
                "message", exception.getMessage(),
                "timestamp", Instant.now().toString()));
  }

  @ExceptionHandler(EspnLookupException.class)
  public ResponseEntity<Map<String, Object>> handleEspnLookupFailure(EspnLookupException exception) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(
            Map.of(
                "error", "espn_lookup_failed",
                "message", exception.getMessage(),
                "timestamp", Instant.now().toString()));
  }
}
