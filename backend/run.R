library(plumber)

pr <- plumb("plumber.R")

# Emit unboxed JSON: length-1 vectors serialize as scalars (matching the app's
# Kotlin models, where stdout/error/timedOut are scalars, not arrays) and R NULL
# becomes JSON null. Lists (plots, workspaceObjects) still serialize as arrays.
pr$setSerializer(plumber::serializer_json(auto_unbox = TRUE, null = "null", na = "null"))

pr$setDebug(FALSE)

pr$run(host = "0.0.0.0", port = as.integer(Sys.getenv("PORT", "8000")))
