package org.matsim.network;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.*;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.router.NetworkRoutingModule;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.TeleportationRoutingModule;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.*;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilitiesFactory;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.run.RunMexicoCityScenario;

import java.nio.file.Path;
import java.util.*;

@Ignore
public class NetworkRoutingTest {

	private final LeastCostPathCalculatorFactory leastCostPathCalculatorFactory = new SpeedyALTFactory();
	private final TravelDisutility travelDisutility = TravelDisutilityUtils.createFreespeedTravelTimeAndDisutility(ConfigUtils.addOrGetModule(new Config(), PlanCalcScoreConfigGroup.class));
	private final TravelTime travelTime = TravelTimeUtils.createFreeSpeedTravelTime();
	private Map<String, RoutingModule> routingModules = new HashMap<>();
	private ActivityFacilitiesFactory fac = FacilitiesUtils.createActivityFacilities().getFactory();
	private Map<Map.Entry, List<? extends PlanElement>> coordPairs2Legs = new HashMap<>();

//	TODO change paths to svn after upload
	private final String carOnlyNetworkPath = "C:/Users/Simon/Desktop/wd/2024-01-09/first-network.xml.gz";
	private final String ptNetworkPath = "C:/Users/Simon/Desktop/wd/2024-01-09/first-network-with-pt.xml.gz";
	private final String transitSchedulePath = "C:/Users/Simon/Documents/vsp-projects/matsim-mexico-city/input/v1.0/mexico-city-v1.0-transitSchedule.xml.gz";
	private final Path shpPath = Path.of("C:/Users/Simon/Documents/public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/distritos_eod2017_unam/DistritosEODHogaresZMVM2017_utm12n.shp");


	@Test
	public void runPtNetworkRoutingTest() {
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(ptNetworkPath);
		config.global().setCoordinateSystem(RunMexicoCityScenario.CRS);
		config.transit().setTransitScheduleFile(transitSchedulePath.toString());
//		directWalkFactor set to high value to avoid creation of walk leg instead of pt leg when using swissRailRaptorRoutingModule
		config.transitRouter().setDirectWalkFactor(100000.);
		ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

		MutableScenario scenario = (MutableScenario) ScenarioUtils.loadScenario(config);

		PopulationFactory factory = scenario.getPopulation().getFactory();
		Population population = createTestPopulation(factory, scenario.getConfig());
		scenario.setPopulation(population);

		TeleportationRoutingModule walkRouter = new TeleportationRoutingModule(TransportMode.walk, scenario, 1.0555556, 1.3);

		LeastCostPathCalculator calculator = leastCostPathCalculatorFactory.createPathCalculator(scenario.getNetwork(), travelDisutility, travelTime);

		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(scenario.getNetwork());

		Set<String> modes = new HashSet<>();
		modes.add(TransportMode.car);
		Network carNetwork = NetworkUtils.createNetwork(scenario.getConfig().network());
		filter.filter(carNetwork, modes);

		NetworkRoutingModule carRouter = new NetworkRoutingModule(TransportMode.car, factory, carNetwork, calculator);

		RaptorStaticConfig staticConfig = RaptorUtils.createStaticConfig(scenario.getConfig());
		OccupancyData occupancyData = new OccupancyData();

		SwissRailRaptorData data = SwissRailRaptorData.create(scenario.getTransitSchedule(), scenario.getTransitVehicles(), staticConfig, scenario.getNetwork(), occupancyData);
		SwissRailRaptor.Builder builder = new SwissRailRaptor.Builder(data, scenario.getConfig());
		SwissRailRaptor raptor = builder.build();

		SwissRailRaptorRoutingModule ptRouter = new SwissRailRaptorRoutingModule(raptor, scenario.getTransitSchedule(), scenario.getNetwork(), walkRouter);

		routingModules.put(TransportMode.car, carRouter);
		routingModules.put(TransportMode.pt, ptRouter);

		routeOnNetwork(scenario);

		coordPairs2Legs.entrySet().stream().forEach(e -> Assert.assertFalse(e.getValue().isEmpty()));
		coordPairs2Legs.clear();
	}


	@Test
	public void runCarNetworkRoutingTest() {

		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(carOnlyNetworkPath);
		config.global().setCoordinateSystem(RunMexicoCityScenario.CRS);
		MutableScenario scenario = (MutableScenario) ScenarioUtils.loadScenario(config);

		PopulationFactory factory = scenario.getPopulation().getFactory();
		Population population = createTestPopulation(factory, scenario.getConfig());
		scenario.setPopulation(population);

		LeastCostPathCalculator calculator = leastCostPathCalculatorFactory.createPathCalculator(scenario.getNetwork(), travelDisutility, travelTime);

		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(scenario.getNetwork());

		Set<String> modes = new HashSet<>();
		modes.add(TransportMode.car);
		Network carNetwork = NetworkUtils.createNetwork(scenario.getConfig().network());
		filter.filter(carNetwork, modes);

		NetworkRoutingModule carRouter = new NetworkRoutingModule(TransportMode.car, factory, carNetwork, calculator);
		routingModules.put(TransportMode.car, carRouter);

		routeOnNetwork(scenario);

		coordPairs2Legs.entrySet().stream().forEach(e -> Assert.assertFalse(e.getValue().isEmpty()));
		coordPairs2Legs.clear();
	}

	private void routeOnNetwork(Scenario scenario) {

		ActivityFacility depFac = fac.createActivityFacility(Id.create("depDistrict", ActivityFacility.class), new Coord());
		ActivityFacility arrFac = fac.createActivityFacility(Id.create("arrDistrict", ActivityFacility.class), new Coord());

		if (routingModules.containsKey(TransportMode.pt)) {
			for (Person p : scenario.getPopulation().getPersons().values()) {

				String mode = p.getAttributes().getAttribute("mode").toString();

				Map<Coord, Coord> coordPairs = createRandomCoordPairs(50000);

				for (Map.Entry e : coordPairs.entrySet()) {
					depFac.setCoord((Coord) e.getKey());
					arrFac.setCoord((Coord) e.getValue());
					List<? extends PlanElement> planElements = routingModules.get(mode).calcRoute(DefaultRoutingRequest.withoutAttributes(depFac, arrFac,
						28800., p));

					coordPairs2Legs.put(e, planElements);
				}
			}

		} else {
//			route car only (network does not include pt yet)
			for (Person p : scenario.getPopulation().getPersons().values()) {
				String mode = p.getAttributes().getAttribute("mode").toString();
				if (mode.equals(TransportMode.car)) {

					Map<Coord, Coord> coordPairs = createRandomCoordPairs(5000);

					for (Map.Entry e : coordPairs.entrySet()) {
						depFac.setCoord((Coord) e.getKey());
						arrFac.setCoord((Coord) e.getValue());
						List<? extends PlanElement> planElements = routingModules.get(mode).calcRoute(DefaultRoutingRequest.withoutAttributes(depFac, arrFac,
							28800., p));

						coordPairs2Legs.put(e, planElements);
					}
				}
			}
		}
	}

	private Map<Coord, Coord> createRandomCoordPairs(int n) {
		ShpOptions shp = new ShpOptions(shpPath, null, null);

		Geometry geometry = shp.getGeometry();

		Random rnd = new Random(21);

		Map<Coord, Coord> coordPairs = new HashMap<>();
		coordPairs.put(new Coord(1748043.9, 2197302.99), new Coord(1744405.15, 2191783.0700000003));
		coordPairs.put(new Coord(1751830.74, 2208860.62), new Coord(1750494.52, 2193690.9));

		for (int i = 0;i<n;i++) {

			Coord from = new Coord(rnd.nextDouble(geometry.getEnvelopeInternal().getMinX(), geometry.getEnvelopeInternal().getMaxX()),
				rnd.nextDouble(geometry.getEnvelopeInternal().getMinY(), geometry.getEnvelopeInternal().getMaxY()));
			Coord to = new Coord(rnd.nextDouble(geometry.getEnvelopeInternal().getMinX(), geometry.getEnvelopeInternal().getMaxX()),
				rnd.nextDouble(geometry.getEnvelopeInternal().getMinY(), geometry.getEnvelopeInternal().getMaxY()));

			coordPairs.putIfAbsent(from, to);
		}
		return coordPairs;
	}

	private Population createTestPopulation(PopulationFactory factory, Config config) {
		Population testPop = PopulationUtils.createPopulation(config);

		Person carPerson = factory.createPerson(Id.createPersonId("carPerson"));
		carPerson.getAttributes().putAttribute("mode", TransportMode.car);

		Person ptPerson = factory.createPerson(Id.createPersonId("ptPerson"));
		ptPerson.getAttributes().putAttribute("mode", TransportMode.pt);

		testPop.addPerson(carPerson);
		testPop.addPerson(ptPerson);

	return testPop;
	}
}
