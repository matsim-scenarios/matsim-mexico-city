
N := mexico-city
V := v1.0
#Mexico ITRF92 / UTM zone 12N
CRS := EPSG:4485

MEMORY ?= 20G
JAR :=  matsim-mexico-city-1.x-SNAPSHOT-f23b612-dirty.jar
#JAR := matsim-mexico-city-*.jar

ifndef SUMO_HOME
	export SUMO_HOME := $(abspath ../../sumo-1.15.0/)
endif

# here, usually the "osmosis" file is used, which does not work for me..
osmosis := ../../../../../Program Files (x86)/osmosis-0.48.3/bin/osmosis.bat

# Scenario creation tool
sc := java -Xmx$(MEMORY) -jar $(JAR)

.PHONY: prepare

$(JAR):
	mvn package

############################################ 1) NETWORK CREATION ###########################################################

# Required files
input/network.osm.pbf:
	curl https://download.geofabrik.de/north-america/mexico-231031.osm.pbf/
	  -o input/network.osm.pbf

input/network.osm:input/network.osm.pbf

#	after thinking about whether to use the exact city borders from shp or a bounding box, the bounding box is chosen
 #	because streets do not necessarily end at the city border and some more detailed network does not hurt,
 #	as long as the net does not become too large -sme1023
 #	 --bounding-polygon file="../shared-svn/projects/$N/data/area.poly"\#

	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-box top=19.5973 left=-99.3707 bottom=19.0446 right=-98.9319\
	 --used-node --wb input/network-detailed.osm.pbf

# in the following it makes sense to use the shp file of zmvm, as this is an area we are interested in, but not the main focus
# I could not run the command with shp file, so I switched back to using a boundary box -sme1023
#--bounding-polygon file="../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/zmvm_2010/zmvm_2010.shp"\
#coarse = rough

	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction\
	 --bounding-box top=19.9010 left=-99.7449 bottom=18.8726 right=-98.5336\
	 --used-node --wb input/network-coarse.osm.pbf

 #	 the following is the pendant of german scenarios "germany network". So, it is just a really wide area with big links only -sme1023

	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
	 --bounding-box top=21.412 left=-100.876 bottom=18.073 right=-97.086\
	 --used-node --wb input/network-wide-area.osm.pbf

	$(osmosis) --rb file=input/network-wide-area.osm.pbf --rb file=input/network-coarse.osm.pbf --rb file=input/network-detailed.osm.pbf\
  	 --merge --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

	rm input/network-detailed.osm.pbf
	rm input/network-coarse.osm.pbf
	rm input/network-wide-area.osm.pbf

input/sumo.net.xml: input/network.osm

# it would be nice to have a osm mx netConvert file, but this does not seem to exist. Therefore, standard sumo types are used -sme1123
# ,$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml\
# osm.lane-access true adds all allowed / disallowed modes to every lane as a parameter

	$(SUMO_HOME)/bin/netconvert --osm-files $< -o=$@ --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)data\typemap\osmNetconvert.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --osm.lane-access true\
	 --proj "+proj=utm +zone=12 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"\

input/first-network.xml.gz: input/sumo.net.xml

	$(sc) prepare network-from-sumo-mexico-city $< --target-crs $(CRS) --output $@

	$(sc) prepare clean-network $@ --output $@ --modes car,ride,bike


input/first-network-with-pt.xml.gz: input/first-network.xml.gz
	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/\
	 --name $N-$V --date "2023-03-07" --target-crs $(CRS) \
	 ../../public-svn/matsim/scenarios/countries/mx/$N/$N-$V/input/data-scenario-generation/gtfs_semovi_2024-02-22.zip

input/mexico-city-v1.0-transitVehicles_corrected.xml.gz: input/mexico-city-v1.0-transitVehicles.xml.gz
	 $(sc) prepare correct-pt-vehicle-types\
	  --vehicles $<


############################################ 2) POPULATION CREATION ###########################################################

input/first-population-cdmx-only-1pct-homeLocOnly.plans.xml.gz: ..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\data-scenario-generation/manzanas_censo2020\manzanas_censo2020_utm12n.shp
	$(sc) prepare mexico-city-population\
		--shp $<\
		--output $@

input/first-population-zmvm-without-cdmx-1pct-homeLocOnly.plans.xml.gz: ..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\data-scenario-generation/zmvm_2010\zmvm_2010_utm12n.shp
	$(sc) prepare zmvm-population\
		--input ..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\data-scenario-generation/iter_13_cpv2020\conjunto_de_datos\conjunto_de_datos_iter_13CSV20.csv,..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\data-scenario-generation/iter_15_cpv2020\conjunto_de_datos\conjunto_de_datos_iter_15CSV20.csv\
		--shp $<\
		--output $@

input/mexico-city-static-1pct.plans.xml.gz: input/first-population-cdmx-only-1pct-homeLocOnly.plans.xml.gz input/first-population-zmvm-without-cdmx-1pct-homeLocOnly.plans.xml.gz
	$(sc) prepare merge-populations $^\
	 --output $@

# the activites and persons table for this class are created by sampling survey data.
# this is done via matsim-python-tools: https://github.com/matsim-vsp/matsim-python-tools/blob/mexico-city/matsim/scenariogen/data/run_extract_activities.py
input/mexico-city-activities-1pct.plans.xml.gz: input/mexico-city-static-1pct.plans.xml.gz
	$(sc) prepare activity-sampling --input $<\
		--output $@\
		--persons input/table-persons.csv.gz\
  		--activities input/table-activities.csv.gz\
  		--network input/v1.0/mexico-city-v1.0-network.xml.gz\
  		--shp ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/distritos_eod2017_unam/DistritosEODHogaresZMVM2017_utm12n.shp

input/v1.0/mexico-city-v1.0-facilities.xml.gz: input/v1.0/mexico-city-v1.0-network.xml.gz ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/economy_locations_zmvm_2017/economy_locations_zmvm_2017_utm12n.shp
	$(sc) prepare facilities\
		--network $<\
		--shp $(word 2,$^)\
		--output $@

input/commuter.csv: ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/data-input-eod2017-bundled/tviaje.csv
	$(sc) prepare create-commute-relations\
		--od-survey $<\
		--zmvm-shp ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/zmvm_2010/zmvm_2010_utm12n.shp\
		--survey-shp ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/distritos_eod2017_unam/DistritosEODHogaresZMVM2017_utm12n.shp\
		--output $@

#	--k 10 -> create 10 plans for each person to have more choices for CountOptimization
input/mexico-city-initial-1pct.plans.xml.gz: input/mexico-city-activities-1pct.plans.xml.gz input/v1.0/mexico-city-v1.0-facilities.xml.gz input/v1.0/mexico-city-v1.0-network.xml.gz
	$(sc) prepare init-location-choice\
	 	--input $<\
	 	--output $@\
	 	--facilities $(word 2,$^)\
	 	--network $(word 3,$^)\
	 	--shp ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/zmvm_2010/zmvm_2010_utm12n.shp\
	 	--commuter input/commuter.csv\
	 	--k 10\

# For debugging and visualization
	$(sc) prepare downsample-population $@\
		 --sample-size 0.01\
		 --samples 0.001\

# create vehicle types
input/mexico-city-v1.0-vehicle-types.xml: ./input
	$(sc) prepare vehicle-types\
		--directory $<\
		--modes car,bike

# create count stations based on csv count data
input/mexico-city-v1.0.counts_car.2017.xml: ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/counts input/v1.0/mexico-city-v1.0-network-with-pt.xml.gz input/v1.0/mexico-city-v1.0-network-linkGeometries.csv
	$(sc) prepare counts\
		--input $<\
		--network $(word 2,$^)\
		--network-geometries $(word 3,$^)\
		--input-crs "EPSG:4326"\
		--target-crs $(CRS)\
		--manual-matched-counts input/manualLinkAssignment.csv\
		--year 2017

	$(sc) prepare scale-counts\
		--input $@

# create first scenario specific config
input/mexico-city-v1.0-1pct.input.config.xml: ./input/v1.0 ./input
	$(sc) prepare config\
		--input-directory $<\
		--modes car,bike,pt,walk,taxibus\
		--output-directory $(word 2,$^)

input/eval-opt:
	java -cp $(JAR) -Xmx14G org.matsim.prepare.population.RunMexicoCityCalibration\
		run --mode "EVAL"\
		--iterations 1\
		--output "output/eval-opt"\
		--1pct\
		--population mexico-city-initial-1pct.plans.xml.gz\
		--config:strategy.maxAgentPlanMemorySize 10

# experienced plans are output of above calibration eval run
ERROR_METRIC ?= LOG_ERROR
input/mexico-city-initial-1pct-plan-selection.csv: ./input/mexico-city-initial-1.0-1pct.experienced_plans.xml.gz input/v1.0/mexico-city-v1.0-network.xml.gz input/v1.0/mexico-city-v1.0.counts_car.2017.xml
	$(sc) prepare run-count-opt\
	 --input $<\
	 --network $(word 2,$^)\
     --counts $(word 3,$^)\
	 --output $@\
	 --metric $(ERROR_METRIC)\
	 --k 10

input/mexico-city-initial-1pct.LOG_ERROR.plans.xml.gz: input/v1.0/mexico-city-initial-1pct.plans.xml.gz input/mexico-city-initial-1pct-plan-selection.csv
	$(sc) prepare select-plans-idx\
 	 --input $<\
 	 --csv $(word 2,$^)\
 	 --output $@\
 	 --exp-plans input/mexico-city-initial-1.0-1pct.experienced_plans.xml.gz

input/v1.0/mexico-city-v1.0-1pct.input.plans.xml.gz: input/v1.0/mexico-city-initial-1pct.LOG_ERROR.plans.xml.gz
	$(sc) prepare split-activity-types-duration\
		--input $<\
		--output $@

	$(sc) prepare change-mode-names\
		--input $@\
		--output $@

	$(sc) prepare check-car-avail\
		--input $@\
		--output $@\
		--mode walk

	$(sc) prepare fix-subtour-modes\
		--input $@\
		--output $@\
		--all-plans\
		--coord-dist 100

# commented out due to bug when reading plans / activities with assigned facilityIds, see matsim-libs PR3106
#	$(sc) prepare xy-to-links\
#		--network input/v1.0/mexico-city-v1.0-network.xml.gz\
#		--input $@\
#		--output $@

	$(sc) prepare extract-home-coordinates $@\
		--csv input/v1.0/mexico-city-v1.0-homes.csv

	$(sc) prepare downsample-population $@\
		 --sample-size 0.01\
		 --samples 0.001\

# this step does not fully work yet, because some activities do not have a coord yet -> see comments on prepare xy-to-links
check: input/v1.0/mexico-city-v1.0-1pct.input.plans.xml.gz
	$(sc) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp ../../public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/data-scenario-generation/zmvm_2010/zmvm_2010_utm12n.shp\
	 --shp-crs $(CRS)

# Aggregated target
prepare: input/v1.0/mexico-city-v1.0-1pct.input.plans.xml.gz input/v1.0/mexico-city-v1.0-network-with-pt.xml.gz
	echo "Done"