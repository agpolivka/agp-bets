package com.agp.bets.goforbroke.team.repository;

import com.agp.bets.goforbroke.team.domain.TeamStrengthRating;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamStrengthRatingRepository extends JpaRepository<TeamStrengthRating, Long> {

  List<TeamStrengthRating> findAllByTeam_IdOrderByGameDateDesc(Long teamId);
}
