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
import org.locationtech.jts.geom.*;
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
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.router.*;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.facilities.*;
import org.matsim.prepare.MexicoCityUtils;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;
	@CommandLine.Option(names = "--output", description = "Output path for population", required = true)
	private Path output;
	@CommandLine.Option(names = "--persons", description = "Path to person table", required = true)
	private Path personsPath;
	@CommandLine.Option(names = "--activities", description = "Path to activity table", required = true)
	private Path activityPath;
	@CommandLine.Option(names = "--seed", description = "Seed used to sample plans", defaultValue = "1")
	private long seed;
	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private Path networkPath;
	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	public static final String NEVER = "never";
	public static final String ALWAYS = "always";
	public static final String DIST_ATTR = "orig_dist";
	private final CsvOptions csv = new CsvOptions(CSVFormat.Predefined.Default);
	private final Map<Key, IntList> groups = new HashMap<>();
	private final Int2ObjectMap<CSVRecord> persons = new Int2ObjectOpenHashMap<>();
	/**
	 * Maps person index to list of activities.
	 */
	private final Int2ObjectMap<List<CSVRecord>> activities = new Int2ObjectOpenHashMap<>();
	private ThreadLocal<Context> ctxs;
	private PopulationFactory factory;
	private ActivityFacilitiesFactory fac = FacilitiesUtils.createActivityFacilities().getFactory();
	private Map<String, RoutingModule> routingModules = new HashMap<>();
	private List<SimpleFeature> features = new ArrayList<>();
	private Map<Id<Person>, Person> agentsWithoutSubgroup = new HashMap<>();

	public static void main(String[] args) {
		new RunActivitySampling().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (!MexicoCityUtils.isDefined(shp.getShapeFile())) {
			log.error("Shape file with districts for EOD2017 is required.");
			return 2;
		}

		features.addAll(shp.readFeatures());

		Config config = ConfigUtils.createConfig();
		config.plans().setInputFile(input.toString());
		config.global().setCoordinateSystem(MexicoCityUtils.CRS);
		config.network().setInputFile(networkPath.toString());
//		directWalkFactor set to high value to avoid creation of walk leg instead of pt leg when using swissRailRaptorRoutingModule
		config.transitRouter().setDirectWalkFactor(1000.);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		try (CSVParser parser = csv.createParser(personsPath)) {
			log.info("Parsing persons sample data from {}. Due to the size of the dataset this may take a while.", personsPath);
			buildSubgroups(parser);
		}

		try (CSVParser parser = csv.createParser(activityPath)) {
			log.info("Parsing activities sample data from {}. Due to the size of the dataset this may take a while.", activityPath);
			readActivities(parser);
		}

		ctxs = ThreadLocal.withInitial(() -> new Context(new SplittableRandom(seed)));

		Population population = scenario.getPopulation();

		factory = population.getFactory();

		//determine home district of EOD2017 districts for each person if not existent
		population.getPersons().values().stream().findAny().ifPresent(person -> {
			if (person.getAttributes().getAttribute(MexicoCityUtils.DISTR) == null) {
				AssignODSurveyDistricts assign = new AssignODSurveyDistricts(population, shp);
				assign.assignDistricts();
			}
		});

		prepareRoutingModules(scenario);

		ParallelPersonAlgorithmUtils.run(population, 12, this);

		ctxs.remove();

		PopulationUtils.writePopulation(population, output.toString());

		double atHome = 0;
		for (Person person : population.getPersons().values()) {
			List<Leg> legs = TripStructureUtils.getLegs(person.getSelectedPlan());
			if (legs.isEmpty())
				atHome++;
		}

		int size = population.getPersons().size();
		double mobile = (size - atHome) / size;

		log.info("Processed {} persons, mobile persons: {}%", size, 100 * mobile);

		double noSubgroupShare = Double.valueOf(agentsWithoutSubgroup.size()) / size;

		if (noSubgroupShare <= 0.05) {
			log.warn("For {} % of the population no subgroup based on the survey data could be found. " +
				"{} agents are affected:", noSubgroupShare*100, agentsWithoutSubgroup.size());
			agentsWithoutSubgroup.keySet().stream().forEach(log::warn);
		} else {
			log.error("For {} % of the population no subgroup based on the survey data could be found. " +
				"Please check your survey data and/or population.", Math.round(noSubgroupShare*100));
			return 2;
		}
		return 0;
	}

	private void prepareRoutingModules(Scenario scenario) {
		TeleportationRoutingModule walkRouter = new TeleportationRoutingModule(TransportMode.walk, scenario, 1.0555556, 1.3);

//		according to INRIX traffic scorecard report 2022 -> downtown avg speed cdmx 12mph -> 19.3 kmh
		double avgCarSpeed = 12 * 1.609344 / 3.6;
		TeleportationRoutingModule carRouter = new TeleportationRoutingModule(TransportMode.car, scenario, avgCarSpeed, 1.3);
		TeleportationRoutingModule bikeRouter = new TeleportationRoutingModule(TransportMode.bike, scenario, 3.1388889, 1.3);
//		avg pt speed according to cdmx government: 17kmh https://semovi.cdmx.gob.mx/storage/app/media/diagnostico-tecnico-de-movilidad-pim.pdf
		TeleportationRoutingModule ptRouter = new TeleportationRoutingModule(TransportMode.pt, scenario, 17 / 3.6, 1.3);

		routingModules.put(TransportMode.pt, ptRouter);
		routingModules.put(TransportMode.car, carRouter);
		routingModules.put(TransportMode.ride, carRouter);
		routingModules.put(TransportMode.motorcycle, carRouter);
		routingModules.put(TransportMode.other, carRouter);
		routingModules.put(TransportMode.bike, bikeRouter);
		routingModules.put(TransportMode.walk, walkRouter);
//		as of 20.12.23 -> taxibus/colectivo routed separate from "normal" pt, as teleported mode with bike speed -> assumption
		routingModules.put(MexicoCityUtils.TAXIBUS, bikeRouter);
	}

	/**
	 * Create subpopulations for sampling.
	 */
	private void buildSubgroups(CSVParser csv) {

		AtomicInteger i = new AtomicInteger();

		csv.stream().forEach(r -> {

			int idx = Integer.parseInt(r.get("idx"));

			createKey(r.get("gender"), Integer.parseInt(r.get("age")), Integer.parseInt(r.get("region_type")), r.get("employment"), r.get("home_district"))
				.forEach(key -> groups.computeIfAbsent(key, k -> new IntArrayList()).add(idx));

			persons.put(idx, r);
			i.getAndIncrement();
		});

		log.info("Read {} persons from csv.", i.get());
	}

	private void readActivities(CSVParser csv) {

		AtomicInteger currentId = new AtomicInteger(-1);
		final List<CSVRecord>[] current = new List[]{null};

		AtomicInteger i = new AtomicInteger();

		csv.stream().forEach(r -> {
			int pIdx = Integer.parseInt(r.get("p_index"));

			if (pIdx != currentId.get()) {
				if (current[0] != null) {
					activities.put(currentId.get(), new ArrayList<>(current[0]));
				}

				currentId.set(pIdx);
				current[0] = new ArrayList<>();
			}

			current[0].add(r);
			i.getAndIncrement();
		});

		if (current[0] != null && !current[0].isEmpty()) {
			activities.put(currentId.get(), current[0]);
		}

		log.info("Read {} activities for {} persons", i.get(), activities.size());
	}

	private Stream<Key> createKey(String gender, int age, int regionType, String employment, String homeDistrict) {
		if (age < 6) {
			return IntStream.rangeClosed(0, 5).mapToObj(i -> new Key(null, i, regionType, null, homeDistrict));
		}
		if (age <= 10) {
			return IntStream.rangeClosed(6, 10).mapToObj(i -> new Key(null, i, regionType, null, homeDistrict));
		}
		if (age < 18) {
			return IntStream.rangeClosed(11, 18).mapToObj(i -> new Key(gender, i, regionType, null, homeDistrict));
		}

		Boolean isEmployed = age > 65 ? null : !employment.equals("unemployed");
		int min = Math.max(18, age - 6);
		int max = Math.min(65, age + 6);

		// larger groups for older people
		if (age > 65) {
			min = Math.max(66, age - 10);
			max = Math.min(99, age + 10);
		}
		return IntStream.rangeClosed(min, max).mapToObj(i -> new Key(gender, i, regionType, isEmployed, homeDistrict));
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
		if (!person.getAttributes().getAttribute(MexicoCityUtils.ENT).equals("09")) {
			regionType = 3;
		}
		return new Key(gender, age, regionType, employed, person.getAttributes().getAttribute(MexicoCityUtils.DISTR).toString());
	}

	@Override
	public void run(Person person) {

		SplittableRandom rnd = ctxs.get().rnd;

		Key key = createKey(person);

		IntList subgroup = groups.get(key);
		if (subgroup == null) {
//			here, no runtime is thrown anymore. Instead, after processing all persons,
//			it is checked whether the no of persons without subgroup exceeds 5% of total population size. -sme0124
			log.warn("No subgroup found for key {}", key);
			agentsWithoutSubgroup.putIfAbsent(person.getId(), person);
			return;
		}

		if (subgroup.size() < 30) {
			log.warn("Group {} has low sample size: {}", key, subgroup.size());
		}

		int idx = subgroup.getInt(rnd.nextInt(subgroup.size()));
		CSVRecord row = persons.get(idx);

		PersonUtils.setCarAvail(person, row.get("car_avail").equals("True") ? ALWAYS : NEVER);
		PersonUtils.setLicence(person, row.get("driving_license").toLowerCase());

		person.getAttributes().putAttribute(MexicoCityUtils.BIKE_AVAIL, row.get("bike_avail").equals("True") ? ALWAYS : NEVER);
		person.getAttributes().putAttribute(MexicoCityUtils.PT_ABO_AVAIL, row.get("pt_abo_avail").equals("True") ? ALWAYS : NEVER);

		person.getAttributes().putAttribute(MexicoCityUtils.EMPLOYMENT, row.get("employment"));
		person.getAttributes().putAttribute(MexicoCityUtils.RESTRICTED_MOBILITY, row.get("restricted_mobility").equals("True"));
		person.getAttributes().putAttribute(MexicoCityUtils.ECONOMIC_STATUS, row.get("economic_status"));
		person.getAttributes().putAttribute(MexicoCityUtils.HOUSEHOLD_SIZE, Integer.parseInt(row.get("n_persons")));


		String mobile = row.get("mobile_on_day");

		// ensure mobile agents have a valid plan
		switch (mobile.toLowerCase()) {

			case "true" -> {
				List<CSVRecord> acts = this.activities.get(idx);

				if (acts == null)
					throw new AssertionError("No activities for mobile person " + idx);

				if (acts.isEmpty())
					throw new AssertionError("Activities for mobile agent can not be empty.");

				person.removePlan(person.getSelectedPlan());
				log.info("about to handle survey-person {} with MATSim personId {}", idx, person.getId());
				Plan plan = createPlan(MexicoCityUtils.getHomeCoord(person), acts, rnd, person);

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

	private Plan createPlan(Coord homeCoord, List<CSVRecord> activities, SplittableRandom rnd, Person person) {
		Plan plan = factory.createPlan();
		plan.setPerson(person);

		Activity a = null;
		String lastMode = null;

		// Track the distance to the first home activity
		double homeDist = 0;
		boolean arrivedHome = false;

		for (int i = 0; i < activities.size(); i++) {

			CSVRecord act = activities.get(i);

			String actType = act.get("type");
			double startTime = Integer.parseInt(act.get("start_time")) * 60.;

			// First and last activities that are other are changed to home
			if (actType.equals("other") && (i == 0 || i == activities.size() - 1))
				actType = "home";

			int duration = Integer.parseInt(act.get("duration"));

			if (actType.equals("home")) {
				a = factory.createActivityFromCoord("home", homeCoord);
			} else
				a = factory.createActivityFromLinkId(actType, Id.createLinkId("unassigned"));

			double legDuration = Double.parseDouble(act.get("leg_duration")) * 60;

			if (plan.getPlanElements().isEmpty()) {
				// Add little
				int seconds = randomizeDuration(duration, rnd);

				a.setEndTime(seconds);

			} else if (duration < 1440) {
				// Flexible modes are represented with duration
				// otherwise start and end time
				int seconds = randomizeDuration(duration, rnd);

				if (RunMexicoCityCalibration.FLEXIBLE_ACTS.contains(actType))
					a.setMaximumDuration(seconds);
				else {
					a.setStartTime(startTime);
					a.setEndTime(startTime + seconds);
				}
			}

			double legDist = Double.parseDouble(act.get("leg_dist"));

			if (act.get("leg_dep_district").equals("999") || act.get("leg_arr_district").equals("999")) {
				//do not route if district id is unknown = 999
			} else {
//					if it is the first act of the day, there is no leg to the activity -> duration and distance of "leg" = 0
				if (i > 0) {
					legDist = getDistFromRoutedLeg(rnd, act, legDuration, plan, homeCoord);
				}
			}


			if (i > 0) {
				a.getAttributes().putAttribute(DIST_ATTR, legDist);
				a.getAttributes().putAttribute("orig_duration", legDuration);
			}

			if (!plan.getPlanElements().isEmpty()) {
				lastMode = act.get("leg_mode");

				// other mode is initialized as walk
				if (lastMode.equals("other"))
					lastMode = "walk";

				Leg leg = factory.createLeg(lastMode);
				leg.getAttributes().putAttribute(DIST_ATTR, legDist);
				leg.getAttributes().putAttribute("orig_duration", legDuration);

				plan.addLeg(leg);
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
		act.getAttributes().putAttribute(DIST_ATTR, homeDist);

		// Last activity has no end time and duration
		if (a != null) {

			if (!RunMexicoCityCalibration.FLEXIBLE_ACTS.contains(a.getType())) {
				a.setEndTimeUndefined();
				a.setMaximumDurationUndefined();
			} else {

				// End open activities
				a.setMaximumDuration(30. * 60);
				plan.addLeg(factory.createLeg(lastMode));
				plan.addActivity(factory.createActivityFromCoord("home", homeCoord));
			}
		}

		return plan;
	}

	private Double getDistFromRoutedLeg(SplittableRandom rnd, CSVRecord act, double legDuration, Plan plan, Coord homeCoord) {

//		list instead of map because depDistrict == arrDistrict would produce a map with only 1 key.
		List<Map.Entry<String, Coord>> district2Coord = new ArrayList<>();
		Coord coord = null;

//		get homeCoord from person if act typ is home
		if (act.get("type").equals("home")) {
			coord = homeCoord;
		}

		String depDistrict = act.get("leg_dep_district");
		String arrDistrict = act.get("leg_arr_district");

		district2Coord.add(new AbstractMap.SimpleEntry<>(depDistrict, null));
		district2Coord.add(new AbstractMap.SimpleEntry<>(arrDistrict, null));

		AtomicReference<Double> routedDuration = new AtomicReference<>();
		AtomicReference<Double> routedDistance = new AtomicReference<>();

		Map<Double, Double> duration2Distance = new HashMap<>();

		int count = 0;
		Double min = null;
		Double closestDuration = null;

		while (routedDuration.get() == null || Math.abs(routedDuration.get() - legDuration) >= 600) {
			routedDuration.lazySet(0.);
			routedDistance.lazySet(0.);

			for (Map.Entry<String, Coord> entry: district2Coord) {
				entry.setValue(generateRandomCoord(rnd, entry.getKey(), coord,
					plan.getPerson().getAttributes().getAttribute(MexicoCityUtils.DISTR).toString(), district2Coord.indexOf(entry)));
			}

			String routingMode = act.get("leg_mode");

			ActivityFacility depFac = fac.createActivityFacility(Id.create(depDistrict, ActivityFacility.class), district2Coord.get(0).getValue());
			ActivityFacility arrFac = fac.createActivityFacility(Id.create(arrDistrict, ActivityFacility.class), district2Coord.get(1).getValue());

			List<? extends PlanElement> planElements = routingModules.get(routingMode).calcRoute(DefaultRoutingRequest.withoutAttributes(depFac, arrFac,
				Double.parseDouble(act.get("leg_departure")), plan.getPerson()));

			planElements.stream().filter(Leg.class::isInstance).forEach(leg -> {
				routedDistance.set(routedDistance.get() + ((Leg) leg).getRoute().getDistance());
				routedDuration.set(routedDuration.get() + ((Leg) leg).getTravelTime().seconds());
			});

			duration2Distance.putIfAbsent(routedDuration.get(), routedDistance.get());

			if (count >= 25) {
//				no route with adequate leg duration can be found -> get value with fewest delta to survey leg uration
				for (Map.Entry<Double, Double> e : duration2Distance.entrySet()) {
					double delta = Math.abs(legDuration - e.getKey());

					if (min == null || delta < min) {
						min = delta;
						closestDuration = e.getKey();
					}
				}
				routedDistance.set(duration2Distance.get(closestDuration));
				log.warn("Within 25 tries, no route with a delta to the surveyed duration of |600|s  or lower could be found. " +
					"Therefore the routedDistance is set to the routed Distance with the closest routedDuration to the surveyed duration: {} m", duration2Distance.get(closestDuration));
				break;
			}
			count++;
		}
		return routedDistance.get();
	}

	private Coord generateRandomCoord(SplittableRandom rnd, String districtNumber, Coord homeCoord, String homeDistrict, int noLoc) {
		AtomicReference<Double> randomX = new AtomicReference<>();
		AtomicReference<Double> randomY = new AtomicReference<>();

		if (homeCoord != null && districtNumber.equals(homeDistrict) && noLoc == 1) {
//			noLoc = index of districtNumber in List is needed because in the case of a route of same district to same district + home act (e.g. distr 178 to distr 178) ->
//			method returns homeCoord for dep + arr -> route of 0 seconds - sme0124
			randomX.set(homeCoord.getX());
			randomY.set(homeCoord.getY());
		} else if (districtNumber.equals("888")) {
//			888 -> outside of metropolitan area
//			buffer of 150km
			Geometry outsideZMVM = shp.getGeometry().buffer(150000).difference(shp.getGeometry());

			randomX.set(rnd.nextDouble(outsideZMVM.getEnvelopeInternal().getMinX(),
				outsideZMVM.getEnvelopeInternal().getMinX() + outsideZMVM.getEnvelopeInternal().getWidth()));
			randomY.set(rnd.nextDouble(outsideZMVM.getEnvelopeInternal().getMinY(),
				outsideZMVM.getEnvelopeInternal().getMinY() + outsideZMVM.getEnvelopeInternal().getHeight()));
		} else {
			AtomicBoolean containsPoint = new AtomicBoolean(false);
			while (!containsPoint.get()) {
//				generate random coord based on district of zmvm
				features.stream().filter(feature ->
					feature.getAttribute("Distrito").equals(districtNumber)).forEach(feature -> {

					Envelope env = ((Geometry) feature.getDefaultGeometry()).getEnvelopeInternal();

					randomX.set(rnd.nextDouble(env.getMinX(), env.getMinX() + env.getWidth()));
					randomY.set(rnd.nextDouble(env.getMinY(), env.getMinY() + env.getHeight()));

					containsPoint.set(MGC.xy2Point(randomX.get(), randomY.get()).within((Geometry) feature.getDefaultGeometry()));
				});

			}
		}
		return new Coord(randomX.get(), randomY.get());
	}

	/**
	 * Key used for sampling activities.
	 */
	private record Key(String gender, int age, int regionType, Boolean employed, String homeDistrict) {
	}

	private record Context(SplittableRandom rnd) {
	}

}
