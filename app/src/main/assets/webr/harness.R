local({
  .maxrows <- 200
  .PLOT_MARKER <- paste0(intToUtf8(2), "RMOBILE:PLOT", intToUtf8(3))
  .TABLE_MARKER <- paste0(intToUtf8(2), "RMOBILE:TABLE", intToUtf8(3))
  setHook("plot.new", function(...) cat(.PLOT_MARKER), action = "replace")
  setHook("grid.newpage", function(...) cat(.PLOT_MARKER), action = "replace")
  .emit <- function(x) {
    df <- if (is.data.frame(x)) x else as.data.frame.matrix(x, stringsAsFactors = FALSE)
    n <- nrow(df); sub <- utils::head(df, .maxrows)
    types <- vapply(df, function(cc) class(cc)[1], character(1))
    cells <- lapply(sub, function(col) if (is.list(col)) vapply(col, function(v) paste(format(v), collapse = ", "), character(1)) else format(col, trim = TRUE))
    cols <- names(df)
    rn <- rownames(sub)
    if (!identical(rn, as.character(seq_len(nrow(sub))))) { cells <- c(list(rn), cells); cols <- c("", cols); types <- c("", types) }
    rowsOut <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(cc) as.character(cc[i]), character(1))))
    obj <- list(columns = as.character(cols), columnTypes = as.character(types), rows = rowsOut, totalRows = jsonlite::unbox(as.integer(n)))
    idx <- length(list.files(".", pattern = "^table[0-9]+\\.json$")) + 1L
    writeLines(jsonlite::toJSON(obj, auto_unbox = FALSE), sprintf("table%03d.json", idx))
  }
  .tabular <- function(v) is.data.frame(v) || ((is.matrix(v) || inherits(v, "table")) && length(dim(v)) == 2)
  .is_print <- function(e) is.call(e) && is.symbol(e[[1]]) && identical(as.character(e[[1]]), "print")
  .exec <- function(exprs) for (e in exprs) { pr <- .is_print(e); r <- withVisible(eval(e, globalenv())); if (r$visible) print(r$value); if ((r$visible || pr) && .tabular(r$value)) { try(.emit(r$value), silent = TRUE); cat(.TABLE_MARKER) } }
  .exec(parse(file = .RMOBILE_ENTRY))
})
