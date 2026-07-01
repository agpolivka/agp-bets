package com.agp.bets.goforbroke.player.repository;

import com.agp.bets.goforbroke.player.domain.Player;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

  Optional<Player> findByEspnAthleteId(String espnAthleteId);

  Optional<Player> findFirstByDisplayNameIgnoreCase(String displayName);

  @Query(
      """
      select p
      from Player p
      where lower(p.displayName) like lower(concat('%', :query, '%'))
         or lower(coalesce(p.firstName, '')) like lower(concat('%', :query, '%'))
         or lower(coalesce(p.lastName, '')) like lower(concat('%', :query, '%'))
      order by
        case when lower(p.displayName) = lower(:query) then 0 else 1 end,
        p.displayName asc
      """)
  List<Player> searchByNameFragment(String query);

  List<Player> findAllByOrderByDisplayNameAsc();

  @Query(
      """
      select distinct p.teamId
      from Player p
      where p.teamId is not null
        and p.teamId <> ''
      order by p.teamId asc
      """)
  List<String> findDistinctTeamIds();

  @Query(
      """
      select p
      from Player p
      where p.espnAthleteId is not null
        and (
          p.position is null
          or p.position = ''
          or p.teamName is null
          or p.teamName = ''
          or p.teamId is null
          or p.teamId = ''
          or p.active is null
        )
      order by p.displayName asc
      """)
  List<Player> findPlayersNeedingMetadataBackfill();
}
