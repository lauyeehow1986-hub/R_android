test_that("printing a data frame emits a table", {
  srv <- local_server()
  res <- post_execute(srv, "print(head(iris))")
  tbls <- res$body$tables
  expect_length(tbls, 1)
  expect_equal(unlist(tbls[[1]]$columns),
               c("Sepal.Length", "Sepal.Width", "Petal.Length", "Petal.Width", "Species"))
  expect_equal(unlist(tbls[[1]]$columnTypes)[[5]], "factor")
  expect_equal(unlist(tbls[[1]]$columnTypes)[[1]], "numeric")
  expect_equal(tbls[[1]]$totalRows, 6)
  expect_length(tbls[[1]]$rows, 6)
})

test_that("a 1x1 data frame round-trips as arrays (unboxing guard)", {
  srv <- local_server()
  tbls <- post_execute(srv, "data.frame(x = 1)")$body$tables
  expect_equal(unlist(tbls[[1]]$columns), "x")
  expect_equal(unlist(tbls[[1]]$rows[[1]]), "1")
})

test_that("rows are capped at R_TABLE_MAX_ROWS but totalRows is exact", {
  srv <- local_server(env = list(R_TABLE_MAX_ROWS = "3"))
  tbls <- post_execute(srv, "data.frame(n = 1:10)")$body$tables
  expect_length(tbls[[1]]$rows, 3)
  expect_equal(tbls[[1]]$totalRows, 10)
})

test_that("a 2-D table object (summary) renders as a grid", {
  srv <- local_server()
  # summary(cars) is a 2-D `table`; as.data.frame.matrix keeps its grid layout.
  tbls <- post_execute(srv, "summary(cars)")$body$tables
  expect_length(tbls, 1)
  expect_true(length(tbls[[1]]$columns) >= 2)
})

test_that("no printed data frame means no tables", {
  srv <- local_server()
  res <- post_execute(srv, "x <- 1; cat('hi')")
  expect_length(res$body$tables, 0)
  expect_equal(res$body$stdout, "hi")
})
