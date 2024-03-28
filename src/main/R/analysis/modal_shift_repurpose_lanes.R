library(tidyverse)
library(matsim)
library(ggalluvial)
library(ggplot2)

setwd("Y:/net/ils/matsim-mexico-city/case-studies/lane-repurposing/output/output-mexico-city-v1.0-1pct-lane-repurposing")

baseTripsFile <- list.files(path = "../../../baseCase/output/output-mexico-city-v1.0-1pct", pattern = "output_trips.csv.gz", full.names = TRUE)
repurposeLanesTripsFile <- list.files(pattern = "output_trips.csv.gz")



# not using matsim read trips table function because right now it cannot handle delimiters different from ";"
trips_base <- read_delim(baseTripsFile,
           locale = locale(decimal_mark = "."),
           n_max = Inf,
           col_types = cols(
             start_x = col_character(),
             start_y = col_character(),
             end_x = col_character(),
             end_y = col_character(),
             end_link = col_character(),
             start_link = col_character()
           ))

trips_base <- trips_base %>%
  mutate(
    start_x = as.double(start_x),
    start_y = as.double(start_y),
    end_x = as.double(end_x),
    end_y = as.double(end_y)
  )
attr(trips_base,"table_name") <- baseTripsFile

trips_repurpose_lanes <- read_delim(repurposeLanesTripsFile,
                         locale = locale(decimal_mark = "."),
                         n_max = Inf,
                         col_types = cols(
                           start_x = col_character(),
                           start_y = col_character(),
                           end_x = col_character(),
                           end_y = col_character(),
                           end_link = col_character(),
                           start_link = col_character()
                         ))

trips_repurpose_lanes <- trips_repurpose_lanes %>%
  mutate(
    start_x = as.double(start_x),
    start_y = as.double(start_y),
    end_x = as.double(end_x),
    end_y = as.double(end_y)
  )
attr(trips_repurpose_lanes,"table_name") <- repurposeLanesTripsFile

analysisDir <- "analysis/repurposeLanes"

full_path <- file.path(getwd(), analysisDir)
if (!dir.exists(full_path)) {
  dir.create(full_path, recursive = TRUE)
  cat("Created directory:", full_path, "\n")
} else {
  cat("Directory already exists:", full_path, "\n")
}

plotModalShiftSankey(trips_base, trips_repurpose_lanes, dump.output.to = analysisDir)

