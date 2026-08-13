package com.agp.bets.goforbroke.odds.repository;

import com.agp.bets.goforbroke.odds.domain.PlayerPropLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerPropLineRepository extends JpaRepository<PlayerPropLine, Long> {

  List<PlayerPropLine> findByPlayerIsNull();

  // join fetch avoids N+1 lazy-loading on rawEvent.eventDate for every row - used by
  // PredictionBacktestService's market-line pass, which reads every matching row up front.
  @Query(
      "select l from PlayerPropLine l join fetch l.rawEvent where l.player is not null and l.marketRaw in :markets")
  List<PlayerPropLine> findByPlayerIsNotNullAndMarketRawIn(@Param("markets") List<String> markets);
}
