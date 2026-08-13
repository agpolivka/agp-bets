package com.agp.bets.goforbroke.odds.repository;

import com.agp.bets.goforbroke.odds.domain.PropOddsRawEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropOddsRawEventRepository extends JpaRepository<PropOddsRawEvent, Long> {

  boolean existsByProviderAndExternalEventIdAndBookmaker(
      String provider, String externalEventId, String bookmaker);
}
