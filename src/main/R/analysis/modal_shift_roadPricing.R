library(tidyverse)
library(matsim)
library(ggalluvial)
library(ggplot2)
library(sf)

setwd("Y:/net/ils/matsim-mexico-city/case-studies/roadPricing-meta-2050/output/output-mexico-city-v1.0-1pct-roadPricing-meta-2050-fare52")
# setwd("C:/Users/Simon/Desktop/wd/2024-03-25/roadPricingAnalysisTest")
analysisDir <- "analysis/roadpricing"

crs <- "EPSG:4485"

shp <- st_read(paste0("./",analysisDir,"/roadPricing_area.shp"), crs=crs)

baseTripsFile <- list.files(path = "../../../baseCase/output/output-mexico-city-v1.0-1pct", pattern = "output_trips.csv.gz", full.names = TRUE)
roadPricingTripsFile <- list.files(pattern = "output_trips.csv.gz")

###################################################### policy case trips #####################################################################

# not using matsim read trips table function because right now it cannot handle delimiters different from ";"
trips_roadPricing <- read_delim(roadPricingTripsFile,
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

trips_roadPricing <- trips_roadPricing %>%
  mutate(
    start_x = as.double(start_x),
    start_y = as.double(start_y),
    end_x = as.double(end_x),
    end_y = as.double(end_y))
attr(trips_roadPricing,"table_name") <- roadPricingTripsFile

points_start <- st_as_sf(trips_roadPricing, coords = c('start_x', 'start_y'), crs = crs) %>% 
  mutate(start_within = as.integer(st_within(geometry, shp)))

points_end <- st_as_sf(trips_roadPricing, coords = c('end_x', 'end_y'), crs = crs) %>% 
  mutate(end_within = as.integer(st_within(geometry, shp)))

trips_roadPricing <- merge(trips_roadPricing, points_start[, c("trip_id", "start_within")], by="trip_id", all.x=TRUE) %>%
  merge(points_end[, c("trip_id", "end_within")], by="trip_id", all.x=TRUE) %>%
  filter(!is.na(start_within) | !is.na(end_within)) %>% 
  select(-geometry.x, -geometry.y, -start_within, -end_within)

###################################################### base case trips #####################################################################

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
    end_y = as.double(end_y))
attr(trips_base,"table_name") <- baseTripsFile

points_start <- st_as_sf(trips_base, coords = c('start_x', 'start_y'), crs = crs) %>%
  mutate(start_within = as.integer(st_within(geometry, shp)))

points_end <- st_as_sf(trips_base, coords = c('end_x', 'end_y'), crs = crs) %>%
  mutate(end_within = as.integer(st_within(geometry, shp)))

trips_base <- merge(trips_base, points_start[, c("trip_id", "start_within")], by="trip_id", all.x=FALSE, all.y=FALSE) %>%
  merge(points_end[, c("trip_id", "end_within")], by="trip_id", all.x=TRUE) %>%
  filter(!is.na(start_within) | !is.na(end_within)) %>%
  select(-geometry.x, -geometry.y, -start_within, -end_within)

plotModalShiftSankey(trips_base, trips_roadPricing, dump.output.to = getwd())
write.csv(trips_roadPricing, file=paste0(getwd(), analysisDir, "/output_trips.roadPricing-area.csv.gz"), quote=FALSE)
