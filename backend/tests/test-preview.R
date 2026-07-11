test_that("preview of an uploaded CSV returns a table", {
  server <- local_server()
  csv <- tempfile(fileext = ".csv")
  writeLines(c("x,y", "1,2", "3,4"), csv)
  up <- post_upload(server, csv, session_id = "s1")

  pv <- post_preview(server, "file", up$body$name, session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$error)
  expect_equal(unlist(pv$body$table$columns), c("x", "y"))
  expect_equal(length(pv$body$table$rows), 2)
  expect_equal(pv$body$table$totalRows, 2)
})

test_that("preview of an uploaded RDS data frame returns a table", {
  server <- local_server()
  rds <- tempfile(fileext = ".rds")
  saveRDS(data.frame(a = 1:3, b = c("p", "q", "r")), rds)
  up <- post_upload(server, rds, session_id = "s1")

  pv <- post_preview(server, "file", up$body$name, session_id = "s1")
  expect_equal(pv$status, 200)
  expect_equal(unlist(pv$body$table$columns), c("a", "b"))
  expect_equal(length(pv$body$table$rows), 3)
})

test_that("preview of a workspace object returns a table and does not change state", {
  server <- local_server()
  run <- post_execute(server, "df <- data.frame(n = 1:5)", session_id = "s1")
  expect_equal(run$status, 200)
  before <- sort(unlist(run$body$workspaceObjects))

  pv <- post_preview(server, "object", "df", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_equal(unlist(pv$body$table$columns), "n")
  expect_equal(pv$body$table$totalRows, 5)

  after <- post_execute(server, "cat(ls())", session_id = "s1")
  expect_equal(sort(unlist(after$body$workspaceObjects)), before)
})

test_that("preview of a non-tabular object reports an error, no table", {
  server <- local_server()
  post_execute(server, "v <- 1:10", session_id = "s1")

  pv <- post_preview(server, "object", "v", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$table)
  expect_true(nchar(pv$body$error) > 0)
})

test_that("preview of a missing file reports an error", {
  server <- local_server()
  pv <- post_preview(server, "file", "nope.csv", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$table)
  expect_true(nchar(pv$body$error) > 0)
})

test_that("preview of a missing object reports an error", {
  server <- local_server()
  pv <- post_preview(server, "object", "ghost", session_id = "s1")
  expect_equal(pv$status, 200)
  expect_null(pv$body$table)
  expect_true(nchar(pv$body$error) > 0)
})

test_that("preview rejects a bad source with 400", {
  server <- local_server()
  pv <- post_preview(server, "bogus", "df", session_id = "s1")
  expect_equal(pv$status, 400)
})

test_that("preview rejects a bad object name with 400", {
  server <- local_server()
  pv <- post_preview(server, "object", "no spaces!", session_id = "s1")
  expect_equal(pv$status, 400)
})

test_that("preview caps rows and reports truncation", {
  server <- local_server(env = list(R_TABLE_MAX_ROWS = "3"))
  csv <- tempfile(fileext = ".csv")
  writeLines(c("x", "1", "2", "3", "4", "5"), csv)
  up <- post_upload(server, csv, session_id = "s1")

  pv <- post_preview(server, "file", up$body$name, session_id = "s1")
  expect_equal(pv$status, 200)
  expect_equal(length(pv$body$table$rows), 3)
  expect_equal(pv$body$table$totalRows, 5)
  expect_true(pv$body$truncated)
})
