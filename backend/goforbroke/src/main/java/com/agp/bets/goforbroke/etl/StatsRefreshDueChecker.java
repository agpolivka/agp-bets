package com.agp.bets.goforbroke.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides whether the stored-players stats refresh (etl/r/refresh_stored_players_weekly.R) is
 * due, instead of assuming a fixed weekly cadence. A refresh is due when {@code nfl_schedules}
 * has a completed game (gameday before today) dated on or after the last successful refresh
 * recorded in {@code etl_import_runs} - this covers Thursday/Saturday/Sunday/Monday games
 * uniformly without hardcoding game days, and needs the caller to just poll this periodically.
 *
 * <p>Both tables are created by the R ETL scripts (not Hibernate), so a fresh database that has
 * never run an R job won't have them yet; that's treated as "nothing to refresh yet", not an
 * error.
 */
@Component
public class StatsRefreshDueChecker {

  private static final Logger log = LoggerFactory.getLogger(StatsRefreshDueChecker.class);
  private static final String REFRESH_JOB_NAME = "refresh_stored_players_weekly";

  private static final String IS_DUE_SQL =
      """
      select exists (
        select 1
        from nfl_schedules s
        where s.gameday is not null
          and s.gameday < current_date
          and s.gameday >= coalesce(
            (select max(finished_at)::date from etl_import_runs where job_name = ?),
            date '1970-01-01'
          )
      )
      """;

  private final JdbcTemplate jdbcTemplate;

  public StatsRefreshDueChecker(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean isRefreshDue() {
    try {
      Boolean due = jdbcTemplate.queryForObject(IS_DUE_SQL, Boolean.class, REFRESH_JOB_NAME);
      return Boolean.TRUE.equals(due);
    } catch (DataAccessException exception) {
      log.debug(
          "Could not evaluate stats refresh due-check (nfl_schedules/etl_import_runs may not exist yet): {}",
          exception.getMessage());
      return false;
    }
  }
}
