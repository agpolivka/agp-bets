package com.agp.bets.goforbroke.player.repository;

import com.agp.bets.goforbroke.player.domain.Player;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface PlayerRepository extends JpaRepository<Player, Long> {

  Optional<Player> findByEspnAthleteId(String espnAthleteId);

  Optional<Player> findFirstByDisplayNameIgnoreCase(String displayName);

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

  // Real candidate pool for PlayerLeaderboardService - active roster players at a given position
  // with a real, non-thin game sample on record (minGames matches RECENT_GAME_WINDOW's default, an
  // already-established threshold elsewhere in this app, not a new arbitrary number). Filters out
  // practice-squad/inactive/just-signed players whose single-digit-game prediction would be mostly
  // noise anyway - same spirit as THIN_SAMPLE_GAME_THRESHOLD's cache-TTL distinction.
  @Query(
      """
      select p
      from Player p
      where p.position = :position
        and p.active = true
        and (select count(g) from PlayerGameStat g where g.player = p) >= :minGames
      """)
  List<Player> findActiveCandidatesByPosition(@Param("position") String position, @Param("minGames") long minGames);
}
