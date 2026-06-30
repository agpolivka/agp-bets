package com.agp.bets.goforbroke.team.repository;

import com.agp.bets.goforbroke.team.domain.Team;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {

  Optional<Team> findByEspnTeamId(String espnTeamId);

  List<Team> findAllByOrderByDisplayNameAsc();
}
