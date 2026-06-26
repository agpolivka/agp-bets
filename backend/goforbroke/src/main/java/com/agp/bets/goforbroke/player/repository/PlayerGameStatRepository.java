package com.agp.bets.goforbroke.player.repository;

import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerGameStatRepository extends JpaRepository<PlayerGameStat, Long> {

  List<PlayerGameStat> findAllByPlayer_IdOrderByGameDateDesc(Long playerId);

  Optional<PlayerGameStat> findFirstByPlayer_IdOrderByFetchedAtDesc(Long playerId);

  Optional<PlayerGameStat> findByPlayer_IdAndGameDate(Long playerId, LocalDate gameDate);

  void deleteAllByPlayer_Id(Long playerId);
}
