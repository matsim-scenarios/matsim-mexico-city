package org.matsim.prepare.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@CommandLine.Command(
	name = "bike-network-from-shp",
	description = "Add bike as a network mode to network links. Based on a shp file with cycleways."
)
public class CreateBikeNetworkFromShp implements MATSimAppCommand {
	@CommandLine.Option(names = "--network", description = "Path to input network file.", required = true)
	private Path inputNetwork;
	@CommandLine.Option(names = "--output", description = "Output xml file", required = true)
	private Path output;
	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();

	private final Logger log = LogManager.getLogger(CreateBikeNetworkFromShp.class);

	public static void main(String[] args) {
		new CreateBikeNetworkFromShp().execute(args);
	}

	@Override
	public Integer call() throws Exception {
//		shp file has to be provided
		if (!shp.isDefined()) {
			log.error("No shp file is provided. It is required to provide a shp file with cycleways via --shp!");
			return 2;
		}

		List<Geometry> geometries = new ArrayList<>();
		for (SimpleFeature feature : shp.readFeatures()) {
			geometries.add((Geometry) feature.getDefaultGeometry());
		}

		Network network = NetworkUtils.readNetwork(inputNetwork.toString());

		log.info("Starting to adapt the network, this might take a while!");

		int count = 0;
		for (Link link : network.getLinks().values()) {

			boolean fromInside = false;
			boolean toInside = false;

			for (Geometry geom : geometries) {
				if (!fromInside) {
					fromInside = MGC.coord2Point(link.getFromNode().getCoord()).within(geom);
				}

				if (!toInside) {
					toInside = MGC.coord2Point(link.getToNode().getCoord()).within(geom);
				}

//				if to and from node are inside of _any_ cycleway shape we want to add bike as allowed mode.
//				if we check isInside = MGC.coord2Point(link.getFromNode().getCoord()).within(geom) && MGC.coord2Point(link.getToNode().getCoord()).within(geom)
//				for only one geometry at a time there are many holes, which leads to big parts of the bike net being "cleaned out"	-sme0524
				if (fromInside && toInside && !link.getAttributes().getAttribute("type").toString().contains("highway.motorway")) {
					Set<String> modes = new HashSet<>();
					modes.add(TransportMode.bike);
					modes.addAll(link.getAllowedModes());
					link.setAllowedModes(modes);
					count++;
					break;
				}
			}
		}

		log.info("For {} links bike was added as an allowed mode.", count);
		MultimodalNetworkCleaner cleaner = new MultimodalNetworkCleaner(network);
		cleaner.run(Set.of(TransportMode.bike));

		NetworkUtils.writeNetwork(network, output.toString());
		log.info("Output network has been written to {}.", output);

		return 0;
	}
}
