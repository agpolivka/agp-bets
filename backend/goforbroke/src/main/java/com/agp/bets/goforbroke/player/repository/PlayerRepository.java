package com.agp.bets.goforbroke.player.repository;

import com.agp.bets.goforbroke.player.domain.Player;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

  Optional<Player> findByEspnAthleteId(String espnAthleteId);

  Optional<Player> findFirstByDisplayNameIgnoreCase(String displayName);

  List<Player> findAllByOrderByDisplayNameAsc();
}
