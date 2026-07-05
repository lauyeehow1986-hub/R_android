#!/usr/bin/env Rscript
# Runs the backend integration suite. `test_dir` sources helper-server.R before
# the test files and exits non-zero on any failure (for CI).
library(testthat)
testthat::test_dir("backend/tests", stop_on_failure = TRUE)
