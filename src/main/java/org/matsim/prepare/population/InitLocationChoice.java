package org.matsim.prepare.population;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.MatsimFacilitiesReader;
import org.matsim.prepare.MexicoCityUtils;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.matsim.prepare.population.CreateMATSimFacilities.IGNORED_LINK_TYPES;

@CommandLine.Command(
	name = "init-location-choice",
	description = "Assign initial locations to agents"
)
@SuppressWarnings("unchecked")
public class InitLocationChoice implements MATSimAppCommand, PersonAlgorithm {

	/**
	 * Detour factor for car routes, which was determined based on sampled routes.
	 * This is true for the MATSim Open Berlin scenario. As trips in the EOD2017 for the Mexico-city metropolitan area do not have a length attribute,
	 * we just adopt this value here.
	 */
	private static final double DETOUR_FACTOR = 1.56;

	private static final Logger log = LogManager.getLogger(InitLocationChoice.class);

	@CommandLine.Option(names = "--input", description = "Path to input population.")
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;

	@CommandLine.Option(names = "--k", description = "Number of choices to generate", defaultValue = "5")
	private int k;

	@CommandLine.Option(names = "--commuter", description = "Path to commuter.csv", required = true)
	private Path commuterPath;

	@CommandLine.Option(names = "--facilities", description = "Path to facilities file", required = true)
	private Path facilityPath;

	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private Path networkPath;

	@CommandLine.Option(names = "--sample", description = "Sample size of the population", defaultValue = "0.01")
	private double sample;

	@CommandLine.Option(names = "--seed", description = "Seed used to sample locations", defaultValue = "1")
	private long seed;

	@CommandLine.Mixin
	private ShpOptions shp;

	private Map<String, STRtree> trees;

	private Object2ObjectMap<String, SimpleFeature> zones;

	private CommuterAssignment commuter;

	private Network network;

	private ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();

	private ThreadLocal<Context> ctxs;

	private AtomicLong total = new AtomicLong();

	private AtomicLong warning = new AtomicLong();

	public static void main(String[] args) {
		new InitLocationChoice().execute(args);
	}

	private static Coord rndCoord(SplittableRandom rnd, double dist, Coord origin) {
		var angle = rnd.nextDouble() * Math.PI * 2;

		var x = Math.cos(angle) * dist;
		var y = Math.sin(angle) * dist;

		return new Coord(MexicoCityUtils.roundNumber(origin.getX() + x), MexicoCityUtils.roundNumber(origin.getY() + y));
	}

	@Override
	public Integer call() throws Exception {

		if (shp.getShapeFile() == null) {
			log.error("Shape file with commuter zones is required.");
			return 2;
		}

		Network completeNetwork = NetworkUtils.readNetwork(networkPath.toString());
		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(completeNetwork);
		network = NetworkUtils.createNetwork();
		filter.filter(network, Set.of(TransportMode.car));

		zones = new Object2ObjectOpenHashMap<>(shp.readFeatures().stream()
			.collect(Collectors.toMap(ft -> ft.getAttribute("CVE_MUN1").toString(), ft -> ft)));

		log.info("Read {} zones", zones.size());


		new MatsimFacilitiesReader(MexicoCityUtils.CRS, MexicoCityUtils.CRS, facilities)
			.readFile(facilityPath.toString());

		Set<String> activities = facilities.getFacilities().values().stream()
			.flatMap(a -> a.getActivityOptions().keySet().stream())
			.collect(Collectors.toSet());

		log.info("Found activity types: {}", activities);

		trees = new HashMap<>();
		for (String act : activities) {

			NavigableMap<Id<ActivityFacility>, ActivityFacility> afs = facilities.getFacilitiesForActivityType(act);
			for (ActivityFacility af : afs.values()) {
				STRtree index = trees.computeIfAbsent(act, noChoices -> new STRtree());
				index.insert(MGC.coord2Point(af.getCoord()).getEnvelopeInternal(), af);
			}
		}

		// Build all trees
		trees.values().forEach(STRtree::build);

		log.info("Using input file: {}", input);

		List<Population> populations = new ArrayList<>();

		for (int i = 0; i < k; i++) {

			long rSeed = this.seed + i;

			log.info("Generating plan {} with seed {}", i , rSeed);

			ctxs = ThreadLocal.withInitial(() -> new Context(rSeed));
			commuter = new CommuterAssignment(zones, commuterPath, sample);

			Population population = PopulationUtils.readPopulation(input.toString());
			ParallelPersonAlgorithmUtils.run(population, 8, this);

			populations.add(population);

			log.info("Processed {} activities with {} warnings", total.get(), warning.get());

			total.set(0);
			warning.set(0);
		}


		Population population = populations.get(0);

		// Merge all plans into the first population
		for (int i = 1; i < populations.size(); i++) {

			Population pop = populations.get(i);

			for (Person p : pop.getPersons().values()) {
				Person destPerson = population.getPersons().get(p.getId());
				if (destPerson == null) {
					log.warn("Person {} not present in all populations.", p.getId());
					continue;
				}

				destPerson.addPlan(p.getPlans().get(0));
			}
		}

		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	@Override
	public void run(Person person) {

		Coord homeCoord = MexicoCityUtils.getHomeCoord(person);

		// Activities that only occur on one place per person
		Map<String, ActivityFacility> fixedLocations = new HashMap<>();
		Context ctx = ctxs.get();

		int age = PersonUtils.getAge(person);

		for (Plan plan : person.getPlans()) {
			List<Activity> acts = TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);

			// keep track of the current coordinate
			Coord lastCoord = homeCoord;

			for (Activity act : acts) {

				String type = act.getType();

				total.incrementAndGet();

				if (MexicoCityUtils.isLinkUnassigned(act.getLinkId())) {
					act.setLinkId(null);
					ActivityFacility location = null;

					// target leg distance in km
					Object origDist = act.getAttributes().getAttribute("orig_dist");

					// Distance will be reduced
					double dist = (double) origDist / DETOUR_FACTOR;

					if (fixedLocations.containsKey(type)) {
						location = fixedLocations.get(type);
					}

					if (location == null && (type.equals("work")) || type.equals("edu")) {
						// sample commute. this can either be to a working loc or a study loc.
						String idEntMun = person.getAttributes().getAttribute(MexicoCityUtils.ENT).toString() +
							person.getAttributes().getAttribute(MexicoCityUtils.MUN).toString();

						location = sampleCommute(ctx, dist, lastCoord, idEntMun, type, age);
					}

					if (location == null && trees.containsKey(type)) {
						// Needed for lambda
						final Coord refCoord = lastCoord;

						List<ActivityFacility> query = trees.get(type).query(MGC.coord2Point(lastCoord).buffer(dist * 1.2).getEnvelopeInternal());

						// Distance should be within the bounds
						List<ActivityFacility> res = query.stream().filter(f -> checkDistanceBound(dist, refCoord, f.getCoord(), 1)).toList();

						if (!res.isEmpty()) {
							location = query.get(ctx.rnd.nextInt(query.size()));
						}

						// Try with larger bounds again
						if (location == null) {
							res = query.stream().filter(f -> checkDistanceBound(dist, refCoord, f.getCoord(), 1.2)).toList();
							if (!res.isEmpty()) {
								location = query.get(ctx.rnd.nextInt(query.size()));
							}
						}
					}

					if (location == null) {
						// sample only coordinate if nothing else is possible
						// Activities without facility entry, or where no facility could be found
						Coord c = sampleLink(ctx.rnd, dist, lastCoord);
						act.setCoord(c);
						lastCoord = c;

						// An activity with type could not be put into correct facility.
						if (trees.containsKey(type)) {
							warning.incrementAndGet();
						}

						continue;
					}

					if (type.equals("work") || type.startsWith("edu"))
						fixedLocations.put(type, location);

					act.setFacilityId(location.getId());
				}

				if (act.getCoord() != null)
					lastCoord = act.getCoord();
				else if (act.getFacilityId() != null)
					lastCoord = facilities.getFacilities().get(act.getFacilityId()).getCoord();

			}
		}
	}

	/**
	 * Sample work place by using commute and distance information.
	 */
	private ActivityFacility sampleCommute(Context ctx, double dist, Coord refCoord, String zoneId, String actType, int age) {

		STRtree index;

		if (actType.equals("edu")) {
//			survey data does not provide information on which type of school it is. This is determined by age instead.
			index = getEduType(age);
		} else {
			index = trees.get("work");
		}

		ActivityFacility destination = null;

		// Only larger distances can be commuters to other zones
		if (dist > 3000) {
			destination = commuter.selectTarget(ctx.rnd, Long.parseLong(zoneId), dist, MGC.coord2Point(refCoord), zone -> sampleZone(index, dist, refCoord, zone, ctx.rnd));
		}

		if (destination == null) {
			// Try selecting within same zone
			destination = sampleZone(index, dist, refCoord, (Geometry) zones.get(zoneId).getDefaultGeometry(), ctx.rnd);
		}

		return destination;
	}

	private STRtree getEduType(int age) {
		STRtree index;
		if (age < 6) {
			index = trees.get("edu_kiga");
		} else if (age >= 6 && age < 12) {
			index = trees.get("edu_primary");
		} else if (age >= 12 && age < 15) {
			index = trees.get("edu_secondary");
		} else if (age >= 15 && age < 31) {
			index = trees.get("edu_higher");
		} else {
//				age > 30 -> edu other
			index = trees.get("edu_other");
		}
		return index;
	}

	/**
	 * Sample a coordinate for which the associated link is not one of the ignored types.
	 */
	private Coord sampleLink(SplittableRandom rnd, double dist, Coord origin) {

		Coord coord = null;
		for (int i = 0; i < 500; i++) {
			coord = rndCoord(rnd, dist, origin);
			Link link = NetworkUtils.getNearestLink(network, coord);
			if (!IGNORED_LINK_TYPES.contains(NetworkUtils.getType(link)))
				break;
		}

		return coord;
	}

	/**
	 * Only samples randomly from the zone, ignoring the distance.
	 */
	private ActivityFacility sampleZone(STRtree index, double dist, Coord refCoord, Geometry zone, SplittableRandom rnd) {

		ActivityFacility location = null;

		List<ActivityFacility> query = index.query(MGC.coord2Point(refCoord).buffer(dist * 1.2).getEnvelopeInternal());

		query = query.stream().filter(f -> checkDistanceBound(dist, refCoord, f.getCoord(), 1)).collect(Collectors.toList());

		while (!query.isEmpty()) {
			ActivityFacility af = query.remove(rnd.nextInt(query.size()));
			if (zone.contains(MGC.coord2Point(af.getCoord()))) {
				location = af;
				break;
			}
		}

		return location;
	}

	/**
	 * General logic to filter coordinate within target distance.
	 */
	private boolean checkDistanceBound(double target, Coord refCoord, Coord other, double factor) {
		double lower = target * 0.8 * (2 - factor);
		double upper = target * 1.15 * factor;

		double dist = CoordUtils.calcEuclideanDistance(refCoord, other);
		return dist >= lower && dist <= upper;
	}

	private static final class Context {
		private final SplittableRandom rnd;

		Context(long seed) {
			rnd = new SplittableRandom(seed);
		}
	}

}
