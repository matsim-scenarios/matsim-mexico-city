package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
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

		if (shp.getShapeFile() == null) {
			shp = new ShpOptions(Path.of("input/v1.0/area/area.shp"), null, null);
		}

		prepareBikeOnNetwork(network, shp);
		prepareRepurposeCarLanesNetwork(network);

		NetworkUtils.writeNetwork(network, outputPath);

		return 0;
	}

	/**
	 * prepare network for modelling bike as network mode.
	 */
	public static void prepareBikeOnNetwork(Network network, ShpOptions shp) {
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

		new MultimodalNetworkCleaner(network).run(Set.of(TransportMode.bike));
	}

	/**
	 * prepare network for policy case, where for links with numberOfLanes > 1 one lane will be repurposed from car to bike.
	 */
	public static void prepareRepurposeCarLanesNetwork(Network network) {

		int count = 0;

		NetworkFactory fac = network.getFactory();

		Set<Link> bikeLinks = new HashSet<>();

		for (Link link : network.getLinks().values()) {
			if (link.getId().toString().contains("pt_") || link.getAttributes().getAttribute("type").toString().contains("highway.motorway")) {
				continue;
			}

			if (link.getAllowedModes().contains(TransportMode.car) && link.getNumberOfLanes() > 1) {
//				create a copy of the link but only for bike
				Link bikeLink = fac.createLink(Id.createLinkId("bike_" + link.getId()), link.getFromNode(), link.getToNode());

//				mb this value should be multiplied by the passenger car equivalent of bike?
				double bikeCapacity = link.getCapacity() / link.getNumberOfLanes();

				bikeLink.setAllowedModes(Set.of(TransportMode.bike));
				bikeLink.setCapacity(bikeCapacity);
				bikeLink.setFreespeed(link.getFreespeed());
				bikeLink.setNumberOfLanes(1);
				bikeLink.setLength(link.getLength());

				bikeLinks.add(bikeLink);

//				adapt "old" link
				if (link.getAllowedModes().contains(TransportMode.bike)) {
					Set<String> modes = new HashSet<>();

					link.getAllowedModes().forEach(m -> {
						if (!m.equals(TransportMode.bike)) {
							modes.add(m);
						}
					});
					link.setAllowedModes(modes);
				}
				link.setCapacity(link.getCapacity() - bikeCapacity);
				link.setNumberOfLanes(link.getNumberOfLanes() - 1);

				count++;
			}
		}

		bikeLinks.forEach(network::addLink);

		log.info("For {} links 1 lane has been removed. The lane is then repurposed as a copied link, where only bikes have access. " +
			"Capacity and number of lanes of the original link are reduced accordingly.", count);

		new MultimodalNetworkCleaner(network).run(Set.of(TransportMode.bike));
	}
}
