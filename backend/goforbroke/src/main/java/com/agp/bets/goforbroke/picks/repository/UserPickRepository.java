package com.agp.bets.goforbroke.picks.repository;

import com.agp.bets.goforbroke.picks.domain.UserPick;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPickRepository extends JpaRepository<UserPick, Long> {

  Optional<UserPick> findByGameId(String gameId);

  List<UserPick> findAllByGameIdIn(List<String> gameIds);
}
