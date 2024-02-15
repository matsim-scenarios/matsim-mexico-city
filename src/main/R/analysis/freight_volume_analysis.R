library(tidyverse)
library(dplyr)

dir <- "../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/counts/"

csv_files <- list.files(dir, pattern = "\\.csv$", full.names = TRUE)

countData <- data.frame()

dataForColumnNames <- read.csv2(paste0(dir,"Av. Las Torres (Eje 6) Central (1).csv"), sep = ",", header = TRUE, quote="")

for (file in csv_files) {
  data <- read.csv2(file, sep = ",", header = TRUE, quote="")
  
  # rename columns because some count files have slightly different names
  colnames(data) <- colnames(dataForColumnNames)
  
  countData <- rbind(countData, data)
}

countData <- countData %>% 
  mutate(AUTOS=as.double(AUTOS),
         OTROS=as.double(OTROS),
         AUTOBUSES=as.double(AUTOBUSES),
         CAMIONES=as.double(CAMIONES))

countsPerHighway <- countData %>% 
  group_by(X..CLAVE.CARRETERA..) %>% 
  summarise(avgFreightVolumeRel=mean(CAMIONES)) %>% 
  mutate(X..CLAVE.CARRETERA..=as.character(X..CLAVE.CARRETERA..))


plot <- ggplot(data=countsPerHighway, aes(x=X..CLAVE.CARRETERA.., y=avgFreightVolumeRel)) +
  geom_bar(stat="identity") +
  ggtitle("Average freight volume per carretera (relative))") +
  geom_text(aes(label = signif(avgFreightVolumeRel,2)), nudge_y = 0.5)
plot

avgFreightTotal <- mean(countData$CAMIONES)
avgFreightTotal
