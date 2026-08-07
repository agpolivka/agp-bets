script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

run_job <- function(job_name, handler) {
  log_info("Starting ETL job: ", job_name)
  result <- handler()
  log_info("Finished ETL job: ", job_name)
  invisible(result)
}
