package org.matsim.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.referencing.operation.transform.IdentityTransform;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CountsOption;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.prepare.counts.NetworkIndex;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.counts.Count;
import org.matsim.counts.Counts;
import org.matsim.counts.CountsWriter;
import org.opengis.referencing.operation.TransformException;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@CommandLine.Command(name = "counts", description = "Create MATSim counts from count station csv data.")
public class CreateCountsFromDatosVialesPortal implements MATSimAppCommand {

	@CommandLine.Option(names = "--input", description = "input count data directory", required = true)
	Path input;

	@CommandLine.Option(names = "--network", description = "MATSim network file path", required = true)
	Path networkPath;

	@CommandLine.Option(names = "--network-geometries", description = "network geometry file path", required = true)
	private Path geometries;

	@CommandLine.Option(names = "--output", description = "output directory", defaultValue = "input/")
	Path output;

	@CommandLine.Option(names = "--scenario", description = "scenario name for output files", defaultValue = "mexico-city-v1.0")
	String scenario;

	@CommandLine.Option(names = "--year", description = "year of count data", defaultValue = "2017")
	int year;

	@CommandLine.Mixin
	private final CrsOptions crs = new CrsOptions();

	@CommandLine.Mixin
	CountsOption counts = new CountsOption();

	private final Map<String, Station> stations = new HashMap<>();
	private final Logger log = LogManager.getLogger(CreateCountsFromDatosVialesPortal.class);

	public static void main(String[] args) {
		new CreateCountsFromDatosVialesPortal().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		String outputString = !output.toString().endsWith("/") || !output.toString().endsWith("\\") ? output + "/" : output.toString();

		//Create Counts Objects
		Counts<Link> car = new Counts<>();
		car.setName(scenario + " car counts");
		car.setDescription("Car counts based on data from mexico city open count data, derived from http://datosviales2020.routedev.mx/main");
		car.setYear(year);

		//Get filepaths
		List<Path> countPaths = new ArrayList<>();

		try (Stream<Path> inputStream = Files.walk(input)) {
			for (Path path : inputStream.toList()) {
				//data is stored as .csv files of each stations
				if (path.toString().endsWith(".csv"))
					countPaths.add(path);
			}
		}

		if (countPaths.isEmpty()) {
			log.warn("No station data were provided. Return Code 1");
			return 1;
		}

		readCountData(countPaths);
		matchWithNetwork(networkPath, geometries, stations, counts);

		stations.values().stream().forEach(s -> {
			Count<Link> carCount = car.createAndAddCount(s.linkAtomicReference().get().getId(), s.id);
//			there are no hourly values in the count data. Therefore, the daily average for the year (TDPA) is set as daily value
			carCount.createVolume(1, s.volumes.motorizedVolume);
//			apparently MATSim needs values for each hour, so for the rest of the day 0 is added as count value
			for (int i = 2; i <= 24; i++) {
				carCount.createVolume(i, 0.);
			}
			carCount.setCoord(s.coord);
		});

		String outputFile = outputString + scenario + ".counts_car." + year + ".xml";

		new CountsWriter(car).write(outputFile);
		log.info("Counts have successfully been written to {}.", outputFile);

		return 0;
	}

	private void matchWithNetwork(Path networkPath, Path geometries, Map<String, Station> stations, CountsOption countsOption) throws TransformException, IOException {

		Network network = NetworkUtils.readNetwork(networkPath.toString());
		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(network);

		Network carNetwork = NetworkUtils.createNetwork();
		filter.filter(carNetwork, Set.of(TransportMode.car));

		Map<Id<Link>, Geometry> networkGeometries = NetworkIndex.readGeometriesFromSumo(geometries.toString(), IdentityTransform.create(2));
		NetworkIndex<Station> index = new NetworkIndex<>(carNetwork, networkGeometries, 50, toMatch -> {
			Coord coord = toMatch.coord();
			return MGC.coord2Point(coord);
		});
		//Add link direction filter
		index.addLinkFilter((link, station) -> {
			String direction = station.direction().get();

			Coord from = link.link().getFromNode().getCoord();
			Coord to = link.link().getToNode().getCoord();

			String linkDir = getDirection(to, from);

			Pattern pattern = Pattern.compile(direction, Pattern.CASE_INSENSITIVE);

			return pattern.matcher(linkDir).find();
		});
		index.addLinkFilter(((link, station) -> !link.link().getId().toString().startsWith("pt_")));

		log.info("Start matching stations to network.");
		Map<String, Station> notMatched = new HashMap<>();

		for (var it = stations.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, Station> next = it.next();

			//Check for manual matching!
			Id<Link> manuallyMatched = countsOption.isManuallyMatched(next.getKey());
			if (manuallyMatched != null) {
				if (!carNetwork.getLinks().containsKey(manuallyMatched))
					throw new NoSuchElementException("Link " + manuallyMatched + " is not in the network!");
				Link link = carNetwork.getLinks().get(manuallyMatched);
				next.getValue().linkAtomicReference().set(link);
				index.remove(link);
				continue;
			}

			Link query = index.query(next.getValue());

			if (query == null) {
				notMatched.put(next.getKey(), next.getValue());
				it.remove();
				continue;
			}

			next.getValue().linkAtomicReference().set(query);
			index.remove(query);
		}

		if (!notMatched.isEmpty()) {
			log.info("Could not match {} stations to a network link. Please consider matching the following stations manually:", notMatched.size());
			notMatched.values().forEach(log::info);
		}
	}

	private String getDirection(Coord to, Coord from) {

		String direction = "";

		if (to.getY() > from.getY()) {
			direction += "north";
		} else
			direction += "south";

		if (to.getX() > from.getX()) {
			direction += "east";
		} else
			direction += "west";

		return direction;
	}

	private void readCountData(List<Path> paths) throws IOException {

		//Read all files and build one record collection
		log.info("Start parsing count data.");

		CSVFormat.Builder builder = CSVFormat.Builder.create(CSVFormat.DEFAULT);
		builder.setQuote(null);
		builder.setHeader();

		for (Path path : paths) {
			try (CSVParser parser = new CSVParser(IOUtils.getBufferedReader(path.toUri().toURL()), builder.build())) {
//				every file consists of only 1 record -> 1 station = 1 file

				CSVRecord rec = parser.getRecords().get(0);

				Coord coord = crs.getTransformation().transform(new Coord(Double.parseDouble(rec.get("LONG")), Double.parseDouble(rec.get("LAT"))));

				String id = rec.get(1) + "_" + coord.getX() + "_" + coord.getY() + "_" + rec.get("SC") + "_" + Double.parseDouble(rec.get("KM"));
				long volume = Long.parseLong(rec.get(24).replace("\"", ""));
				double carFactor = Double.parseDouble(rec.get("AUTOS")) / 100;

				if (!stations.isEmpty() && stations.containsKey(id)) {
					throw new IllegalArgumentException("Station id {} is not unique, please check data." + id);
				}

				stations.put(id, new Station(id, coord,
					Integer.parseInt(rec.get("SC")), Double.parseDouble(rec.get("KM")), rec.get(1), getVolumeData(volume, carFactor)));
			} catch (IOException e) {
				throw new IOException("Error processing file {}: " + path);
			}
		}

		Map<String, Station> newStations = new HashMap<>();

//		for roads with id 44 and 17 there is only one station -> determination of direction by using other stations of the same road does not work
//		for roads 44, 17, 170 and 171 the flowDirection goes against the kilometer count of the road instead of with it (as for all other roads)
		List<String> manualRoads = List.of("44", "17");
		List<String> oppositeFlowDirRoads = List.of("171", "170");

		for (Map.Entry<String, Station> e : stations.entrySet()) {
			if (manualRoads.contains(e.getValue().roadId)) {

				assignDirectionManually(e, newStations);
				continue;
			}

			int flowDir = e.getValue().flowDirection();

			if (flowDir == 0) {
//				duplicate station + volume 50/50
				for (int i = 1; i<=2; i++) {
					String id = e.getValue().roadId + "_" + e.getValue().coord.getX() + "_" + e.getValue().coord.getY() + "_" + i + "_" + e.getValue().km;
					Station duplicate = new Station(id, e.getValue().coord(), i, e.getValue().km, e.getValue().roadId,
						getVolumeData(e.getValue().volumes.totalVolume / 2, e.getValue().volumes.carFactor));

					determineStationDirection(duplicate, duplicate.flowDirection, oppositeFlowDirRoads);
					newStations.put(id, duplicate);
				}
			} else {
				determineStationDirection(e.getValue(), flowDir, oppositeFlowDirRoads);
			}
		}

//		remove stations with flowDirection 0 and add new duplicated stations instead
		stations.entrySet().removeIf(entry -> entry.getValue().flowDirection == 0);
		stations.putAll(newStations);
	}

	private CountData getVolumeData(long totalVolume, double carFactor) {

		long motorizedVolume = Math.round(totalVolume * carFactor);

		return new CountData(totalVolume, motorizedVolume);
	}

	private void assignDirectionManually(Map.Entry<String, Station> e, Map<String, Station> newStations) {
		Map<String, Station> manualStations = new HashMap<>();

		//duplicate station + volume 50/50
		for (int i = 1; i<=2; i++) {
			String id = e.getValue().roadId + "_" + e.getValue().coord.getX() + "_" + e.getValue().coord.getY() + "_" + i + "_" + e.getValue().km;
			Station duplicate = new Station(id, e.getValue().coord(), i, e.getValue().km, e.getValue().roadId,
				getVolumeData(e.getValue().volumes.totalVolume / 2, e.getValue().volumes.carFactor));

			manualStations.put(id, duplicate);
		}

		for (Station station : manualStations.values()) {
			switch (e.getValue().roadId) {
				case "44" -> {
					if (station.flowDirection == 1) {
						station.direction.set("northwest");
					} else {
						station.direction.set("southeast");
					}
				}
				case "17" -> {
					if (station.flowDirection == 1) {
						station.direction.set("southeast");
					} else {
						station.direction.set("northwest");
					}
				}

				default -> throw new AssertionError("Invalid roadId attribute " + e.getValue().roadId);
			}
		}
		newStations.putAll(manualStations);
	}

	private void determineStationDirection(Station station, int flowDir, List<String> oppositeFlowDirRoads) {
//		find the next station on same carretera based on km count
		Map<String, Station> filtered = stations.entrySet().stream()
			.filter(entry -> entry.getValue().roadId.equals(station.roadId))
			.filter(entry -> entry.getValue().km != station.km)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		Map<String, Double> stationDistances = new HashMap<>();
//		get distances from all filtered stations to current station
		filtered.entrySet().stream().forEach(entry -> stationDistances.put(entry.getKey(), Math.abs(station.km - entry.getValue().km)));

//		get station id of station with the fewest distance to current station
		String nearestStationId = stationDistances.entrySet().stream()
			.sorted(Map.Entry.comparingByValue())
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new))
			.entrySet().iterator().next().getKey();

//		for some exceptional roads, the flowDirection goes against the kilometer count of the road
		if (oppositeFlowDirRoads.contains(station.roadId)) {
			if (flowDir == 1) {
				flowDir = 2;
			} else {
				flowDir = 1;
			}
		}

		Station to = null;
		Station from = null;

		if (flowDir == 1) {
			if (filtered.get(nearestStationId).km > station.km) {
				to = filtered.get(nearestStationId);
				from = station;
			} else {
				to = station;
				from = filtered.get(nearestStationId);
			}
		} else if (flowDir == 2) {
			if (filtered.get(nearestStationId).km > station.km) {
				to = station;
				from = filtered.get(nearestStationId);
			} else {
				to = filtered.get(nearestStationId);
				from = station;
			}
		}

		if (to != null && from != null) {
			station.direction.set(getDirection(to.coord, from.coord));
		} else {
			throw new NullPointerException("Either the origin station (" + to + ") or destination station (" + from + ") are null. This must not happen!");
		}
	}

	private record Station(String id, AtomicReference<String> direction, Coord coord, AtomicReference<Link> linkAtomicReference, int flowDirection, double km, String roadId, CountData volumes) {

		private Station(String id, Coord coord, int flowDirection, double km, String roadId, CountData volumes) {
			this(id, new AtomicReference<>(), coord, new AtomicReference<>(), flowDirection, km, roadId, volumes);
		}
	}

//	motorizedVolume = volume for cars + motorbikes
	private record CountData(long totalVolume, long motorizedVolume, double carFactor) {
		private CountData(long totalVolume, long motorizedVolume) {
			this(totalVolume, motorizedVolume, (double) motorizedVolume / totalVolume);
		}
	}

}
