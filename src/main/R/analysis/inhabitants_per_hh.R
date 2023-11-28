library(tidyverse)
library(dplyr)

setwd("~/")
setwd("./public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/")

# helper script to calculate the number of inhabitants per household in ZMVM based on EOD2017 data
vv <- read.csv2(unzip("eod_2017_csv.zip", "tvivienda_eod2017/conjunto_de_datos/tvivienda.csv"),sep=",")

# p1_1 -> survey question on how many persons live in the hh
vv_real <- vv %>% 
  uncount(factor) %>% 
  mutate(noInhCapped = ifelse(p1_1 <= 7,p1_1,7))

distr <- vv_real %>%
  group_by(noInhCapped) %>%
  summarize(n=n()) %>%
  mutate(rel = n / sum(n))

plot <- ggplot(data=distr, aes(x=noInhCapped, y=rel)) +
geom_bar(stat="identity") +
  ggtitle("number of inh. per HH size in ZMVM (EOD2017)") +
  geom_text(aes(label = signif(rel,2)), nudge_y = 0.01) +
  scale_x_discrete(limits = c(1,2,3,4,5,6,"7+"))
plot



