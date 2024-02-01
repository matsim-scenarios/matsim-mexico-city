package org.matsim.prepare.population;

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

import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;

/**
 * class copied and adapted from.
 * https://github.com/matsim-scenarios/matsim-berlin/blob/6.x/src/main/java/org/matsim/prepare/population/CreateBerlinPopulation.java
 * -sme0923
 **/

@CommandLine.Command(
		name = "mexico-city-population",
		description = "Create synthetic population for mexico-city."
)

public class CreateMexicoCityPopulation implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateMexicoCityPopulation.class);

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

	//name of attribute, which contains number of inhabitants
	private String noInhAttrName = "POBTOT";

	private Map<String, Double> ageGroupDistr = new HashMap<>();
	private List<SimpleFeature> manzanas = new ArrayList<>();

	public static void main(String[] args) {
		new CreateMexicoCityPopulation().execute(args);
	}

	@Override
	@SuppressWarnings("IllegalCatch")
	public Integer call() throws Exception {

		final CoordinateTransformation ct = new GeotoolsTransformation(targetCrs, targetCrs);

		if (MexicoCityUtils.isDefined(shp.getShapeFile())) {
			log.error("Shape file with manzanas is required.");
			return 2;
		}

		log.info("Found {} manzanas", shp.readFeatures().size());

		rnd = new SplittableRandom(0);

		population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		//calc ageDistr + filter out manzanas with invalid data
		calcAgeDistr();

		for (SimpleFeature ft : manzanas) {
				processManzana(ft, ct);
		}

		log.info("Generated {} persons", population.getPersons().size());

		PopulationUtils.sortPersons(population);

		ProjectionUtils.putCRS(population, MexicoCityUtils.CRS);
		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	private void calcAgeDistr() {

		List<Integer> valuesYoung = new ArrayList<>();
		List<Integer> valuesMiddle = new ArrayList<>();
		List<Integer> valuesOld = new ArrayList<>();
		int sumYoung = 0;
		int sumMiddle = 0;
		int sumOld = 0;

		for (SimpleFeature ft : shp.readFeatures()) {

			if (ft.getAttribute(noInhAttrName) != null && Integer.parseInt(ft.getAttribute(noInhAttrName).toString()) != 0) {
				manzanas.add(ft);

				int nYoung = Integer.parseInt(ft.getAttribute("POB0_14").toString());
				int nMiddle = Integer.parseInt(ft.getAttribute("POB15_64").toString());
				int nOld = Integer.parseInt(ft.getAttribute("POB65_MAS").toString());

				if (!(nYoung == 0 && nMiddle == 0 && nOld == 0)) {
					valuesYoung.add(nYoung);
					valuesMiddle.add(nMiddle);
					valuesOld.add(nOld);
					sumYoung = sumYoung + nYoung;
					sumMiddle = sumMiddle + nMiddle;
					sumOld = sumOld + nOld;
				}
			}
		}

		if (valuesYoung.size() == valuesMiddle.size() && valuesYoung.size() == valuesOld.size() && sumYoung != 0 && sumMiddle != 0 && sumOld != 0) {
			ageGroupDistr.put("0-14", ((double) sumYoung / (sumYoung + sumMiddle + sumOld)));
			ageGroupDistr.put("15-64", ((double) sumMiddle / (sumYoung + sumMiddle + sumOld)));
			ageGroupDistr.put("65+", ((double) sumOld / (sumYoung + sumMiddle + sumOld)));
		} else {
			log.fatal("Age groups must have the same number of values and their sums must not be 0! {}, {}, {}, {}, {}, {}", valuesYoung.size(), valuesMiddle.size(), valuesOld.size(), sumYoung, sumMiddle, sumOld);
		}
	}

	private void processManzana(SimpleFeature ft, CoordinateTransformation ct) {

		String manzanaID = ft.getAttribute("CVEGEO").toString();

		int n = Integer.parseInt(ft.getAttribute(noInhAttrName).toString());
		int nYoung = Integer.parseInt(ft.getAttribute("POB0_14").toString());
		int nMiddle = Integer.parseInt(ft.getAttribute("POB15_64").toString());
		int nOld = Integer.parseInt(ft.getAttribute("POB65_MAS").toString());
		int nFem = Integer.parseInt(ft.getAttribute("POBFEM").toString());
		int nMasc = Integer.parseInt(ft.getAttribute("POBMAS").toString());
		// number of persons (12+ years) which are "economically active"
		int nEcoAct = Integer.parseInt(ft.getAttribute("PEA").toString());
		int nEcoNotAct = Integer.parseInt(ft.getAttribute("PE_INAC").toString());
		int nAgeTwelveAndMore = Integer.parseInt(ft.getAttribute("P_12YMAS").toString());

		InputData inputData = new InputData(manzanaID, n, nYoung, nMiddle, nOld, nFem, nMasc, nEcoAct, nEcoNotAct, nAgeTwelveAndMore);

		log.info("Processing {} with {} inhabitants", manzanaID, n);

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

		//when sampling for 1pct scenario we are overestimating manzanas with nInh <= 100. keep in mind -sme1123
		for (int i = 0; i < n * sample; i++) {

			Person person = f.createPerson(generateId(population, "cdmx", rnd));
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

			person.getAttributes().putAttribute(MexicoCityUtils.ENT, ft.getAttribute("CVE_ENT"));
			person.getAttributes().putAttribute(MexicoCityUtils.MUN, ft.getAttribute("CVE_MUN"));
			person.getAttributes().putAttribute(MexicoCityUtils.LOC, ft.getAttribute("CVE_LOC"));
			person.getAttributes().putAttribute(MexicoCityUtils.AGEB, ft.getAttribute("CVE_AGEB"));
			person.getAttributes().putAttribute(MexicoCityUtils.MZA, ft.getAttribute("CVE_MZA"));

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
		if (data.nYoung == 0 && data.nMiddle == 0 && data.nOld == 0 || (young + middle + old) != 1.0) {
			//if values for age groups are missing -> apply general values for cdmx. The values are calculated in method calcAgeDistr
			young = ageGroupDistr.get("0-14");
			middle = ageGroupDistr.get("15-64");
			old = ageGroupDistr.get("65+");
		}

		// calc quota for men / women. There seems to be no data on diverse people..
		double quotaFem = (double) data.nFem / data.nInh;
		double quotaMasc = (double) data.nMasc / data.nInh;

		if (data.nFem == 0 && data.nMasc == 0 || (quotaFem + quotaMasc) != 1.0) {
			//if values for fem and masc population in manzana are missing -> apply general values for cdmx
			//source: https://www.inegi.org.mx/contenidos/programas/ccpv/2020/doc/cpv2020_pres_res_cdmx.pdf
			quotaFem = 0.52;
			quotaMasc = 0.48;
		}

		double quotaEcoAct = (double) data.nEcoAct / data.nAgeTwelveAndMore;
		double quotaEcoNotAct = (double) data.nEcoNotAct / data.nAgeTwelveAndMore;

		//calc quota of economically active inhabitants (12+ years)
		if (data.nEcoNotAct == 0 && data.nEcoAct == 0 || (quotaEcoNotAct + quotaEcoAct) != 1.0) {
			//if values are missing -> apply general values for cdmx
			//source: https://www.inegi.org.mx/contenidos/programas/ccpv/2020/doc/cpv2020_pres_res_cdmx.pdf
			//after some thinking: We apply the share of employed people on all inh with age 15-64. BUT: quotaEmployed = nEmployed / nEcoAct = 97.8 -> all employed people out of the economically active
			//it would be better to apply nEcoAct / nInh on all people of 15-64 and assume that nEcoAct = nEmployed as it is really close to 100 anyway. -sme1123
			quotaEcoAct = 0.644;
			quotaEcoNotAct = 1 - quotaEcoAct;
		}

		return new ValidatedAndProcessedData(data.manzanaID, young, middle, old, quotaFem, quotaMasc, quotaEcoAct, quotaEcoNotAct);
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

	private record InputData(String manzanaID, int nInh, int nYoung, int nMiddle, int nOld, int nFem, int nMasc, int nEcoAct, int nEcoNotAct, int nAgeTwelveAndMore) {

	}

	private record ValidatedAndProcessedData(String manzanaID, double young, double middle, double old, double quotaFem, double quotaMasc, double quotaEcoAct, double quotaEcoNotAct) {

	}

}
