package com.agp.bets.goforbroke.team.web;

import com.agp.bets.goforbroke.team.service.TeamNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = TeamController.class)
public class TeamExceptionHandler {

  @ExceptionHandler(TeamNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, String> handleTeamNotFound(TeamNotFoundException exception) {
    return Map.of("message", exception.getMessage());
  }
}
