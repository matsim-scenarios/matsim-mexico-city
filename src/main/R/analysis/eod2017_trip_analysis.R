library(tidyverse)
library(dplyr)

# analysis script to analyze the number of trips per day for each MATSim mode
trips <- read.csv2("../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/data-input-eod2017-bundled/tviaje.csv",sep=",")

trips <- trips %>% filter(p5_3 == 1)

# p5_3=1 -> weekdays only, p5_14_XX are all different pt modes of ZMVM
trips_pt <- trips %>%
  filter(p5_14_03 == 1 | 
           p5_14_04 == 1 |
           p5_14_05 == 1 |
           p5_14_06 == 1 |
           p5_14_08 == 1 |
           p5_14_10 == 1 |
           p5_14_11 == 1 |
           p5_14_12 == 1 |
           p5_14_13 == 1 |
           p5_14_15 == 1 |
           p5_14_16 == 1 |
           p5_14_17 == 1)

trips_car <- trips %>% 
  filter(p5_14_01 == 1 | p5_14_09 == 1)

trips_bike <- trips %>% 
  filter(p5_14_07 == 1)

trips_taxibus <- trips %>% 
  filter(p5_14_02 == 1)

trips_walk <- trips %>% 
  filter(p5_14_14 == 1)

datasets <- list(trips_pt, trips_car, trips_taxibus, trips_bike, trips_walk, trips)
names <- c("pt", "car", "taxibus", "bike", "walk", "total")
i <- 1

avgValues <- setNames(data.frame(matrix(ncol = 3, nrow = 0)), c("mode", "meanTripsPerDay", "medianTripsPerDay"))

for (dataset in datasets) {
  
  grouped <- dataset %>% 
    group_by(id_soc) %>% 
    summarise(n=n())
  
  meanTrips <- mean(grouped$n)
  medianTrips <- median(grouped$n)
  
  print(paste0("Mean number of trips for mode ",names[i], ": ", meanTrips))
  print(paste0("Median number of trips for mode ",names[i], ": ", medianTrips))
  
  avgValuesDataset <- data.frame(names[i], meanTrips, medianTrips)
  
  names(avgValuesDataset) <- names(avgValues)
  avgValues <- rbind(avgValues,avgValuesDataset)
  
  i <- i + 1
}

write.table(avgValues,"./output/avg_trips_per_mode_eod2017.csv",quote=FALSE, row.names=FALSE, dec=".", sep=",")

