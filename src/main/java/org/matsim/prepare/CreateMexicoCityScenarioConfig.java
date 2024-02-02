package org.matsim.prepare;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.*;
import org.matsim.core.replanning.modules.SubtourModeChoice;
import org.matsim.run.Activities;
import org.matsim.run.RunMexicoCityScenario;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(name = "config", description = "Create a MATSim config from scratch and fill it with scenario specific values.")
public class CreateMexicoCityScenarioConfig implements MATSimAppCommand {

	@CommandLine.Option(names = "--input-directory", description = "path to directory with config inputs. Plans, facilities, counts etc.", required = true)
	private Path dir;

	@CommandLine.Option(names = "--sample-size", description = "Scenario sample size. Typically 1, 10 or 25.", defaultValue = "1")
	private double sampleSize;

	@CommandLine.Option(names = "--modes", description = "Transport modes to be included into the simulation.", required = true)
	private String modes;

	@CommandLine.Option(names = "--year", description = "year of count data", defaultValue = "2017")
	int year;

	@CommandLine.Option(names = "--output-directory", description = "output directory")
	Path outputPath;

	private static final String SUBPOP_PERSON = "person";

	public static void main(String[] args) {
		new CreateMexicoCityScenarioConfig().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Set<String> relevantModes = Set.of(modes.split(","));

		String outputString;

		if (MexicoCityUtils.isDefined(outputPath)) {
			outputString = !outputPath.toString().endsWith("/") || !outputPath.toString().endsWith("\\") ? outputPath + "/" : outputPath.toString();
		} else {
			outputString = !dir.toString().endsWith("/") || !dir.toString().endsWith("\\") ? dir + "/" : dir.toString();
		}

		String countsPath = globFile(dir, "*counts_car." + year + "*").toString();
		String facilitiesPath = globFile(dir, "*facilities*").toString();
		String networkPath = globFile(dir, "*network-with-pt*").toString();
		String plansPath = globFile(dir, "*plans*").toString();
		String transitSchedulePath = globFile(dir, "*transitSchedule*").toString();
		String transitVehiclesPath = globFile(dir, "*transitVehicles*").toString();
		String vehicleTypesPath =globFile(dir, "*vehicle-types*").toString();

		Config config = ConfigUtils.createConfig();

		config.controler().setOutputDirectory("./output/mexico-city-1pct");
		config.controler().setRoutingAlgorithmType(ControlerConfigGroup.RoutingAlgorithmType.SpeedyALT);
		config.controler().setRunId("mexico-city-1pct");
		config.controler().setWriteEventsInterval(100);
		config.controler().setWritePlansInterval(100);

		config.counts().setInputFile(countsPath);

		config.facilities().setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.fromFile);
		config.facilities().setInputFile(facilitiesPath);

		config.global().setCoordinateSystem(MexicoCityUtils.CRS);
		config.global().setNumberOfThreads(14);
		config.global().setDefaultDelimiter(",");
		config.global().setInsistingOnDeprecatedConfigVersion(false);

		config.network().setInputFile(networkPath);

		configurePlanCalcScoreModule(config, relevantModes);

		config.plans().setInputFile(plansPath);
		config.plans().setRemovingUnneccessaryPlanAttributes(true);

		configurePlansCalcRouteModule(config);

		configureQsimModule(config);

		config.strategy().setFractionOfIterationsToDisableInnovation(0.9);
		config.strategy().clearStrategySettings();

		StrategyConfigGroup.StrategySettings reRoute = new StrategyConfigGroup.StrategySettings();
		reRoute.setStrategyName("ReRoute");
		reRoute.setSubpopulation(SUBPOP_PERSON);

		StrategyConfigGroup.StrategySettings changeExpBeta = new StrategyConfigGroup.StrategySettings();
		changeExpBeta.setStrategyName("ChangeExpBeta");
		changeExpBeta.setSubpopulation(SUBPOP_PERSON);

		StrategyConfigGroup.StrategySettings smc = new StrategyConfigGroup.StrategySettings();
		smc.setStrategyName("SubtourModeChoice");
		smc.setSubpopulation(SUBPOP_PERSON);

		StrategyConfigGroup.StrategySettings timeAlloc = new StrategyConfigGroup.StrategySettings();
		timeAlloc.setStrategyName("TimeAllocationMutator");
		timeAlloc.setSubpopulation(SUBPOP_PERSON);

		Set.of(reRoute, changeExpBeta, smc, timeAlloc).forEach(config.strategy()::addStrategySettings);

		config.subtourModeChoice().setBehavior(SubtourModeChoice.Behavior.betweenAllAndFewerConstraints);
		config.subtourModeChoice().setConsiderCarAvailability(true);
		config.subtourModeChoice().setCoordDistance(100.);
		config.subtourModeChoice().setModes(new String[]{String.valueOf(relevantModes)});
		config.subtourModeChoice().setProbaForRandomSingleTripMode(0.5);

		config.transit().setTransitScheduleFile(transitSchedulePath);
		config.transit().setUseTransit(true);
		config.transit().setVehiclesFile(transitVehiclesPath);

//		increase stepsize as cdmx is a big city
		config.transitRouter().setExtensionRadius(500.);
		config.transitRouter().setMaxBeelineWalkConnectionDistance(500.);
		config.transitRouter().setSearchRadius(1500.);

		config.vehicles().setVehiclesFile(vehicleTypesPath);

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.abort);

		ConfigUtils.writeConfig(config, outputString + "mexico-city-v" +
			RunMexicoCityScenario.VERSION + "-" + Math.round(sampleSize) + "pct.input.config.xml");

		return 0;
	}

	private static void configurePlansCalcRouteModule(Config config) {
		config.plansCalcRoute().setAccessEgressType(PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLink);
		config.plansCalcRoute().setNetworkModes(Set.of(TransportMode.car));
		config.plansCalcRoute().clearTeleportedModeParams();

		PlansCalcRouteConfigGroup.TeleportedModeParams walk = new PlansCalcRouteConfigGroup.TeleportedModeParams(TransportMode.walk);
		walk.setBeelineDistanceFactor(1.3);
		walk.setTeleportedModeSpeed(1.0555556);

		PlansCalcRouteConfigGroup.TeleportedModeParams bike = new PlansCalcRouteConfigGroup.TeleportedModeParams(TransportMode.bike);
		bike.setBeelineDistanceFactor(1.3);
		bike.setTeleportedModeSpeed(3.1388889);

		PlansCalcRouteConfigGroup.TeleportedModeParams taxibus = new PlansCalcRouteConfigGroup.TeleportedModeParams(MexicoCityUtils.TAXIBUS);
		taxibus.setBeelineDistanceFactor(1.3);
//		the speed for colectivo / taxibus is an assumption as there is almost no data on this transport mode
		taxibus.setTeleportedModeSpeed(3.1388889);

		Set.of(walk, bike, taxibus).forEach(config.plansCalcRoute()::addTeleportedModeParams);
	}

	private static void configurePlanCalcScoreModule(Config config, Set<String> relevantModes) {
		config.planCalcScore().setFractionOfIterationsToStartScoreMSA(0.9);
		config.planCalcScore().setWriteExperiencedPlans(true);

		config.planCalcScore().getActivityParams()
			.stream()
			.filter(p -> p.getActivityType().contains("interaction"))
			.forEach(p -> p.setTypicalDuration(1.));

		Activities.addScoringParams(config, false);

		relevantModes.forEach(m -> {
//			iterate 2 times, first time to create missing modeParams, 2nd time to set correct values.
			for (int i = 0; i <=1; i++) {
				if (config.planCalcScore().getModes().containsKey(m)) {
//				values come from Berlin output config. They have to be changed into mexico's currency later
					if (m.equals(TransportMode.car)) {
						config.planCalcScore().getModes().get(m).setDailyMonetaryConstant(-14.1);
						config.planCalcScore().getModes().get(m).setMonetaryDistanceRate(-1.49E-4);
					} else if (m.equals(TransportMode.pt) || m.equals(MexicoCityUtils.TAXIBUS)) {
//						colectivo / taxibus with equal values as pt
						config.planCalcScore().getModes().get(m).setDailyMonetaryConstant(-3.);
					}
				} else {
					PlanCalcScoreConfigGroup.ModeParams params = new PlanCalcScoreConfigGroup.ModeParams(m);
					config.planCalcScore().addModeParams(params);
				}
			}
		});
		//		set all marginal ut of trav to 0, otherwise simulation will abort (vsp standards)
		config.planCalcScore().getModes().values().forEach(m -> m.setMarginalUtilityOfTraveling(0.));
	}

	private void configureQsimModule(Config config) {
		config.qsim().setEndTime(36 * 3600.);
		config.qsim().setFlowCapFactor(sampleSize / 100);
		config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
		config.qsim().setMainModes(Set.of(TransportMode.car));
		config.qsim().setNumberOfThreads(8);
		config.qsim().setSimEndtimeInterpretation(QSimConfigGroup.EndtimeInterpretation.onlyUseEndtime);
		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
		config.qsim().setStartTime(0.);
		config.qsim().setStorageCapFactor(sampleSize / 100);
		config.qsim().setStuckTime(30.);
		config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
	}
}
