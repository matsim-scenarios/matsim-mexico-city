package org.matsim.prepare.population;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.prepare.MexicoCityUtils;
import org.matsim.run.RunMexicoCityScenario;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;

@CommandLine.Command(
	name = "create-commute-relations",
	description = "Create commute trips from municipality to municipality based on survey data"
)
public class CreateCommuterRelations implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateCommuterRelations.class);

	@CommandLine.Option(names = "--od-survey", description = "Path to trips.csv file of OD survey.", required = true)
	private Path surveyPath;

	@CommandLine.Option(names = "--zmvm-shp", description = "Path to metropolitan-area.shp", required = true)
	private Path zmvmShpPath;

	@CommandLine.Option(names = "--survey-shp", description = "Path to origin-destination-survey districts.shp", required = true)
	private Path districtsShpPath;

	@CommandLine.Option(names = "--output", description = "Path to output commuter.csv", required = true)
	private Path output;

	private final CsvOptions csv = new CsvOptions(CSVFormat.Predefined.Default);

	private Map<String, TravelData> commuteTrips = new HashMap<>();

	private final Set<String> invalidDistricts = Set.of("888", "999");

	private Network network = NetworkUtils.createNetwork();

	public static void main(String[] args) {
		new CreateCommuterRelations().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (!MexicoCityUtils.isDefined(zmvmShpPath)) {
			log.error("Shape file with metropolitan area zones is required.");
			return 2;
		}

		if (!MexicoCityUtils.isDefined(districtsShpPath)) {
			log.error("Shape file with origin destination survey zones is required.");
			return 2;
		}

		ShpOptions zmvmShp = new ShpOptions(zmvmShpPath, RunMexicoCityScenario.CRS, null);
		ShpOptions districtsShp = new ShpOptions(districtsShpPath, RunMexicoCityScenario.CRS, null);

		parseTravelSurvey();

//		get centroids of muns and save them as network nodes
		for (SimpleFeature ft : zmvmShp.readFeatures()) {
			Geometry geom = (Geometry) ft.getDefaultGeometry();

			Node node = NetworkUtils.createNode(Id.createNodeId(ft.getAttribute("CVE_MUN1").toString()),
				new Coord(geom.getCentroid().getX(), geom.getCentroid().getY()));

			network.addNode(node);
		}

//		find nearest mun for each dist
		Map<String, Set<String>> mun2Distr = new HashMap<>();

		for (SimpleFeature ft : districtsShp.readFeatures()) {
			String distrId = ft.getAttribute("Distrito").toString();
			Geometry geometry = (Geometry) ft.getDefaultGeometry();

			Node nearestNode = NetworkUtils.getNearestNode(network, new Coord(geometry.getCentroid().getX(), geometry.getCentroid().getY()));

			mun2Distr.putIfAbsent(nearestNode.getId().toString(), new HashSet<>());
			mun2Distr.get(nearestNode.getId().toString()).add(distrId);
		}

		Map<Entry<String, String>, Integer> relations = countCommuteTrips(mun2Distr);

		writeOutputData(relations);
		log.info("{} municipality pairs have been written to {}.", relations.keySet().size(), output);

		return 0;
	}

	private void writeOutputData(Map<Entry<String, String>, Integer> relations) throws IOException {
		BufferedWriter writer = IOUtils.getBufferedWriter(output.toString());

		writer.write("from,to,n");

		for (Entry<Entry<String, String>, Integer> e : relations.entrySet()) {
			Entry<String, String> depArrPair = e.getKey();

			writer.newLine();
			writer.write(depArrPair.getKey() + "," + depArrPair.getValue() + "," + e.getValue().toString());
		}
		writer.close();
	}

	private Map<Entry<String, String>, Integer> countCommuteTrips(Map<String, Set<String>> mun2Distr) {
		Map<Entry<String, String>, Integer> relations = new HashMap<>();

		for (TravelData e : commuteTrips.values()) {
			String depMun = null;
			String arrMun = null;
			for (Entry<String, Set<String>> entry : mun2Distr.entrySet()) {

				Set<String> v = entry.getValue();
				if (v.contains(e.arrivalDistrict)) {
					arrMun = entry.getKey();
				}
				if (v.contains(e.departureDistrict)) {
					depMun = entry.getKey();
				}

				if (arrMun != null && depMun != null && !arrMun.equals(depMun)) {
//					trips from mun to same mun are ignored -> no commute
					Entry<String, String> relation = Map.entry(depMun, arrMun);

					relations.putIfAbsent(relation, 0);
					relations.replace(relation, relations.get(relation), relations.get(relation) + e.weight);
					break;
				}
			}
		}
		return relations;
	}

	private void parseTravelSurvey() {
		try (CSVParser parser = csv.createParser(surveyPath)) {
			for (CSVRecord row : parser) {
				int purpose = Integer.parseInt(row.get("p5_13"));

				//for commuting only purposes home, work and study are relevant
				if (purpose > 3) {
					continue;
				}
				String depDistr = row.get("dto_origen");
				String arrDistr = row.get("dto_dest");

//				some district codes are "outside of metropolitan area" or unknown"
				if (!(invalidDistricts.contains(depDistr) || invalidDistricts.contains(arrDistr) || arrDistr.equals(depDistr))) {
					String id = row.get("id_via");

					int weight = Integer.parseInt(row.get("factor"));
					commuteTrips.put(id, new TravelData(depDistr, arrDistr, weight));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private record TravelData(String departureDistrict, String arrivalDistrict, int weight) {

	}
}
