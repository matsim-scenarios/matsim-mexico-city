package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@CommandLine.Command(
		name = "network",
		description = "Add network allowed mode bike for simulation of mode bike on network."
)
public class PrepareNetwork implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(PrepareNetwork.class);

	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private String networkFile;

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	@CommandLine.Option(names = "--output", description = "Output path of the prepared network", required = true)
	private String outputPath;

	public static void main(String[] args) {
		new PrepareNetwork().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Network network = NetworkUtils.readNetwork(networkFile);

		if (!MexicoCityUtils.isDefined(shp.getShapeFile())) {
			shp = new ShpOptions(Path.of("input/v1.0/area/area.shp"), null, null);
		}

		prepareNetworkBikeOnNetwork(network, shp);

		NetworkUtils.writeNetwork(network, outputPath);

		return 0;
	}

	/**
	 * prepare network for modelling bike as network mode.
	 */
	public static void prepareNetworkBikeOnNetwork(Network network, ShpOptions shp) {
		Geometry bikeArea = shp.getGeometry();

		boolean isInsideArea;
		int linkCount = 0;

		for (Link link : network.getLinks().values()) {
			if (link.getId().toString().contains("pt_") || link.getAllowedModes().contains(TransportMode.bike)
			|| link.getAttributes().getAttribute("type").toString().contains("highway.motorway")) {
				continue;
			}

			isInsideArea = MGC.coord2Point(link.getFromNode().getCoord()).within(bikeArea) && MGC.coord2Point(link.getToNode().getCoord()).within(bikeArea);

			//if inside shp add bike as allowed mode
			if (isInsideArea) {
				Set<String> allowedModes = new HashSet<>();
				allowedModes.add(TransportMode.bike);
				allowedModes.addAll(link.getAllowedModes());
				link.setAllowedModes(allowedModes);

				linkCount++;
			}
		}

		log.info("For {} links bike has been added as an allowed mode.", linkCount);
	}
}
