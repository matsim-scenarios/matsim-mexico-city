library(tidyverse)
library(matsim)
library(ggalluvial)
library(ggplot2)
library(sf)
library(optparse)

option_list <- list(
  make_option(c("-d", "--runDir"), type="character", default=NULL,
              help="Path of run directory. Avoid using '/', use '/' instead", metavar="character"))

opt_parser <- OptionParser(option_list=option_list)
opt <- parse_args(opt_parser)

# if you want to run code without run args -> comment out the following if condition + setwd() manually
if (is.null(opt$runDir)){
  print_help(opt_parser)
  stop("At least 1 argument must be supplied. Use -h for help.", call.=FALSE)
}

setwd(opt$runDir)
# setwd("C:/Users/Simon/Desktop/wd/2024-04-01/modalShiftAnalysis/roadPricing-avenidas-principales/output/relative-income-fare0.001")
analysisDir <- "analysis/roadpricing"

crs <- "EPSG:4485"

shp <- st_read(paste0("./",analysisDir,"/roadPricing_area.shp"), crs=crs)

baseTripsFile <- list.files(path = "../../../baseCase/output/output-mexico-city-v1.0-1pct", pattern = "output_trips.csv.gz", full.names = TRUE)
roadPricingTripsFile <- list.files(pattern = "output_trips.csv.gz")
roadPricingActivitiesFile <- list.files(pattern = "output_activities.csv.gz")

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

# activities are the same for base / policy, time related values change, but we do not care about them here
activities <- read_delim(roadPricingActivitiesFile,
                         locale = locale(decimal_mark = "."),
                         n_max = Inf)

activities_base_summary <- activities %>% 
  group_by(person) %>% 
  summarise(num_acts = n())

ggplot(activities_base_summary, aes(x = "", y = num_acts)) +
  geom_violin(fill = "purple4", color = "black") +
  # geom_vline(xintercept = 1, color = "black", linetype = "dashed", size = 1) +
  labs(title = "Distribution of number of activities/person - base case", y = "number of acts/person") +
  theme_minimal() +
  theme(
    plot.title = element_text(hjust = 0.5, size = 20),
    axis.title = element_text(size = 15),
    axis.text = element_text(size = 12)
  ) +
  scale_y_continuous(limits = c(0, max(activities_base_summary$num_acts)), breaks= seq(0, max(activities_base_summary$num_acts), by=1))

points_start <- st_as_sf(trips_base, coords = c('start_x', 'start_y'), crs = crs) %>%
  mutate(start_within = as.integer(st_within(geometry, shp)))

points_end <- st_as_sf(trips_base, coords = c('end_x', 'end_y'), crs = crs) %>%
  mutate(end_within = as.integer(st_within(geometry, shp)))

trips_base <- merge(trips_base, points_start[, c("trip_id", "start_within")], by="trip_id", all.x=FALSE, all.y=FALSE) %>%
  merge(points_end[, c("trip_id", "end_within")], by="trip_id", all.x=TRUE) %>%
  filter(!is.na(start_within) | !is.na(end_within)) %>%
  select(-geometry.x, -geometry.y, -start_within, -end_within)

########################################## analyze mean number of car trips and activities / person for base and policy ####################################################

activities_base_car_summary <- activities %>% 
  right_join(trips_base %>% filter(main_mode=="car"), by="person") %>%
  group_by(person) %>% 
  summarise(num_acts = n())

mean_num_acts_car_base <- mean(activities_base_car_summary$num_acts)

activities_roadPricing_car_summary <- activities %>% 
  right_join(trips_roadPricing %>% filter(main_mode=="car"), by="person") %>%
  group_by(person) %>% 
  summarise(num_acts = n())

mean_num_acts_car_roadPricing <- mean(activities_roadPricing_car_summary$num_acts)
  

trips_base_car_summary <- trips_base %>% 
  filter(main_mode == "car") %>% 
  group_by(person) %>% 
  summarise(num_trips = n())

mean_num_trips_car_base <- mean(trips_base_car_summary$num_trips)

trips_roadPricing_car_summary <- trips_roadPricing %>% 
  filter(main_mode == "car") %>% 
  group_by(person) %>% 
  summarise(num_trips = n())

mean_num_trips_car_roadPricing <- mean(trips_roadPricing_car_summary$num_trips)

names <- c("meanNumTripsBase", "meanNumTripsRoadPricing", "meanNumActsBase", "meanNumActsRoadPricing")
values <- c(mean_num_trips_car_base, mean_num_trips_car_roadPricing, mean_num_acts_car_base, mean_num_acts_car_roadPricing)

df_mean_num_trips <- data.frame(names, values) 
  

############################################### print result files ####################################################################################

plotModalShiftSankey(trips_base, trips_roadPricing, dump.output.to = analysisDir)
write.csv(trips_roadPricing, file=paste0(analysisDir, "/output_trips.roadPricing-area.csv.gz"), quote=FALSE)
write.csv(df_mean_num_trips, file=paste0(analysisDir, "/mean-num-trips-comp.csv"), quote=FALSE)
