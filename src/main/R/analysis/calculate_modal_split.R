library(tidyverse)
library(dplyr)

# analysis script to analyze the number of trips per day for each MATSim mode
modalSplitRaw <- read.csv2("./input/v1.0/modalSplitRaw.csv",sep=",",header=TRUE,stringsAsFactors = FALSE)

modalSplitRaw <- as.data.frame(lapply(modalSplitRaw, as.numeric))

modalSplitAggr <- modalSplitRaw %>%
  mutate(car = car + motorbike,
         pt = sum(taxi_internet, taxi_street, metro, bus_rtp, bus, trolebus, metrobus, train_ligero, suburban_train, mexibus, taxi_bike, taxi_motorbike, school_transport),
         walk = walk + other) %>%
  select(walk, car, taxibus, pt, bike)

sum <- sum(colSums(modalSplitAggr))
sumRaw <- sum(colSums(modalSplitRaw))

# values above do not sum to 1, but to 1.25 because in the calculation of the above modal split a trip can count twice.
# e.g. if a person uses walk and pt on a trip, both legs will count for the modal split calculation
# hence, the distribution is normalized

modalSplitAggrNorm <- modalSplitAggr / sum

sumNorm <- sum(colSums(modalSplitAggrNorm))

modalSplitAggrNorm
