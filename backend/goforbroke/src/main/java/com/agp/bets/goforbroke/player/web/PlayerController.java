package com.agp.bets.goforbroke.player.web;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.service.EspnPlayerIngestionService;
import com.agp.bets.goforbroke.player.service.PlayerLeaderboardService;
import com.agp.bets.goforbroke.player.web.dto.PlayerCandidateResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerMetadataBackfillResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerResponse;
import com.agp.bets.goforbroke.player.web.dto.PlayerSyncResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/players")
@CrossOrigin(originPatterns = {"http://localhost:*", "http://127.0.0.1:*"})
@RequiredArgsConstructor
public class PlayerController {

  private final EspnPlayerIngestionService ingestionService;
  private final PlayerLeaderboardService leaderboardService;

  @GetMapping
  public List<PlayerResponse> listPlayers() {
    return ingestionService.listPlayers().stream().map(PlayerResponse::from).toList();
  }

  // Real, computed-from-live-projections leaderboard (see PlayerLeaderboardService's doc) - powers
  // the homepage "Featured players" spotlight. Cached server-side (30 min), so this is cheap to
  // call even though the underlying computation evaluates ~480 real candidates.
  @GetMapping("/leaderboard")
  public PlayerLeaderboardService.LeaderboardResponse leaderboard() {
    return leaderboardService.topProjectedPlayers();
  }

  @GetMapping("/{espnAthleteId}")
  public PlayerResponse getPlayer(@PathVariable String espnAthleteId) {
    return PlayerResponse.from(ingestionService.getPlayerByEspnAthleteId(espnAthleteId));
  }

  @GetMapping("/search")
  public List<PlayerCandidateResponse> searchByName(@RequestParam String name) {
    return ingestionService.findPlayerCandidatesByName(name).stream()
        .map(candidate -> PlayerCandidateResponse.from(candidate, ingestionService.isPlayerStored(candidate.espnAthleteId())))
        .toList();
  }

  @PostMapping("/sync/by-name")
  public PlayerSyncResponse syncByName(@RequestParam String name) {
    Player player = ingestionService.findOrLoadPlayerByName(name);
    return PlayerSyncResponse.from(player, "name", name);
  }

  @PostMapping("/sync/{espnAthleteId}")
  public PlayerSyncResponse syncByAthleteId(@PathVariable String espnAthleteId) {
    Player player = ingestionService.syncPlayerByEspnAthleteId(espnAthleteId);
    return PlayerSyncResponse.from(player, "espnAthleteId", espnAthleteId);
  }

  @PostMapping("/backfill-metadata")
  public PlayerMetadataBackfillResponse backfillMetadata() {
    return PlayerMetadataBackfillResponse.from(ingestionService.backfillPlayerMetadata());
  }
}
