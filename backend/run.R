library(plumber)

pr <- plumb("plumber.R")

pr$setDebug(FALSE)

pr$run(host = "0.0.0.0", port = as.integer(Sys.getenv("PORT", "8000")))
