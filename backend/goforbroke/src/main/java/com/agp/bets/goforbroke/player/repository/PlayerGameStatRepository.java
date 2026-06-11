package com.agp.bets.goforbroke.player.repository;

import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerGameStatRepository extends JpaRepository<PlayerGameStat, Long> {

  List<PlayerGameStat> findAllByPlayer_IdOrderByGameDateDesc(Long playerId);

  void deleteAllByPlayer_Id(Long playerId);
}
