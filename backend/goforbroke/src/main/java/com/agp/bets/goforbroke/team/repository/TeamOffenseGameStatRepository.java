package com.agp.bets.goforbroke.team.repository;

import com.agp.bets.goforbroke.team.domain.TeamOffenseGameStat;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamOffenseGameStatRepository extends JpaRepository<TeamOffenseGameStat, Long> {

  Optional<TeamOffenseGameStat> findByTeam_IdAndGameDate(Long teamId, LocalDate gameDate);

  List<TeamOffenseGameStat> findAllByTeam_IdOrderByGameDateDesc(Long teamId);
}
