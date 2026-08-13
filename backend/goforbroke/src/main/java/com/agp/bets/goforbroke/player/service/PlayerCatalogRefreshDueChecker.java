package com.agp.bets.goforbroke.player.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides whether the full-catalog player refresh (etl/r/import_players.R) is due. Unlike {@code
 * StatsRefreshDueChecker} (tied to a completed game in nfl_schedules), roster/player changes
 * happen on any day - trades and signings aren't tied to game completion, and this needs to work
 * during the offseason/preseason too - so "due" here is simple time-based staleness against the
 * last successful run recorded in {@code etl_import_runs}.
 *
 * <p>That table is created by the R ETL scripts (not Hibernate), so a database that has never run
 * any R job won't have it yet; that's treated as "nothing to refresh yet", not an error, matching
 * {@code StatsRefreshDueChecker}'s handling of the same situation.
 */
@Component
public class PlayerCatalogRefreshDueChecker {

  private static final Logger log = LoggerFactory.getLogger(PlayerCatalogRefreshDueChecker.class);
  private static final String JOB_NAME = "import_players";

  private static final String IS_DUE_SQL =
      """
      select coalesce(
        (select max(finished_at) from etl_import_runs where job_name = ?),
        timestamp '1970-01-01'
      ) < ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final Duration staleAfter;

  public PlayerCatalogRefreshDueChecker(
      JdbcTemplate jdbcTemplate,
      @Value("${agp.player-catalog-refresh.stale-after-hours:24}") long staleAfterHours) {
    this.jdbcTemplate = jdbcTemplate;
    this.staleAfter = Duration.ofHours(staleAfterHours);
  }

  public boolean isRefreshDue() {
    try {
      Timestamp cutoff = Timestamp.from(Instant.now().minus(staleAfter));
      Boolean due = jdbcTemplate.queryForObject(IS_DUE_SQL, Boolean.class, JOB_NAME, cutoff);
      return Boolean.TRUE.equals(due);
    } catch (DataAccessException exception) {
      log.debug(
          "Could not evaluate player catalog refresh due-check (etl_import_runs may not exist yet): {}",
          exception.getMessage());
      return false;
    }
  }
}
