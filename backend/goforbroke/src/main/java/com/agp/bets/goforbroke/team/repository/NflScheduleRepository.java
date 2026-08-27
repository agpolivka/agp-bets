package com.agp.bets.goforbroke.team.repository;

import com.agp.bets.goforbroke.team.domain.NflSchedule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NflScheduleRepository extends JpaRepository<NflSchedule, String> {

  // homeScore is null for a game that hasn't been played yet - nflverse's schedules dataset
  // doesn't include preseason at all (confirmed directly: no "PRE" game_type ever appears in
  // stored data), so filtering to REG/WC/DIV/CON/SB game types is enough to exclude preseason
  // without any extra logic.
  List<NflSchedule> findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(List<String> gameTypes);
}
