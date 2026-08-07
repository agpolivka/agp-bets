package com.agp.bets.goforbroke.player.repository;

import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerGameStatRepository extends JpaRepository<PlayerGameStat, Long> {

  // season/week (not game_date) is the recency ordering: game_date is only populated once
  // nfl_schedules has been imported for that season, and Postgres sorts NULLs first on DESC, so
  // ordering by game_date would put un-schedule-matched older seasons ahead of real recent games.
  List<PlayerGameStat> findAllByPlayer_IdOrderBySeasonDescWeekDesc(Long playerId);

  Optional<PlayerGameStat> findFirstByPlayer_IdOrderByFetchedAtDesc(Long playerId);

  Optional<PlayerGameStat> findByPlayer_IdAndGameDate(Long playerId, LocalDate gameDate);

  void deleteAllByPlayer_Id(Long playerId);
}
