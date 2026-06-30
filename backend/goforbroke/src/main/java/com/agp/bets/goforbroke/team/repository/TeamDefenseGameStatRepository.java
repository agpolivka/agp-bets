package com.agp.bets.goforbroke.team.repository;

import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamDefenseGameStatRepository extends JpaRepository<TeamDefenseGameStat, Long> {

  Optional<TeamDefenseGameStat> findByTeam_IdAndGameDate(Long teamId, LocalDate gameDate);

  List<TeamDefenseGameStat> findAllByTeam_IdOrderByGameDateDesc(Long teamId);
}
