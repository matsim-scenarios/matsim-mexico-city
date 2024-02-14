library(tidyverse)
library(dplyr)

# helper script to calculate the number of inhabitants per sociodemographic group in ZMVM based on EOD2017 data
persons <- read.csv2("../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/data-input-eod2017-bundled/tsdem.csv",sep=",")
hh <- read.csv2("../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/data-input-eod2017-bundled/thogar.csv",sep=",")

merged <- left_join(persons, hh, by="id_hog")

# estrato (sociodemográfico) = sociodemographic group of a person / hh
groups <- merged %>%
  filter(estrato.x == estrato.y) %>%
  select(id_soc, id_hog, estrato.x, factor.x) %>% 
  rename(group = "estrato.x",
         factor = "factor.x") %>%
  mutate(group = case_when(
    group == 1 ~ "LOW",
    group == 2 ~ "MEDIUM_LOW",
    group == 3 ~ "MEDIUM_HIGH",
    group == 4 ~ "HIGH",
    TRUE ~ as.character(group))) %>% # Handle unexpected values, if any
  uncount(factor)

groups_grouped <- groups %>%
  group_by(group) %>% 
  summarise(n=n()) %>%
  mutate(rel = n / sum(n))

plot <- ggplot(data=groups_grouped, aes(x=factor(group, level=c("LOW", "MEDIUM_LOW", "MEDIUM_HIGH", "HIGH")), y=rel)) +
  geom_bar(stat="identity") +
  ggtitle("number of inh. per sociodemographic group in ZMVM (EOD2017)") +
  geom_text(aes(label = signif(rel,2)), nudge_y = 0.01) + 
  labs(x="sociodemographic groups")
plot

enigh2018 <- read.csv2("C:/Users/Simon/Downloads/enigh/conjunto_de_datos_enigh_2018_ns_csv/conjunto_de_datos_ingresos_enigh_2018_ns/conjunto_de_datos/conjunto_de_datos_ingresos_enigh_2018_ns.csv",sep=",")

################################################################## income distribution for Mexico #########################################################################
maxIncome <- max(round(as.double(enigh2018$ing_tri)/3),2)

  enigh <- enigh2018 %>%
    mutate(ing_tri = as.double(ing_tri)) %>%
    mutate(avgIncomeMonth = round(ing_tri / 3, 2)) %>%
    filter(!is.na(avgIncomeMonth)) %>%
    mutate(incomeGroup = cut(avgIncomeMonth, breaks=(seq(0,maxIncome, by = 500)))) %>%
    filter(avgIncomeMonth>=1000 & avgIncomeMonth < 20000)

  # check out income types to get a feeling for the dataset
  incomeTypes <- enigh %>%
    group_by(clave) %>%
    summarise(n=n()) %>%
    mutate(rel = n / sum(n))

  incomePlotData <- incomeTypes %>% filter(rel>=0.01)

  incomeTypePlot <- ggplot(data=incomePlotData, aes(x=clave, y=rel)) +
    geom_bar(stat="identity") +
    ggtitle("Enigh2018 income type distr for Mexico") +
    geom_text(aes(label = signif(rel,2)), nudge_y = 0.01)
  incomeTypePlot

  enighIncomeGroups <- enigh %>%
    group_by(incomeGroup) %>%
    summarise(n=n()) %>%
    mutate(rel=n/sum(n))

  incomeDistrPlot <- incomeTypePlot <- ggplot(data=enighIncomeGroups, aes(x=incomeGroup, y=rel)) +
    geom_bar(stat="identity") +
    ggtitle("Enigh2018 income distr for Mexico")
  incomeDistrPlot

# Function to check if a number starts with a certain digit
startsWithDigit <- function(num, digit) {
  num_str <- as.character(num)
  first_char <- substr(num_str, 1, 1)
  return(first_char == as.character(digit))
}

# TODO: filter for zmvm
enigh_CDMX <- enigh2018 %>%
  filter(startsWithDigit(folioviv, 9))

################################################################## income distribution for CDMX #########################################################################
maxIncomeCDMX <- max(round(as.double(enigh_CDMX$ing_tri)/3),2)

enigh_CDMX <- enigh_CDMX %>%
  mutate(ing_tri = as.double(ing_tri)) %>%
  mutate(avgIncomeMonth = round(ing_tri / 3, 2)) %>%
  filter(!is.na(avgIncomeMonth)) %>%
  mutate(incomeGroup = cut(avgIncomeMonth, breaks=(seq(0,maxIncome, by = 500)))) %>%
  filter(avgIncomeMonth>=1000 & avgIncomeMonth < 20000)

# check out income types to get a feeling for the dataset
incomeTypes_CDMX <- enigh_CDMX %>%
  group_by(clave) %>%
  summarise(n=n()) %>%
  mutate(rel = n / sum(n))

incomePlotData_CDMX <- incomeTypes_CDMX %>% filter(rel>=0.01)

incomeTypePlot_CDMX <- ggplot(data=incomePlotData_CDMX, aes(x=clave, y=rel)) +
  geom_bar(stat="identity") +
  ggtitle("Enigh2018 income type distr for CDMX") +
  geom_text(aes(label = signif(rel,2)), nudge_y = 0.01)
incomeTypePlot_CDMX

enighIncomeGroups_CDMX <- enigh_CDMX %>%
  group_by(incomeGroup) %>%
  summarise(n=n()) %>%
  mutate(rel=n/sum(n))

incomeDistrPlot_CDMX <- incomeTypePlot_CDMX <- ggplot(data=enighIncomeGroups, aes(x=incomeGroup, y=rel)) +
  geom_bar(stat="identity") +
  ggtitle("Enigh2018 income distr for CDMX")
incomeDistrPlot_CDMX




# comparison of the 2 graphs: it does not fit! lowest estrato from eod2017 is almost not present,
# whereas the lowest income group of enigh is overwhelmingly big
# unfortunately this goes for the whole of Mexico as well as filtered enigh data only for CDMX.
# => based on the socioeconomic groups from EOD2017 / ENIGH2018 income dependent scoring cannot be implemented


