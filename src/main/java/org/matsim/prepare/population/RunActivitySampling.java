package org.matsim.prepare.population;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.data.shapefile.files.ShpFileType;
import org.geotools.data.shapefile.files.ShpFiles;
import org.geotools.data.shapefile.shp.ShapeType;
import org.geotools.data.shapefile.shp.ShapefileWriter;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.router.*;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.gis.ShapeFileWriter;
import org.matsim.facilities.*;
import org.matsim.prepare.MexicoCityUtils;
import org.matsim.run.RunMexicoCityScenario;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * class copied and adapted from.
 * https://github.com/matsim-scenarios/matsim-berlin/blob/6.x/src/main/java/org/matsim/prepare/population/RunActivitySampling.java
 * -sme1123
 **/

@CommandLine.Command(
	name = "activity-sampling",
	description = "Create activities by sampling from survey data"
)
public class RunActivitySampling implements MATSimAppCommand, PersonAlgorithm {

	private static final Logger log = LogManager.getLogger(RunActivitySampling.class);
	private final CsvOptions csv = new CsvOptions(CSVFormat.Predefined.Default);
	private final Map<Key, IntList> groups = new HashMap<>();
	private final Int2ObjectMap<CSVRecord> persons = new Int2ObjectOpenHashMap<>();
	/**
	 * Maps person index to list of activities.
	 */
	private final Int2ObjectMap<List<CSVRecord>> activities = new Int2ObjectOpenHashMap<>();
	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;
	@CommandLine.Option(names = "--output", description = "Output path for population", required = true)
	private Path output;
	@CommandLine.Option(names = "--persons", description = "Path to person table", required = true)
	private Path personsPath;
	@CommandLine.Option(names = "--activities", description = "Path to activity table", required = true)
	private Path activityPath;
	@CommandLine.Option(names = "--network", description = "Path to input network for routing", required = true)
	private Path networkPath;

	@CommandLine.Option(names = "--transit-schedule", description = "Path to input transit schedule for pt routing", required = true)
	private Path transitSchedulePath;
	@CommandLine.Option(names = "--seed", description = "Seed used to sample plans", defaultValue = "1")
	private long seed;
	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();
	private ThreadLocal<Context> ctxs;

	private PopulationFactory factory;
	private ActivityFacilitiesFactory fac = FacilitiesUtils.createActivityFacilities().getFactory();
	private LeastCostPathCalculatorFactory leastCostPathCalculatorFactory = new SpeedyALTFactory();

	private TravelDisutility travelDisutility = TravelDisutilityUtils.createFreespeedTravelTimeAndDisutility(ConfigUtils.addOrGetModule(new Config(), PlanCalcScoreConfigGroup.class));
	private TravelTime travelTime = TravelTimeUtils.createFreeSpeedTravelTime();

	private Map<String, RoutingModule> routingModules = new HashMap<>();

	public static void main(String[] args) {
		new RunActivitySampling().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (shp.getShapeFile() == null) {
			log.error("Shape file with districts for EOD2017 is required.");
			return 2;
		}

		Config config = ConfigUtils.createConfig();
		config.plans().setInputFile(input.toString());
		config.network().setInputFile(networkPath.toString());
		config.transit().setTransitScheduleFile(transitSchedulePath.toString());
		config.global().setCoordinateSystem(RunMexicoCityScenario.CRS);

//		Network network = NetworkUtils.readNetwork(networkPath.toString());

		Scenario scenario = ScenarioUtils.loadScenario(config);

		LeastCostPathCalculator calculator = leastCostPathCalculatorFactory.createPathCalculator(scenario.getNetwork(), travelDisutility, travelTime);

		try (CSVParser parser = csv.createParser(personsPath)) {
			buildSubgroups(parser);
		}

		try (CSVParser parser = csv.createParser(activityPath)) {
			readActivities(parser);
		}

		ctxs = ThreadLocal.withInitial(() -> new Context(new SplittableRandom(seed)));
//		factory = scenario.getPopulation().getFactory();

//TODO: delete testPop

		Population testPop = PopulationUtils.createPopulation(config);
		Person testPerson = testPop.getFactory().createPerson(Id.createPersonId("test"));
		testPerson.getAttributes().putAttribute("age", 35);
		testPerson.getAttributes().putAttribute("sex", "f");
		testPerson.getAttributes().putAttribute("employed", true);
		testPerson.getAttributes().putAttribute("home_x", 1752045.4);
		testPerson.getAttributes().putAttribute("home_y", 2184313.44);

		testPop.addPerson(testPerson);

		factory = testPop.getFactory();


		TeleportationRoutingModule walkRouter = new TeleportationRoutingModule(TransportMode.walk, scenario, 1.0555556, 1.3);
		NetworkRoutingModule carRouter = new NetworkRoutingModule(TransportMode.car, factory, scenario.getNetwork(), calculator);

		routingModules.put(TransportMode.car, carRouter);
		routingModules.put(TransportMode.ride, carRouter);
		routingModules.put(TransportMode.motorcycle, carRouter);
		routingModules.put(TransportMode.other, carRouter);
		routingModules.put(TransportMode.bike, new TeleportationRoutingModule(TransportMode.bike, scenario, 3.1388889, 1.3));
		routingModules.put(TransportMode.walk, walkRouter);
		// pt is routed as car because:
//		1) There is no gtfs data for many pt modes such as colectivo / microbus.., which make up for 37% of the city's modal split
//		2) as there is no gtfs data, a routing via gtfs or pt mode for e.g. colectivo would lead to incorrect results
//		3) 70% of the pt modal share are modes, which operate on the normal street network
//		source for modal shares: https://semovi.cdmx.gob.mx/storage/app/media/diagnostico-tecnico-de-movilidad-pim.pdf
		routingModules.put(TransportMode.pt, carRouter);


//		ParallelPersonAlgorithmUtils.run(scenario.getPopulation(), 8, this);
		ParallelPersonAlgorithmUtils.run(testPop, 8, this);

//		PopulationUtils.writePopulation(scenario.getPopulation(), output.toString());
		PopulationUtils.writePopulation(testPop, output.toString());

		double atHome = 0;
		for (Person person : scenario.getPopulation().getPersons().values()) {
			List<Leg> legs = TripStructureUtils.getLegs(person.getSelectedPlan());
			if (legs.isEmpty())
				atHome++;
		}

		int size = scenario.getPopulation().getPersons().size();
		double mobile = (size - atHome) / size;

		log.info("Processed {} persons, mobile persons: {}%", size, 100 * mobile);

		return 0;
	}

	/**
	 * Create subpopulations for sampling.
	 */
	private void buildSubgroups(CSVParser csv) {

		int i = 0;

		for (CSVRecord r : csv) {

			int idx = Integer.parseInt(r.get("idx"));
			int regionType = Integer.parseInt(r.get("region_type"));
			String gender = r.get("gender");
			String employment = r.get("employment");
			int age = Integer.parseInt(r.get("age"));

			Stream<Key> keys = createKey(gender, age, regionType, employment);
			keys.forEach(key -> groups.computeIfAbsent(key, (k) -> new IntArrayList()).add(idx));
			persons.put(idx, r);
			i++;
		}

		log.info("Read {} persons from csv.", i);
	}

	private void readActivities(CSVParser csv) {

		int currentId = -1;
		List<CSVRecord> current = null;

		int i = 0;
		for (CSVRecord r : csv) {

			int pId = Integer.parseInt(r.get("p_id"));

			if (pId != currentId) {
				if (current != null)
					activities.put(currentId, current);

				currentId = pId;
				current = new ArrayList<>();
			}

			current.add(r);
			i++;
		}

		if (current != null && !current.isEmpty()) {
			activities.put(currentId, current);
		}

		log.info("Read {} activities for {} persons", i, activities.size());
	}

	private Stream<Key> createKey(String gender, int age, int regionType, String employment) {
		if (age < 6) {
			return IntStream.rangeClosed(0, 5).mapToObj(i -> new Key(null, i, regionType, null));
		}
		if (age <= 10) {
			return IntStream.rangeClosed(6, 10).mapToObj(i -> new Key(null, i, regionType, null));
		}
		if (age < 18) {
			return IntStream.rangeClosed(11, 18).mapToObj(i -> new Key(gender, i, regionType, null));
		}

		Boolean isEmployed = age > 65 ? null : !employment.equals("unemployed");
		int min = Math.max(18, age - 6);
		int max = Math.min(65, age + 6);

		// larger groups for older people
		if (age > 65) {
			min = Math.max(66, age - 10);
			max = Math.min(99, age + 10);
		}

		return IntStream.rangeClosed(min, max).mapToObj(i -> new Key(gender, i, regionType, isEmployed));
	}

	private Key createKey(Person person) {

		Integer age = PersonUtils.getAge(person);
		String gender = PersonUtils.getSex(person);
		if (age <= 10)
			gender = null;

		Boolean employed = PersonUtils.isEmployed(person);
		if (age < 18 || age > 65)
			employed = null;

		int regionType = 1;

		// Region types have been reduced to 1 and 3
		if (regionType != 1)
			regionType = 3;

		return new Key(gender, age, regionType, employed);
	}

	@Override
	public void run(Person person) {

		SplittableRandom rnd = ctxs.get().rnd;

		Key key = createKey(person);

		IntList subgroup = groups.get(key);
		if (subgroup == null) {
			log.error("No subgroup found for key {}", key);
			throw new IllegalStateException("Invalid entry");
		}

		if (subgroup.size() < 30) {
			log.warn("Group {} has low sample size: {}", key, subgroup.size());
		}

		int idx = subgroup.getInt(rnd.nextInt(subgroup.size()));
		CSVRecord row = persons.get(idx);

		PersonUtils.setCarAvail(person, row.get("car_avail").equals("True") ? "always" : "never");
		PersonUtils.setLicence(person, row.get("driving_license").toLowerCase());

		person.getAttributes().putAttribute(MexicoCityUtils.BIKE_AVAIL, row.get("bike_avail").equals("True") ? "always" : "never");
		person.getAttributes().putAttribute(MexicoCityUtils.PT_ABO_AVAIL, row.get("pt_abo_avail").equals("True") ? "always" : "never");

		person.getAttributes().putAttribute(MexicoCityUtils.EMPLOYMENT, row.get("employment"));
		person.getAttributes().putAttribute(MexicoCityUtils.RESTRICTED_MOBILITY, row.get("restricted_mobility").equals("True"));
		person.getAttributes().putAttribute(MexicoCityUtils.ECONOMIC_STATUS, row.get("economic_status"));
		person.getAttributes().putAttribute(MexicoCityUtils.HOUSEHOLD_SIZE, Integer.parseInt(row.get("n_persons")));


		String mobile = row.get("mobile_on_day");

		// ensure mobile agents have a valid plan
		switch (mobile.toLowerCase()) {

			case "true" -> {
				List<CSVRecord> activities = this.activities.get(idx);

				if (activities == null)
					throw new AssertionError("No activities for mobile person " + idx);

				if (activities.size() == 0)
					throw new AssertionError("Activities for mobile agent can not be empty.");

				person.removePlan(person.getSelectedPlan());
				Plan plan = createPlan(MexicoCityUtils.getHomeCoord(person), activities, rnd);

				person.addPlan(plan);
				person.setSelectedPlan(plan);
			}

			case "false" -> {
				// Keep the stay home plan
			}

			default -> throw new AssertionError("Invalid mobile_on_day attribute " + mobile);
		}
	}

	/**
	 * Randomize the duration slightly, depending on total duration.
	 */
	private int randomizeDuration(int minutes, SplittableRandom rnd) {
		if (minutes <= 10)
			return minutes * 60;

		if (minutes <= 60)
			return minutes * 60 + rnd.nextInt(300) - 150;

		if (minutes <= 240)
			return minutes * 60 + rnd.nextInt(600) - 300;

		return minutes * 60 + rnd.nextInt(1200) - 600;
	}

	private Plan createPlan(Coord homeCoord, List<CSVRecord> activities, SplittableRandom rnd) {
		Plan plan = factory.createPlan();

		Activity a = null;
		String lastMode = null;

		double startTime = 0;

		// Track the distance to the first home activity
		double homeDist = 0;
		boolean arrivedHome = false;

		for (int i = 0; i < activities.size(); i++) {

			CSVRecord act = activities.get(i);

			String actType = act.get("type");

			// First and last activities that are other are changed to home
			if (actType.equals("other") && (i == 0 || i == activities.size() - 1))
				actType = "home";

			int duration = Integer.parseInt(act.get("duration"));

			if (actType.equals("home")) {
				a = factory.createActivityFromCoord("home", homeCoord);
			} else
				a = factory.createActivityFromLinkId(actType, Id.createLinkId("unassigned"));

			double legDuration = Double.parseDouble(act.get("leg_duration"));

			if (plan.getPlanElements().isEmpty()) {
				// Add little
				int seconds = randomizeDuration(duration, rnd);

				a.setEndTime(seconds);
				startTime += seconds;

			} else if (duration < 1440) {

				startTime += legDuration * 60;

				// Flexible modes are represented with duration
				// otherwise start and end time
				int seconds = randomizeDuration(duration, rnd);

				if (RunMexicoCityCalibration.FLEXIBLE_ACTS.contains(actType))
					a.setMaximumDuration(seconds);
				else {
					a.setStartTime(startTime);
					a.setEndTime(startTime + seconds);
				}

				startTime += seconds;
			}

			double legDist = Double.parseDouble(act.get("leg_dist"));

			if (act.get("dep_district").equals("999") || act.get("arr_district").equals("999")) {
				//do not route if district id is unknown = 999
			} else {
				try {
					legDist = getDistFromRoutedLeg(rnd, act, legDuration, plan);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}


			if (i > 0) {
				a.getAttributes().putAttribute("orig_dist", legDist);
				a.getAttributes().putAttribute("orig_duration", legDuration);
			}

			if (!plan.getPlanElements().isEmpty()) {
				lastMode = act.get("leg_mode");

				// other mode is initialized as walk
				if (lastMode.equals("other"))
					lastMode = "walk";

				plan.addLeg(factory.createLeg(lastMode));
			}

			if (!arrivedHome) {
				homeDist += legDist;
			}

			if (a.getType().equals("home")) {
				arrivedHome = true;
			}

			plan.addActivity(a);
		}

		// First activity contains the home distance
		Activity act = (Activity) plan.getPlanElements().get(0);
		act.getAttributes().putAttribute("orig_dist", homeDist);

		// Last activity has no end time and duration
		if (a != null) {

			if (!RunMexicoCityCalibration.FLEXIBLE_ACTS.contains(a.getType())) {
				a.setEndTimeUndefined();
				a.setMaximumDurationUndefined();
			} else {

				// End open activities
				a.setMaximumDuration(30 * 60);
				plan.addLeg(factory.createLeg(lastMode));
				plan.addActivity(factory.createActivityFromCoord("home", homeCoord));

				//log.warn("Last activity of type {}", a.getType());
			}
		}

		return plan;
	}

	private Double getDistFromRoutedLeg(SplittableRandom rnd, CSVRecord act, double legDuration, Plan plan) throws IOException {

		Map<String, Coord> district2Coord = new HashMap<>();

		String depDistrict = act.get("dep_district");
		String arrDistrict = act.get("arr_district");

		district2Coord.put(depDistrict, null);
		district2Coord.put(arrDistrict, null);

		Coord randomDepCoord = null;
		Coord randomArrCoord = null;

		AtomicReference<Double> routedDuration = new AtomicReference<>(null);
		AtomicReference<Double> routedDistance = new AtomicReference<>(null);

		while (routedDuration.get() == null || Math.abs(routedDuration.get() - legDuration) >= 300) {

			for (Map.Entry entry: district2Coord.entrySet()) {
				entry.setValue(generateRandomCoord(rnd, entry.getKey().toString()));
			}

			String routingMode = act.get("leg_mode");

			ActivityFacility depFac = fac.createActivityFacility(Id.create(depDistrict, ActivityFacility.class), district2Coord.get(depDistrict));
			ActivityFacility arrFac = fac.createActivityFacility(Id.create(arrDistrict, ActivityFacility.class), district2Coord.get(arrDistrict));

			List<? extends PlanElement> planElements = routingModules.get(routingMode).calcRoute(DefaultRoutingRequest.withoutAttributes(depFac, arrFac,
				Double.parseDouble(act.get("departure")), plan.getPerson()));

			planElements.stream().filter(planElement -> planElement instanceof Leg).filter(leg -> ((Leg) leg).getMode().equals(routingMode)).forEach(leg -> {
				routedDistance.set(((Leg) leg).getRoute().getDistance());
				routedDuration.set(((Leg) leg).getTravelTime().seconds());
			});
		}
		return routedDistance.get();
	}

	private Coord generateRandomCoord(SplittableRandom rnd, String districtNumber) throws IOException {
		AtomicReference<Double> randomX = new AtomicReference<>();
		AtomicReference<Double> randomY = new AtomicReference<>();

		if (districtNumber.equals("888")) {
//			888 -> outside of metropolitan area
//			buffer of 150km
			Geometry outsideZMVM = shp.getGeometry().buffer(150000).difference(shp.getGeometry());

//			TODO: remove this after test of written shp file
//			ShapefileWriter writer =  new ShapefileWriter(FileChannel.open(Path.of("C:/Users/Simon/Desktop/wd/2023-11-22/outsideZMVM.shp")),
//				FileChannel.open(Path.of("C:/Users/Simon/Desktop/wd/2023-11-22/outsideZMVM.shx")));

//			writer.write(outsideZMVM.getFactory().createGeometryCollection(), ShapeType.POLYGON);
//			writer.writeGeometry(outsideZMVM);
//			writer.close();

			SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();

			SimpleFeatureBuilder builder = new SimpleFeatureBuilder(typeBuilder.buildFeatureType());

			SimpleFeature feature = builder.buildFeature("12");

			feature.setDefaultGeometry(outsideZMVM);

			new ShapeFileWriter().writeGeometries(Collections.singleton(feature), "C:/Users/Simon/Desktop/wd/2023-11-22/outsideZMVM.shp");

			randomX.set(rnd.nextDouble(outsideZMVM.getEnvelopeInternal().getMinX(), outsideZMVM.getEnvelopeInternal().getMaxX()));
			randomY.set(rnd.nextDouble(outsideZMVM.getEnvelopeInternal().getMinY(), outsideZMVM.getEnvelopeInternal().getMaxY()));
		} else {


			Geometry outsideZMVM = shp.getGeometry().buffer(150000).difference(shp.getGeometry());

			ShpFiles shpFiles = new ShpFiles(":/Users/Simon/Desktop/wd/2023-11-22/outsideZMVM.shp");





//			TODO: remove this after test of written shp file
//			ShapefileWriter writer =  new ShapefileWriter(FileChannel.open(Path.of("C:/Users/Simon/Desktop/wd/2023-11-22/outsideZMVM.shp")),
//				FileChannel.open(Path.of("C:/Users/Simon/Desktop/wd/2023-11-22/outsideZMVM.shx")));

			ShapefileWriter writer =  new ShapefileWriter(shpFiles.getStorageFile(ShpFileType.SHP).getWriteChannel(),
				shpFiles.getStorageFile(ShpFileType.SHX).getWriteChannel());

//			writer.write(outsideZMVM.getFactory().createGeometryCollection(), ShapeType.POLYGON);
			writer.writeHeaders(outsideZMVM.getEnvelopeInternal(), ShapeType.POLYGON, 1, 1);
			writer.writeGeometry(outsideZMVM);
			writer.close();

//			SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
//
//			SimpleFeatureType type = typeBuilder.buildFeatureType();
//			typeBuilder.
//
//			SimpleFeatureBuilder builder = new SimpleFeatureBuilder();
//
//			SimpleFeature simpleFeature = builder.buildFeature("12");
//
//			simpleFeature.setDefaultGeometry(outsideZMVM);
//
//			new ShapeFileWriter().writeGeometries(Collections.singleton(simpleFeature), "C:/Users/Simon/Desktop/wd/2023-11-22/outsideZMVM.shp");



//			generate random coord based on district of zmvm
			shp.readFeatures().stream().filter(feature ->
				feature.getAttribute("Distrito").equals(districtNumber)).forEach(feature -> {
				randomX.set(rnd.nextDouble(((Geometry) feature.getDefaultGeometry()).getEnvelopeInternal().getMinX(),
					((Geometry) feature.getDefaultGeometry()).getEnvelopeInternal().getMaxX()));
				randomY.set(rnd.nextDouble(((Geometry) feature.getDefaultGeometry()).getEnvelopeInternal().getMinY(),
					((Geometry) feature.getDefaultGeometry()).getEnvelopeInternal().getMaxY()));
			});

		}
		return new Coord(randomX.get(), randomY.get());
	}

	/**
	 * Key used for sampling activities.
	 */
	private record Key(String gender, int age, int regionType, Boolean employed) {
	}

	private record Context(SplittableRandom rnd) {
	}

}
