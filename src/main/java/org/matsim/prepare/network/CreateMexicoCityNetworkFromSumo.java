package org.matsim.prepare.network;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectReferencePair;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.lanes.*;
import org.matsim.utils.objectattributes.attributable.Attributable;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

@CommandLine.Command(
		name = "network-from-sumo-mexico-city",
		description = "Create MATSim network from a SUMO network",
		showDefaultValues = true
)
public final class CreateMexicoCityNetworkFromSumo implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateNetworkFromSumo.class);

	/**
	 * Capacities below this threshold are unplausible and ignored.
	 */
	private static final double CAPACITY_THRESHOLD = 300;

	@CommandLine.Parameters(arity = "1..*", paramLabel = "INPUT", description = "Input file")
	private List<Path> input;

	@CommandLine.Option(names = "--output", description = "Output xml file", required = true)
	private Path output;

	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();

	@CommandLine.Mixin
	private final CrsOptions crs = new CrsOptions();

	@CommandLine.Option(names = {"--capacities"}, description = "CSV file with lane capacities", required = false)
	private Path capacities;

	@CommandLine.Option(names = "--free-speed-factor", description = "Free-speed reduction for urban links")
	private double freeSpeedFactor = LinkProperties.DEFAULT_FREESPEED_FACTOR;

	public static void main(String[] args) {
		System.exit(new CommandLine(new CreateNetworkFromSumo()).execute(args));
	}

	@Override
	public Integer call() throws Exception {

//		since ShpOptions.getShapeFile() no longer is a path but a string, we have to check if it is defined before creating SumoNetworkConverter to
//		preserve the possibility to run the converter without a shp file, otherwise, when calling Path.of(shp.getShapeFile) a NullPointerException is caused -sme0324
		Path path = null;

		if (shp.isDefined()) {
			path = Path.of(shp.getShapeFile());
		}

		MexicoCitySumoNetworkConverter converter = MexicoCitySumoNetworkConverter.newInstance(input, output, path, crs.getInputCRS(), crs.getTargetCRS(), freeSpeedFactor);

		Network network = NetworkUtils.createNetwork();
		Lanes lanes = LanesUtils.createLanesContainer();

		MexicoCitySumoNetworkHandler handler = converter.convert(network, lanes);

		converter.calculateLaneCapacities(network, lanes);

		// This needs to run without errors, otherwise network is broken
		network.getLinks().values().forEach(link -> {
			LanesToLinkAssignment l2l = lanes.getLanesToLinkAssignments().get(link.getId());
			if (l2l != null)
				LanesUtils.createLanes(link, l2l);
		});

		if (capacities != null) {

			Object2DoubleMap<Triple<Id<Link>, Id<Link>, Id<Lane>>> map = readLaneCapacities(capacities);

			log.info("Read lane capacities from {}, containing {} lanes", capacities, map.size());

			int n = setLinkCapacities(network, map);
			int n2 = setLaneCapacities(lanes, map);

			log.info("Unmatched links: {}, lanes: {}", n, n2);

		}

		if (crs.getTargetCRS() != null)
			ProjectionUtils.putCRS(network, crs.getTargetCRS());

		NetworkUtils.writeNetwork(network, output.toAbsolutePath().toString());
		new NetworkWriter(network).write(output.toAbsolutePath().toString());
		new LanesWriter(lanes).write(output.toAbsolutePath().toString().replace(".xml", "-lanes.xml"));

		converter.writeGeometry(handler, output.toAbsolutePath().toString().replace(".xml", "-linkGeometries.csv").replace(".gz", ""));

		return 0;
	}

	/**
	 * Read lane capacities from csv file.
	 *
	 * @return triples of fromLink, toLink, fromLane
	 */
	public static Object2DoubleMap<Triple<Id<Link>, Id<Link>, Id<Lane>>> readLaneCapacities(Path input) {

		Object2DoubleMap<Triple<Id<Link>, Id<Link>, Id<Lane>>> result = new Object2DoubleOpenHashMap<>();

		try (CSVParser parser = new CSVParser(IOUtils.getBufferedReader(input.toString()),
				CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {

			for (CSVRecord csvRecord : parser) {

				Id<Link> fromLinkId = Id.create(csvRecord.get("fromEdgeId"), Link.class);
				Id<Link> toLinkId = Id.create(csvRecord.get("toEdgeId"), Link.class);
				Id<Lane> fromLaneId = Id.create(csvRecord.get("fromLaneId"), Lane.class);

				Triple<Id<Link>, Id<Link>, Id<Lane>> key = Triple.of(fromLinkId, toLinkId, fromLaneId);

				result.mergeDouble(key, Integer.parseInt(csvRecord.get("intervalVehicleSum")), Double::sum);

			}

		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return result;
	}

	/**
	 * Aggregate maximum lane capacities, independent of turning direction.
	 */
	public static Object2DoubleMap<Pair<Id<Link>, Id<Lane>>> calcMaxLaneCapacities(Object2DoubleMap<Triple<Id<Link>, Id<Link>, Id<Lane>>> map) {

		Object2DoubleMap<Pair<Id<Link>, Id<Lane>>> laneCapacities = new Object2DoubleOpenHashMap<>();

		// sum for each link
		for (Object2DoubleMap.Entry<Triple<Id<Link>, Id<Link>, Id<Lane>>> e : map.object2DoubleEntrySet()) {
			laneCapacities.mergeDouble(ObjectReferencePair.of(e.getKey().getLeft(), e.getKey().getRight()), e.getDoubleValue(), Double::max);
		}

		return laneCapacities;
	}

	/**
	 * Use provided lane capacities, to calculate aggregated capacities for all links.
	 * This does not modify lane capacities.
	 *
	 * @return number of links from file that are not in the network.
	 */
	public static int setLinkCapacities(Network network, Object2DoubleMap<Triple<Id<Link>, Id<Link>, Id<Lane>>> map) {

		Object2DoubleMap<Id<Link>> linkCapacities = new Object2DoubleOpenHashMap<>();
		Object2DoubleMap<Pair<Id<Link>, Id<Lane>>> laneCapacities = calcMaxLaneCapacities(map);

		// sum for each link
		for (Object2DoubleMap.Entry<Pair<Id<Link>, Id<Lane>>> e : laneCapacities.object2DoubleEntrySet()) {
			linkCapacities.mergeDouble(e.getKey().key(), e.getDoubleValue(), Double::sum);
		}

		int unmatched = 0;

		for (Object2DoubleMap.Entry<Id<Link>> e : linkCapacities.object2DoubleEntrySet()) {

			Link link = network.getLinks().get(e.getKey());

			// ignore unplausible capacities
			if (e.getDoubleValue() < CAPACITY_THRESHOLD)
				continue;

			if (link != null) {
				link.setCapacity(e.getDoubleValue());
				link.getAttributes().putAttribute("junction", true);
			} else {
				unmatched++;
			}
		}

		Object2DoubleMap<Pair<Id<Link>, Id<Link>>> turnCapacities = new Object2DoubleOpenHashMap<>();

		for (Object2DoubleMap.Entry<Triple<Id<Link>, Id<Link>, Id<Lane>>> e : map.object2DoubleEntrySet()) {
			turnCapacities.mergeDouble(Pair.of(e.getKey().getLeft(), e.getKey().getMiddle()), e.getDoubleValue(), Double::sum);
		}

		// set turn capacities relative to whole link capacity
		for (Object2DoubleMap.Entry<Pair<Id<Link>, Id<Link>>> e : turnCapacities.object2DoubleEntrySet()) {

			Id<Link> fromLink = e.getKey().left();
			Id<Link> toLink = e.getKey().right();

			Link link = network.getLinks().get(fromLink);

			if (link == null)
				continue;

			getTurnEfficiencyMap(link).put(toLink.toString(), String.valueOf(e.getDoubleValue() / link.getCapacity()));
		}


		return unmatched;
	}

	/**
	 * Use provided lane capacities and apply them in the network.
	 *
	 * @return number of lanes in file, but not in the network.
	 */
	public static int setLaneCapacities(Lanes lanes, Object2DoubleMap<Triple<Id<Link>, Id<Link>, Id<Lane>>> map) {

		Object2DoubleMap<Pair<Id<Link>, Id<Lane>>> laneCapacities = calcMaxLaneCapacities(map);

		int unmatched = 0;

		SortedMap<Id<Link>, LanesToLinkAssignment> l2ls = lanes.getLanesToLinkAssignments();

		for (Object2DoubleMap.Entry<Pair<Id<Link>, Id<Lane>>> e : laneCapacities.object2DoubleEntrySet()) {

			LanesToLinkAssignment l2l = l2ls.get(e.getKey().key());

			if (l2l == null) {
				unmatched++;
				continue;
			}

			Lane lane = l2l.getLanes().get(e.getKey().right());

			if (lane == null) {
				unmatched++;
				continue;
			}

			// ignore unplausible capacities
			if (e.getDoubleValue() < CAPACITY_THRESHOLD)
				continue;

			lane.setCapacityVehiclesPerHour(e.getDoubleValue());
		}

		// set turn efficiency depending on to link
		for (Object2DoubleMap.Entry<Triple<Id<Link>, Id<Link>, Id<Lane>>> e : map.object2DoubleEntrySet()) {

			LanesToLinkAssignment l2l = l2ls.get(e.getKey().getLeft());
			if (l2l == null) continue;

			Lane lane = l2l.getLanes().get(e.getKey().getRight());
			if (lane == null) continue;

			Id<Link> toLink = e.getKey().getMiddle();
			getTurnEfficiencyMap(lane).put(toLink.toString(), String.valueOf(e.getDoubleValue() / lane.getCapacityVehiclesPerHour()));
		}


		return unmatched;
	}

	/**
	 * Retrieves turn efficiency from attributes.
	 */
	private static Map<String, String> getTurnEfficiencyMap(Attributable obj) {
		Map<String, String> cap = (Map<String, String>) obj.getAttributes().getAttribute("turnEfficiency");
		if (cap == null) {
			cap = new HashMap<>();
			obj.getAttributes().putAttribute("turnEfficiency", cap);
		}

		return cap;
	}

}