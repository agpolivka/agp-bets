package com.agp.bets.goforbroke.etl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs an ETL script from {@code etl/r} as a child process (e.g. {@code Rscript some_job.R}).
 *
 * <p>Each script loads a full nflverse season into memory (several hundred MB), so a {@link
 * Semaphore} caps how many run at once - without it, e.g. several concurrent new-player
 * backfills each spawn their own heavy R process and compete for memory/CPU, making all of them
 * slow instead of one at a time being fast.
 */
@Component
public class RScriptRunner {

  private static final Logger log = LoggerFactory.getLogger(RScriptRunner.class);
  private static final Duration TIMEOUT = Duration.ofMinutes(15);

  private final String rExecutable;
  private final Path scriptsDir;
  private final Semaphore concurrencyLimit;

  public RScriptRunner(
      @Value("${agp.etl.r-executable:Rscript}") String rExecutable,
      @Value("${agp.etl.scripts-dir:../../etl/r}") String scriptsDir,
      @Value("${agp.etl.max-concurrent-scripts:1}") int maxConcurrentScripts) {
    this.rExecutable = rExecutable;
    this.scriptsDir = Path.of(scriptsDir);
    this.concurrencyLimit = new Semaphore(maxConcurrentScripts);
  }

  public boolean run(String scriptName, String... scriptArgs) {
    Path scriptPath = scriptsDir.resolve(scriptName);
    List<String> command = new ArrayList<>();
    command.add(rExecutable);
    command.add(scriptPath.toString());
    command.addAll(List.of(scriptArgs));

    try {
      concurrencyLimit.acquire();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.error("Interrupted while waiting for a free ETL script slot for {}", scriptName, exception);
      return false;
    }

    try {
      log.info("Running ETL script: {}", command);
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes());

      if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        log.error("ETL script {} timed out after {}", scriptName, TIMEOUT);
        return false;
      }

      if (process.exitValue() != 0) {
        log.error("ETL script {} failed with exit code {}. Output:\n{}", scriptName, process.exitValue(), output);
        return false;
      }

      log.info("ETL script {} completed:\n{}", scriptName, output);
      return true;
    } catch (IOException exception) {
      log.error("Failed to start ETL script {}", scriptName, exception);
      return false;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.error("Interrupted while waiting for ETL script {}", scriptName, exception);
      return false;
    } finally {
      concurrencyLimit.release();
    }
  }
}
