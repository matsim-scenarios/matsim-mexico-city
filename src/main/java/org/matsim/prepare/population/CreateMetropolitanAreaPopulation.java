package org.matsim.prepare.population;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.MultiPolygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.LanduseOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import org.matsim.prepare.MexicoCityUtils;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;

/**
 * class copied and adapted from.
 * https://github.com/matsim-scenarios/matsim-berlin/blob/6.x/src/main/java/org/matsim/prepare/population/CreateBerlinPopulation.java
 * -sme0923
 **/

@CommandLine.Command(
		name = "zmvm-population",
		description = "Create synthetic population for zmvm (zona metropolitana del valle de mexico)."
)

public class CreateMetropolitanAreaPopulation implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateMetropolitanAreaPopulation.class);

	@CommandLine.Option(names = "--input", description = "Paths to input csv data files. Use comma as delimiter.", required = true)
	private String input;

	@CommandLine.Mixin
	private LanduseOptions landuse = new LanduseOptions();

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;

	@CommandLine.Option(names = "--sample", description = "Sample size to generate", defaultValue = "0.01")
	private double sample;

	@CommandLine.Option(names = "--target-crs", description = "Target CRS", defaultValue = "EPSG:4485")
	private String targetCrs;

	private SplittableRandom rnd;

	private Population population;

	private Map<String, SimpleFeature> municipios = new HashMap<>();

	public static void main(String[] args) {
		new CreateMetropolitanAreaPopulation().execute(args);
	}

	@Override
	@SuppressWarnings("IllegalCatch")
	public Integer call() throws Exception {

		final CoordinateTransformation ct = new GeotoolsTransformation(targetCrs, targetCrs);

		if (shp.getShapeFile() == null) {
			log.error("Shape file with municipios of ZMVM is required.");
			return 2;
		}

		rnd = new SplittableRandom(0);

		population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		for (SimpleFeature ft : shp.readFeatures()) {
			//09 = mexico city. population for mexico city is created in different file.
			if (!ft.getAttribute("CVE_ENT").equals("09")) {
				//0000 is added to match the locID of the csv input files, which are datasets for the whole municipios.
				municipios.putIfAbsent(ft.getAttribute("CVE_MUN1").toString() + "0000", ft);
			}
		}

		log.info("Found {} relevant municipios", municipios.size());

		Map<String, InputData> zmvmData = readCSVData();

		for (Map.Entry<String, SimpleFeature> entry: municipios.entrySet()) {
				processMunicipio(entry.getValue(), ct, zmvmData.get(entry.getKey()));
		}

		log.info("Generated {} persons", population.getPersons().size());

		PopulationUtils.sortPersons(population);

		ProjectionUtils.putCRS(population, MexicoCityUtils.CRS);
		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	private Map<String, InputData> readCSVData() throws IOException {

		List<String> csvPaths = new ArrayList<>(Arrays.asList(input.split(",")));

		Map<String, InputData> id2InputData = new HashMap<>();

		for (String inputString : csvPaths) {

			Path inputPath = Path.of(inputString);

			CSVFormat.Builder format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true);

			try (CSVParser reader = new CSVParser(new InputStreamReader(new BOMInputStream(inputPath.getFileSystem().provider().newInputStream(inputPath)),
				StandardCharsets.UTF_8), format.build())) {

				for (CSVRecord row : reader) {

					//build ID of municipio to check if it matches the IDs of shp file
					String munID = row.get("ENTIDAD") + row.get("MUN") + row.get("LOC");

					if (municipios.containsKey(munID)) {

						List<String> values = Arrays.asList(row.get("POBTOT"), row.get("POB0_14"), row.get("POB15_64"), row.get("POB65_MAS"), row.get("POBFEM"),
							row.get("POBMAS"), row.get("PEA"), row.get("PE_INAC"), row.get("P_12YMAS"));

						List<Integer> parsedValues = handleNonNumerics(values);

						id2InputData.putIfAbsent(munID, new InputData(munID, row.get("ENTIDAD"), row.get("MUN"), parsedValues.get(0), parsedValues.get(1), parsedValues.get(2),
							parsedValues.get(3), parsedValues.get(4), parsedValues.get(5), parsedValues.get(6),
							parsedValues.get(7), parsedValues.get(8)));
					}
				}
			}
		}
		return id2InputData;
	}

	private List<Integer> handleNonNumerics(List<String> values) {

		List<Integer> parsedValues = new ArrayList<>();

		//handle null / * / whatever values
		for (String s : values) {
			if (NumberUtils.isParsable(s)) {
				parsedValues.add(Integer.parseInt(s));
			} else {
				parsedValues.add(0);
			}
		}
		return parsedValues;
	}

	private void processMunicipio(SimpleFeature ft, CoordinateTransformation ct, InputData inputData) {

		log.info("Processing {} with {} inhabitants", inputData.munID, inputData.nInh);

		ValidatedAndProcessedData validatedData = validateDataFields(inputData);

		var sex = new EnumeratedAttributeDistribution<>(Map.of("f", validatedData.quotaFem, "m", validatedData.quotaMasc));
		var economicActivity = new EnumeratedAttributeDistribution<>(Map.of(true, validatedData.quotaEcoAct, false, validatedData.quotaEcoNotAct ));
		var ageGroup = new EnumeratedAttributeDistribution<>(Map.of(
				AgeGroup.YOUNG, validatedData.young,
				AgeGroup.MIDDLE, validatedData.middle,
				AgeGroup.OLD, validatedData.old
		));

		MultiPolygon geom = (MultiPolygon) ft.getDefaultGeometry();

		PopulationFactory f = population.getFactory();

		var youngDist = new UniformAttributeDistribution<>(IntStream.range(0, 14).boxed().toList());
		var middleDist = new UniformAttributeDistribution<>(IntStream.range(15, 64).boxed().toList());
		var oldDist = new UniformAttributeDistribution<>(IntStream.range(65, 100).boxed().toList());

		for (int i = 0; i < inputData.nInh * sample; i++) {

			Person person = f.createPerson(generateId(population, "zmvm", rnd));
			PersonUtils.setSex(person, sex.sample());
			PopulationUtils.putSubpopulation(person, "person");

			AgeGroup group = ageGroup.sample();

			if (group == AgeGroup.MIDDLE) {
				PersonUtils.setAge(person, middleDist.sample());
				PersonUtils.setEmployed(person, economicActivity.sample());
			} else if (group == AgeGroup.YOUNG) {
				PersonUtils.setAge(person, youngDist.sample());
				PersonUtils.setEmployed(person, false);
			} else if (group == AgeGroup.OLD) {
				PersonUtils.setAge(person, oldDist.sample());
				PersonUtils.setEmployed(person, false);
			}

			Coord coord = ct.transform(sampleHomeCoordinate(geom, targetCrs, landuse, rnd));

			person.getAttributes().putAttribute(MexicoCityUtils.HOME_X, coord.getX());
			person.getAttributes().putAttribute(MexicoCityUtils.HOME_Y, coord.getY());

			person.getAttributes().putAttribute(MexicoCityUtils.ENT, inputData.ent);
			person.getAttributes().putAttribute(MexicoCityUtils.MUN, inputData.mun);

			Plan plan = f.createPlan();
			plan.addActivity(f.createActivityFromCoord("home", coord));

			person.addPlan(plan);
			person.setSelectedPlan(plan);

			population.addPerson(person);
		}
	}

	private ValidatedAndProcessedData validateDataFields(InputData data) {

		//population of manzana 0-14 years old
		double young = (double) data.nYoung / data.nInh;
		//population of manzana 15-64 years old
		double middle = (double) data.nMiddle / data.nInh;
		//population of manzana  65 or more years old
		 double old = (double) data.nOld / data.nInh;

		// calc quota for age gropus
		if ((young + middle + old) != 1.0) {
			//if quotas for ageGroups do not add up to 1.0 -> use sum of all ageGroups as noInh
			//discrepancy between sum of all ageGroups and nInh for municipios is max 0.003
			young = (double) data.nYoung / (data.nYoung + data.nMiddle + data.nOld);
			middle = (double) data.nMiddle / (data.nYoung + data.nMiddle + data.nOld);
			old = (double) data.nOld / (data.nYoung + data.nMiddle + data.nOld);
		}

		// calc quota for men / women. There seems to be no data on diverse people..
		double quotaFem = (double) data.nFem / data.nInh;
		double quotaMasc = (double) data.nMasc / data.nInh;

		if (data.nFem == 0 && data.nMasc == 0 || (quotaFem + quotaMasc) != 1.0) {
			//if quotas for gender do not add up to 1.0 -> apply general values for states mexico state and hidalgo
			//source mexico state: https://www.inegi.org.mx/contenidos/saladeprensa/boletines/2021/EstSociodemo/ResultCenso2020_EdMx.pdf
			//source hidalgo: https://www.inegi.org.mx/contenidos/productos/prod_serv/contenidos/espanol/bvinegi/productos/nueva_estruc/702825197865.pdf
			switch (data.ent) {
				case "13" -> {
					//hidalgo
					quotaFem = 0.52;
					quotaMasc = 0.48;
				}
				case "15" -> {
					//mexico state
					quotaFem = 0.51;
					quotaMasc = 0.49;
				}
				default -> throw new IllegalStateException();
			}
		}

		double quotaEcoAct = (double) data.nEcoAct / data.nAgeTwelveAndMore;
		double quotaEcoNotAct = (double) data.nEcoNotAct / data.nAgeTwelveAndMore;

		//calc quota of economically active inhabitants (12+ years)
		if (data.nEcoNotAct == 0 && data.nEcoAct == 0 || (quotaEcoNotAct + quotaEcoAct) != 1.0) {
			//if quotas for eco act do not add up to 1.0 -> apply general values for states mexico state and hidalgo
			//source mexico state: https://www.inegi.org.mx/contenidos/saladeprensa/boletines/2021/EstSociodemo/ResultCenso2020_EdMx.pdf
			//source hidalgo: https://www.inegi.org.mx/contenidos/productos/prod_serv/contenidos/espanol/bvinegi/productos/nueva_estruc/702825197865.pdf
			switch (data.ent) {
				case "13" -> {
					//hidalgo
					quotaEcoAct = 0.611;
					quotaEcoNotAct = 1 - quotaEcoAct;
				}
				case "15" -> {
					//mexico state
					quotaEcoAct = 0.62;
					quotaEcoNotAct = 1 - quotaEcoAct;
				}
				default -> throw new IllegalStateException();
			}
		}
		return new ValidatedAndProcessedData(data.munID, young, middle, old, quotaFem, quotaMasc, quotaEcoAct, quotaEcoNotAct);
	}

	/**
	 * Generate a new unique id within population.
	 */
	public static Id<Person> generateId(Population population, String prefix, SplittableRandom rnd) {

		Id<Person> id;
		byte[] bytes = new byte[4];
		do {
			rnd.nextBytes(bytes);
			id = Id.createPersonId(prefix + "_" + HexFormat.of().formatHex(bytes));

		} while (population.getPersons().containsKey(id));

		return id;
	}


	/**
	 * Samples a home coordinates from geometry and landuse (if provided).
	 */
	public static Coord sampleHomeCoordinate(MultiPolygon geometry, String crs, LanduseOptions landuse, SplittableRandom rnd) {

		Envelope bbox = geometry.getEnvelopeInternal();

		int i = 0;
		Coord coord;
		do {
			coord = landuse.select(crs, () -> new Coord(
					bbox.getMinX() + (bbox.getMaxX() - bbox.getMinX()) * rnd.nextDouble(),
					bbox.getMinY() + (bbox.getMaxY() - bbox.getMinY()) * rnd.nextDouble()
			));

			i++;

		} while (!geometry.contains(MGC.coord2Point(coord)) && i < 1500);

		if (i == 1500)
			log.warn("Invalid coordinate generated");

		return MexicoCityUtils.roundCoord(coord);
	}

	private enum AgeGroup {
		YOUNG,
		MIDDLE,
		OLD
	}

	private record InputData(String munID, String ent, String mun, int nInh, int nYoung, int nMiddle, int nOld, int nFem, int nMasc, int nEcoAct, int nEcoNotAct, int nAgeTwelveAndMore) {

	}

	private record ValidatedAndProcessedData(String munID, double young, double middle, double old, double quotaFem, double quotaMasc, double quotaEcoAct, double quotaEcoNotAct) {

	}

}
