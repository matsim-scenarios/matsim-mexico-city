package org.matsim.prepare.population;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.facilities.*;
import org.matsim.prepare.MexicoCityUtils;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.matsim.prepare.MexicoCityUtils.roundCoord;

@CommandLine.Command(
	name = "facilities",
	description = "Creates MATSim facilities from shape-file and network"
)
public class CreateMATSimFacilities implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateMATSimFacilities.class);

	@CommandLine.Option(names = "--network", required = true, description = "Path to network. Will be filtered for mode car later.")
	private Path network;

	@CommandLine.Option(names = "--output", required = true, description = "Path to output facility file")
	private Path output;

	@CommandLine.Mixin
	private ShpOptions shp;

	/**
	 * Filter link types that don't have a facility associated.
	 */
	public static final Set<String> IGNORED_LINK_TYPES = Set.of("highway.motorway", "highway.trunk",
		"highway.motorway_link", "highway.trunk_link", "highway.secondary_link", "highway.primary_link", "highway.unclassified");

	private final Set<String> work = Set.of("11", "21", "22", "23", "31", "32", "33", "43", "48", "49", "51", "52", "53", "54", "55", "56", "62", "81", "93");
	private final Set<String> shopDaily = Set.of("461", "464");
	private final Set<String> eduBasic = Set.of("61112", "61113", "61114");
	private final Set<String> eduOther = Set.of("61115", "61117", "61118", "61121", "61141", "61142", "61143", "61151", "61161", "61162", "61163", "61169", "61171");
	private final Set<String> eduHigher = Set.of("61116", "61121", "61131", "61142", "61143", "61161");

	private static final String ATTR_NAME = "codigo_act";


	public static void main(String[] args) {
		new CreateMATSimFacilities().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (!MexicoCityUtils.isDefined(shp.getShapeFile())) {
			log.error("Shp file with facility locations is required.");
			return 2;
		}

		Network completeNetwork = NetworkUtils.readNetwork(this.network.toString());
		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(completeNetwork);
		Network carOnlyNetwork = NetworkUtils.createNetwork();
		filter.filter(carOnlyNetwork, Set.of(TransportMode.car));

//		ignored link types are filtered out
		NetworkFilterManager filterManager = new NetworkFilterManager(carOnlyNetwork, new NetworkConfigGroup());
		filterManager.addLinkFilter(link -> !IGNORED_LINK_TYPES.contains(NetworkUtils.getType(link)));
		Network filteredNetwork = filterManager.applyFilters();

		List<SimpleFeature> fts = shp.readFeatures();

		Map<Id<Link>, Holder> data = new ConcurrentHashMap<>();

		fts.parallelStream().forEach(ft -> processFeature(ft, filteredNetwork, data));

		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();

		ActivityFacilitiesFactory f = facilities.getFactory();

		for (Map.Entry<Id<Link>, Holder> e : data.entrySet()) {

			Holder h = e.getValue();

			Id<ActivityFacility> id = Id.create(String.join("_", h.ids), ActivityFacility.class);

			// Create mean coordinate
			OptionalDouble x = h.coords.stream().mapToDouble(Coord::getX).average();
			OptionalDouble y = h.coords.stream().mapToDouble(Coord::getY).average();

			if (x.isEmpty() || y.isEmpty()) {
				log.warn("Empty coordinate (Should never happen)");
				continue;
			}

			ActivityFacility facility = f.createActivityFacility(id, roundCoord(new Coord(x.getAsDouble(), y.getAsDouble())));
			for (String act : h.activities) {
				facility.addActivityOption(f.createActivityOption(act));
			}

			facilities.addActivityFacility(facility);
		}

		log.info("Created {} facilities, writing to {}", facilities.getFacilities().size(), output);

		FacilitiesWriter writer = new FacilitiesWriter(facilities);
		writer.write(output.toString());

		return 0;
	}

	/**
	 * Sample points and choose link with the nearest points. Aggregate everything so there is at most one facility per link.
	 */
	private void processFeature(SimpleFeature ft, Network network, Map<Id<Link>, Holder> data) {

		// Actual id is the last part
		String[] id = ft.getID().split("\\.");
		Point p = (Point) ft.getDefaultGeometry();

		List<Coord> coords = List.of(new Coord(p.getX(), p.getY()));
		List<Id<Link>> links = coords.stream().map(coord -> NetworkUtils.getNearestLinkExactly(network, coord).getId()).toList();

		Map<Id<Link>, Long> map = links.stream()
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

		if (map.isEmpty())
			return;

		List<Map.Entry<Id<Link>, Long>> counts = map.entrySet().stream().sorted(Map.Entry.comparingByValue())
			.toList();

		// The "main" link of the facility
		Id<Link> link = counts.get(counts.size() - 1).getKey();

		Holder holder = data.computeIfAbsent(link, k -> new Holder(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), Collections.synchronizedList(new ArrayList<>())));

//		cap id length, because with too many datapoints the facility id exceeds the allowed char count for csv files (is relevant for MATSim analysis like TripAnalysis)
		if (holder.ids.size() <= 5) {
			holder.ids.add(id[id.length - 1]);
		}

		holder.activities.addAll(activities(ft));

		// Search for the original drawn coordinate of the associated link
		for (int i = 0; i < links.size(); i++) {
			if (links.get(i).equals(link)) {
				holder.coords.add(coords.get(i));
				break;
			}
		}
	}

	private Set<String> activities(SimpleFeature ft) {

//		classification of attr codigo_act: https://www.inegi.org.mx/scian/
		Set<String> act = new HashSet<>();

		if (work.contains(ft.getAttribute(ATTR_NAME).toString().split(getPattern(2))[0])) {
			act.add("work");
			act.add("work_business");
		}
		if (ft.getAttribute(ATTR_NAME).toString().startsWith("46")) {
			act.add("shop_other");
			if (shopDaily.contains(ft.getAttribute(ATTR_NAME).toString().split(getPattern(4))[0])) {
				act.add("shop_daily");
			}
		}
		if (ft.getAttribute(ATTR_NAME).toString().startsWith("61111")) {
			act.add("edu_kiga");
		}
		if (eduBasic.contains(ft.getAttribute(ATTR_NAME).toString().split(getPattern(5))[0])) {
			act.add("edu_primary");
			act.add("edu_secondary");
		}
		if (eduHigher.contains(ft.getAttribute(ATTR_NAME).toString().split(getPattern(5))[0])) {
			act.add("edu_higher");
		}
		if (eduOther.contains(ft.getAttribute(ATTR_NAME).toString().split(getPattern(5))[0])) {
			act.add("edu_other");
		}
		if (ft.getAttribute(ATTR_NAME).toString().startsWith("62")) {
			act.add("personal_business");
		}
		if (ft.getAttribute(ATTR_NAME).toString().startsWith("71")) {
			act.add("leisure");
		}
		if (ft.getAttribute(ATTR_NAME).toString().startsWith("721")) {
			act.add("leisure");
		}
		if (ft.getAttribute(ATTR_NAME).toString().startsWith("722")) {
			act.add("dining");
		}
		return act;
	}

	private String getPattern(Integer index) {
		return "(?<=\\G.{" + index + "})";
	}

	/**
	 * Temporary data holder for facilities.
	 */
	private record Holder(Set<String> ids, Set<String> activities, List<Coord> coords) {

	}

}
