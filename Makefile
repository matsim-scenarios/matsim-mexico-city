
N := mexico-city
V := v1.0
#Mexico ITRF92 / UTM zone 12N
CRS := EPSG:4485

MEMORY ?= 20G
JAR := matsim-mexico-city-1.x-SNAPSHOT-40ae115-dirty.jar

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

	$(SUMO_HOME)/bin/netconvert --osm-files $< -o=$@ --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)data\typemap\osmNetconvert.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=12 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"\

input/first-network.xml.gz: input/sumo.net.xml

	$(sc) prepare network-from-sumo $< --target-crs $(CRS) --output $@

	$(sc) prepare clean-network $@ --output $@ --modes car,ride,bike


input/first-network-with-pt.xml.gz: input/first-network.xml.gz

	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/\
	 --name $N-$V --date "2021-03-09" --target-crs $(CRS) \
	 ../../public-svn/matsim/scenarios/countries/mx/$N/$N-$V/input/gtfs_cdmx_2020-09-15.zip


############################################ 2) POPULATION CREATION ###########################################################

input/first-population-cdmx-only-1pct-homeLocOnly.plans.xml.gz: ..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\manzanas_censo2020\manzanas_censo2020_utm12n.shp
	$(sc) prepare mexico-city-population\
		--shp $<\
		--output $@

input/first-population-zmvm-without-cdmx-1pct-homeLocOnly.plans.xml.gz: ..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\zmvm_2010\zmvm_2010_utm12n.shp
	$(sc) prepare zmvm-population\
		--input ..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\iter_13_cpv2020\conjunto_de_datos\conjunto_de_datos_iter_13CSV20.csv,..\..\public-svn\matsim\scenarios\countries\mx\mexico-city\mexico-city-v1.0\input\iter_15_cpv2020\conjunto_de_datos\conjunto_de_datos_iter_15CSV20.csv\
		--shp $<\
		--output $@

input/mexico-city-static-1pct.plans.xml.gz: input/first-population-cdmx-only-1pct-homeLocOnly.plans.xml.gz input/first-population-zmvm-without-cdmx-1pct-homeLocOnly.plans.xml.gz
	$(sc) prepare merge-populations $^\
	 --output $@

input/mexico-city-activities-1pct.plans.xml.gz: input/mexico-city-static-1pct.plans.xml.gz
	$(sc) prepare activity-sampling --seed 1 --input $< --output $@ --persons #TODO --activities #TODO

input/berlin-initial-1pct.plans.xml.gz: input/mexico-city-activities-1pct.plans.xml.gz #TODO-facilities.xml.gz input/v1.0/mexico-city-v1.0-network.xml.gz
	$(sc) prepare init-location-choice\
	 --input $<\
	 --output $@\
	 --facilities $(word 2,$^)\
	 --network $(word 3,$^)\
	 --shp # TODO$(germany)/vg5000/vg5000_ebenen_0101/VG5000_GEM.shp\
	 --commuter #TODO $(germany)/regionalstatistik/commuter.csv\

	# For debugging and visualization
	$(sc) prepare downsample-population $@\
		 --sample-size 0.01\
		 --samples 0.01\

input/$V/prepare-25pct.plans.xml.gz:
	$(sc) prepare trajectory-to-plans\
	 --name prepare --sample-size 0.25 --output input/$V\
	 --population ../shared-svn/projects/$N/matsim-input-files/population.xml.gz\
	 --attributes  ../shared-svn/projects/$N/matsim-input-files/personAttributes.xml.gz

	$(sc) prepare resolve-grid-coords\
	 input/$V/prepare-25pct.plans.xml.gz\
	 --input-crs $CRS\
	 --grid-resolution 300\
	 --landuse ../matsim-leipzig/scenarios/input/landuse/landuse.shp\
	 --output $@

input/$V/$N-$V-25pct.plans.xml.gz: input/freight-trips.xml.gz input/$V/$N-$V-network.xml.gz input/$V/prepare-25pct.plans.xml.gz
	$(sc) prepare generate-short-distance-trips\
 	 --population input/$V/prepare-25pct.plans.xml.gz\
 	 --input-crs $CRS\
	 --shp ../shared-svn/projects/$N/data/shp/$N.shp --shp-crs $CRS\
 	 --num-trips 111111 # FIXME

	$(sc) prepare adjust-activity-to-link-distances input/$V/prepare-25pct.plans-with-trips.xml.gz\
	 --shp ../shared-svn/projects/$N/data/shp/$N.shp --shp-crs $CRS\
     --scale 1.15\
     --input-crs $CRS\
     --network input/$V/$N-$V-network.xml.gz\
     --output input/$V/prepare-25pct.plans-adj.xml.gz

	$(sc) prepare xy-to-links --network input/$V/$N-$V-network.xml.gz --input input/$V/prepare-25pct.plans-adj.xml.gz --output $@

	$(sc) prepare fix-subtour-modes --input $@ --output $@

	$(sc) prepare merge-populations $@ $< --output $@

	$(sc) prepare extract-home-coordinates $@ --csv input/$V/$N-$V-homes.csv

	$(sc) prepare downsample-population $@\
    	 --sample-size 0.25\
    	 --samples 0.1 0.01\


check: input/$V/$N-$V-25pct.plans.xml.gz
	$(sc) analysis check-population $<\
 	 --input-crs $CRS\
	 --shp ../shared-svn/projects/$N/data/shp/$N.shp --shp-crs $CRS

# Aggregated target
prepare: input/$V/$N-$V-25pct.plans.xml.gz input/$V/$N-$V-network-with-pt.xml.gz
	echo "Done"