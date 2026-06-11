package com.agp.bets.goforbroke.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

  @GetMapping("/")
  public Map<String, Object> home() {
    return Map.of(
        "service", "goforbroke",
        "status", "up",
        "frontend", "http://localhost:5173",
        "api", "/api/players");
  }
}
