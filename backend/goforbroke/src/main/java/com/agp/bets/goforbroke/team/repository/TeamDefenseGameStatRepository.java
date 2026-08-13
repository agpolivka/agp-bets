package com.agp.bets.goforbroke.team.repository;

import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamDefenseGameStatRepository extends JpaRepository<TeamDefenseGameStat, Long> {

  Optional<TeamDefenseGameStat> findByTeam_IdAndGameDate(Long teamId, LocalDate gameDate);

  List<TeamDefenseGameStat> findAllByTeam_IdOrderByGameDateDesc(Long teamId);

  // Used for the league-wide baseline (PlayerPredictionService.leagueDefenseAverages) so old
  // seasons age out naturally instead of blending into "current" league averages forever.
  List<TeamDefenseGameStat> findAllByGameDateAfter(LocalDate cutoff);

  // Historical-date twin of the above, used by PredictionBacktestService to compute league
  // averages as of a past date instead of "now".
  List<TeamDefenseGameStat> findAllByGameDateBetween(LocalDate start, LocalDate end);
}
