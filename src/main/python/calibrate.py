#!/usr/bin/env python
# -*- coding: utf-8 -*-

from matsim.calibration import create_calibration, ASCCalibrator, utils, analysis

import geopandas as gpd

# %%

modes = ["walk", "car", "taxibus", "pt", "bike"]
fixed_mode = "walk"
initial = {
    "bike": 0,
    "pt": -0.5,
    "car": -0.5,
    "taxibus": -0.5
}

# Target calculated from EOD2017, see class calculate_modal_split.R
target = {
    "walk": 0.1868,
    "car": 0.1829,
    "taxibus": 0.2942,
    "pt": 0.3259,
    "bike": 0.0103    
}

city = gpd.read_file("/net/ils/matsim-mexico-city/input/v1.0/cityArea/cityArea_cdmx_utm12n.shp")


def f(persons):
    persons = gpd.GeoDataFrame(persons, geometry=gpd.points_from_xy(persons.home_x, persons.home_y))

    df = gpd.sjoin(persons.set_crs("EPSG:4485"), city, how="inner", predicate="intersects")

    print("Filtered %s persons" % len(df))

    return df


def filter_modes(df):
    return df[df.main_mode.isin(modes)]


study, obj = create_calibration("calib", ASCCalibrator(modes, initial, target, lr=utils.linear_scheduler(start=0.3, interval=15)),
                                                "matsim-mexico-city-1.x-SNAPSHOT-047f3f0-dirty.jar",
                                                 "/net/ils/matsim-mexico-city/input/v1.0/mexico-city-v1.0-1pct.input.config.xml",
                                                 args="--1pct --income-area /net/ils/matsim-mexico-city/input/v1.0/nivel_amai/nivel_amai.shp --config:TimeAllocationMutator.mutationRange=900",
                                                 jvm_args="-Xmx40G -Xms40G -XX:+AlwaysPreTouch -XX:+UseParallelGC",
                                                 transform_persons=f, transform_trips=filter_modes,
                                                 chain_runs=utils.default_chain_scheduler,
                                                 debug=True)

# %%

study.optimize(obj, 10)
