package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlayerUpsertService {

  private final PlayerRepository playerRepository;
  private final Clock clock;

  @Autowired
  public PlayerUpsertService(PlayerRepository playerRepository) {
    this(playerRepository, Clock.systemUTC());
  }

  PlayerUpsertService(PlayerRepository playerRepository, Clock clock) {
    this.playerRepository = playerRepository;
    this.clock = clock;
  }

  public Player upsertAthlete(JsonNode athleteNode, String sourceUrl) {
    Instant now = clock.instant();
    PlayerSnapshot snapshot = EspnAthleteMapper.toSnapshot(athleteNode, sourceUrl, now);

    Player player =
        playerRepository
            .findByEspnAthleteId(snapshot.espnAthleteId())
            .orElseGet(Player::new);

    boolean isNew = player.getId() == null;

    player.setEspnAthleteId(snapshot.espnAthleteId());
    player.setDisplayName(snapshot.displayName());
    player.setFirstName(snapshot.firstName());
    player.setLastName(snapshot.lastName());
    player.setPosition(snapshot.position());
    player.setJerseyNumber(snapshot.jerseyNumber());
    player.setTeamName(snapshot.teamName());
    player.setTeamId(snapshot.teamId());
    player.setActive(snapshot.active());
    player.setSourceUrl(snapshot.sourceUrl());
    player.setRawPayload(snapshot.rawPayload());
    player.setFetchedAt(snapshot.fetchedAt());
    player.setCreatedAt(isNew ? now : player.getCreatedAt());
    player.setUpdatedAt(now);

    return playerRepository.save(player);
  }
}
