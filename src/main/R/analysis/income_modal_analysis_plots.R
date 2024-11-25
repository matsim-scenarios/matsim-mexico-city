library(tidyverse)
library(matsim)
library(RColorBrewer)
library(sf)
library(ggokabeito)

############################################# plot bars for income distr comparison avenidas principales #####################################################################
income_distr52 <- read_delim(file = "Y:/net/ils/matsim-mexico-city/case-studies/roadPricing-avenidas-principales/output/output-mexico-city-v1.0-1pct-roadPricing-avenidas-principales-fare52/analysis/roadpricing/roadPricing_income_groups.csv",
                              locale = locale(decimal_mark = "."),
                              n_max = Inf) %>% 
  rename(absolute = "share")
income_distr0.001 <- read_delim(file = "Y:/net/ils/matsim-mexico-city/case-studies/roadPricing-avenidas-principales/output/output-mexico-city-v1.0-1pct-roadPricing-avenidas-principales-relative-income-fare0.001/analysis/roadpricing/roadPricing_income_groups.csv",
                              locale = locale(decimal_mark = "."),
                              n_max = Inf) %>% 
  rename(relative_0.001 = "share")
income_distr0.005 <- read_delim(file = "Y:/net/ils/matsim-mexico-city/case-studies/roadPricing-avenidas-principales/output/output-mexico-city-v1.0-1pct-roadPricing-avenidas-principales-relative-income-fare0.005/analysis/roadpricing/roadPricing_income_groups.csv",
                                locale = locale(decimal_mark = "."),
                                n_max = Inf) %>% 
  rename(relative_0.005 = "share")
income_distr0.01 <- read_delim(file = "Y:/net/ils/matsim-mexico-city/case-studies/roadPricing-avenidas-principales/output/output-mexico-city-v1.0-1pct-roadPricing-avenidas-principales-relative-income-fare0.01/analysis/roadpricing/roadPricing_income_groups.csv",
                                locale = locale(decimal_mark = "."),
                                n_max = Inf) %>% 
  rename(relative_0.01 = "share")

income_distr <- merge(income_distr52, income_distr0.001, by="incomeGroup")
income_distr <- merge(income_distr, income_distr0.005, by="incomeGroup")
income_distr <- merge(income_distr, income_distr0.01, by="incomeGroup") %>% 
  select(incomeGroup, absolute, relative_0.001, relative_0.005, relative_0.01) %>%
  mutate(incomeGroup = factor(incomeGroup, levels=c("0 - 4999", "5000 - 9999", "10000 - 14999", "15000 - 19999", "20000 - 24999",
                                                       "25000 - 29999", "30000 - 34999", "35000 - 39999", "40000 - 44999", "45000 - 49999",
                                                       "50000+"))) %>%
  arrange(incomeGroup)

# income_distr_long <- income_distr %>% 
#   gather(key="incomeGroup", value="share") %>% 
#   mutate(fare = case_when(
#     incomeGroup=="share_52" ~ "52",
#     incomeGroup=="share_0.001" ~ "0.001",
#     incomeGroup=="share_0.005" ~ "0.005",
#     incomeGroup=="share_0.01" ~ "0.01"
#   ))

df_long <- income_distr %>%
  gather(key = "share_type", value = "share_value", absolute, relative_0.001, relative_0.005, relative_0.01)


ggplot(df_long, aes(x = incomeGroup, y = share_value, fill = incomeGroup)) +
  geom_bar(stat = "identity", position = "dodge") +
  facet_wrap(~share_type) +
  labs(x = "Income Group",
       y = "Share") +
  theme_minimal() +
  # scale_fill_brewer(palette = "Set3") +
  theme(plot.title = element_text(hjust = 0.5),
        legend.position = "none",
        axis.text.x = element_text(angle = 90, vjust = 0.5, hjust=1)) 

############################################## plot facet for modal shift over all cases ################################################################

data <- read.delim(file="Y:/net/ils/matsim-mexico-city/case-studies/roadPricing_modalShift_cdmx.tsv")

data <- data %>% 
  mutate(tollAmountChar = ifelse(tollAmount > 1.0, paste0(as.character(round(tollAmount, 0)), "MXN"), 
                                 paste0(as.character(tollAmount * 100), "%")))

data_long <- data %>%
  gather(key = "transport_mode", value = "value", -tollAmount, -case, -tollAmountChar)

# Convert tollAmount to a factor to treat it as categorical
data_long$tollAmountChar <- as.factor(data_long$tollAmountChar)

# Plot
ggplot(data_long, aes(x = tollAmountChar, y = value, color = transport_mode)) +
  geom_point(size = 2, position = position_jitter(width = 0.2, height = 0)) +  # Add jitter to avoid overlap
  facet_wrap(~ case) +
  labs(
    x = "Toll Amount (Categorical)",
    y = expression(Delta * " from Base Case"),
    color = "Transport Mode"
  ) +
  scale_color_okabe_ito() +
  theme_minimal() +
  theme(
    axis.title.x = element_text(size = 14),
    axis.title.y = element_text(size = 14),
    axis.text = element_text(size = 12),
    legend.title = element_text(size = 14),
    legend.text = element_text(size = 12),
    strip.text = element_text(size = 14)
  )


############################################## analyze mean trip length for every transport mode in base #################################################

persons_base <- read_delim("Y:/net/ils/matsim-mexico-city/case-studies/baseCase/output/output-mexico-city-v1.0-1pct/mexico-city-v1.0-1pct.output_persons.csv.gz",
                           locale = locale(decimal_mark = "."),
                           n_max = Inf)

max(persons_base$income)

crs <- "EPSG:4485"

shp <- st_read("./input/v1.0/area/area.shp", crs=crs)

home_coords <- st_as_sf(persons_base, coords = c('home_x', 'home_y'), crs = crs) %>% 
  mutate(home_within = as.integer(st_within(geometry, shp)))

persons_base <- home_coords %>% 
  filter(!is.na(home_within)) %>% 
  select(-geometry)

trips_base <- read_delim("Y:/net/ils/matsim-mexico-city/case-studies/baseCase/output/output-mexico-city-v1.0-1pct/mexico-city-v1.0-1pct.output_trips.csv.gz",
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
  filter(person %in% persons_base$person)

mean_dist_car <- trips_base %>%
  filter(main_mode == "car") %>%
  summarize(mean_distance = mean(euclidean_distance)) %>%
  pull(mean_distance)

mean_dist_bike <- trips_base %>%
  filter(main_mode == "bike") %>%
  summarize(mean_distance = mean(euclidean_distance)) %>%
  pull(mean_distance)

mean_dist_pt <- trips_base %>%
  filter(main_mode == "pt") %>%
  summarize(mean_distance = mean(euclidean_distance)) %>%
  pull(mean_distance)

mean_dist_taxibus <- trips_base %>%
  filter(main_mode == "taxibus") %>%
  summarize(mean_distance = mean(euclidean_distance)) %>%
  pull(mean_distance)

mean_dist_walk <- trips_base %>%
  filter(main_mode == "walk") %>%
  summarize(mean_distance = mean(euclidean_distance)) %>%
  pull(mean_distance)

############################################## income group distribution of mode users in base case #####################################################

# income groups 2005
income_groups <- data.frame(
  name = c("E", "D_me", "D_mas", "C_me", "C_mas", "AB"),
  lower_bound_2005 = c(0.0, 2700.0, 6800.0, 11600.0, 35000.0, 85000.0),
  upper_bound_2005 = c(2699.0, 6799.0, 11599.0, 34999.0, 84999.0, 170000.0)
)


income_groups <- income_groups %>% 
  mutate(lower_bound_2017 = lower_bound_2005 * 1.6173,
         upper_bound_2017 = upper_bound_2005 * 1.6173) %>% 
  mutate(lower_bound_2017 = round(lower_bound_2017),
         upper_bound_2017 = round(upper_bound_2017))

# Function to ensure lower_bound is previous upper_bound + 1
adjust_bounds <- function(df, name) {
  for (i in 2:nrow(df)) {
    df$lower_bound_2017[i] <- df$upper_bound_2017[i-1] + 1
  }
  return(df)
}

# Apply the function to adjust bounds
income_groups <- adjust_bounds(income_groups)

# Function to determine the income group
find_income_group <- function(value) {
  group <- income_groups %>%
    filter(round(value) >= lower_bound_2017 & round(value) <= upper_bound_2017) %>%
    pull(name)
  
  if(length(group) == 0) return(NA) else return(group)
}

persons_base <- persons_base %>% 
  rowwise() %>% 
  mutate(income_group = find_income_group(income)) %>% 
  ungroup()

persons_base_E <- persons_base %>% 
  filter(income_group == "E")

ggplot(persons_base_E, aes(x = "", y = income)) +
  geom_violin(fill = "purple4", color = "black") +
  geom_vline(xintercept = 1, color = "black", linetype = "dashed", size = 1) +
  labs(title = "Violin Plot of Income Group E", y = "Income") +
  theme_minimal() +
  theme(
    plot.title = element_text(hjust = 0.5, size = 20),
    axis.title = element_text(size = 15),
    axis.text = element_text(size = 12)
  )

below_100MXN_persons <- persons_base %>% 
  filter(income <= 100)

  

na_persons <- persons_base %>% 
  filter(is.na(income_group))

# plot income distr over whole base population
grouped <- persons_base %>% 
  group_by(income_group) %>% 
  summarize(count = n()) %>% 
  mutate(share = count / sum(count)) %>% 
  arrange(desc(income_group)) %>%
  mutate(income_group = factor(income_group, levels = income_group))

ggplot(grouped, aes(x = income_group, y = share)) +
  geom_bar(stat = "identity", width = 0.7) +
  labs(title = "overall", x = "Income Group", y = "Share") +
  theme_minimal() +
  theme(
    plot.title = element_text(size = 30, hjust = 0.5),
    axis.title = element_text(size = 30),
    axis.text = element_text(size = 30),
    axis.text.x = element_text(angle = 90, hjust = 1)) +
  ylim(0, 1)

modes <- c("car", "bike", "pt", "taxibus", "walk")

# plot income distr filtered for each mode in base case
for (mode in modes) {
  filtered_trips <- trips_base %>% 
    filter(main_mode == mode)
  
  filtered_persons <- persons_base %>% 
    filter(person %in% filtered_trips$person)
  
  filtered_persons_grouped <- filtered_persons %>% 
    group_by(income_group) %>% 
    summarize(count = n()) %>% 
    mutate(share = count / sum(count)) %>% 
    arrange(desc(income_group)) %>%
    mutate(income_group = factor(income_group, levels = income_group))
  
  plot <- ggplot(filtered_persons_grouped, aes(x = income_group, y = share)) +
            geom_bar(stat = "identity", width = 0.7) +
            labs(title = mode, x = "Income Group", y = "Share") +
            theme_minimal() +
            theme(
              plot.title = element_text(size = 30, hjust = 0.5),
              axis.title = element_text(size = 30),
              axis.text = element_text(size = 30),
              axis.text.x = element_text(angle = 90, hjust = 1)) +
    ylim(0, 1)
  
  print(plot)
}


############################################## modal split per income group #####################################################

trips_income <- merge(trips_base, persons_base %>% select(person, income_group, income), by="person")

# plot modal share for all citizens
trips_grouped <- trips_income %>% 
  group_by(main_mode) %>% 
  summarize(count = n()) %>% 
  mutate(share = count / sum(count)) %>%
  mutate(legend_label = paste0(main_mode, " (", round(share, digits=4), ")"))

# Generate a color palette
palette <- brewer.pal(n = nrow(trips_grouped), name = "Set1")

# Create the pie chart with customized legend
ggplot(trips_grouped, aes(x = "", y = share, fill = main_mode)) +
  geom_bar(width = 1, stat = "identity") +
  coord_polar(theta = "y") +
  labs(title = "Overall", x = "", y = "") +
  scale_fill_manual(values = palette, labels = trips_grouped$legend_label) + # Custom labels in the legend
  theme_minimal() +
  theme(axis.text.x = element_blank(),
        axis.ticks = element_blank(),
        plot.title = element_text(hjust = 0.5),
        legend.title = element_blank(), # Optional: remove legend title
        legend.text = element_text(size = 12)) # Optional: adjust legend text size

# plot modal share for each income group
for (group in income_groups$name) {
  filtered_trips_income <- trips_income %>% 
    filter(income_group == group)
  
  filtered_trips_income_grouped <- filtered_trips_income %>% 
    group_by(main_mode) %>% 
    summarize(count = n()) %>% 
    mutate(share = count / sum(count)) %>%
    mutate(legend_label = paste0(main_mode, " (", round(share, digits=4), ")"))
  
  plot2 <- ggplot(filtered_trips_income_grouped, aes(x = "", y = share, fill = main_mode)) +
    geom_bar(width = 1, stat = "identity") +
    coord_polar(theta = "y") +
    labs(title = group, x = "", y = "") +
    scale_fill_manual(values = palette, labels = filtered_trips_income_grouped$legend_label) + # Custom labels in the legend
    theme_minimal() +
    theme(axis.text.x = element_blank(),
          axis.ticks = element_blank(),
          plot.title = element_text(hjust = 0.5),
          legend.title = element_blank(), # Optional: remove legend title
          legend.text = element_text(size = 12)) # Optional: adjust legend text size
  
  print(plot2)
}


  
  
  
  