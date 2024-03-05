package org.matsim.prepare.population;

import com.google.inject.Inject;
import com.google.inject.TypeLiteral;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.contrib.cadyts.car.CadytsCarModule;
import org.matsim.contrib.cadyts.car.CadytsContext;
import org.matsim.contrib.cadyts.general.CadytsScoring;
import org.matsim.contrib.locationchoice.frozenepsilons.FrozenTastes;
import org.matsim.contrib.locationchoice.frozenepsilons.FrozenTastesConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.replanning.choosers.ForceInnovationStrategyChooser;
import org.matsim.core.replanning.choosers.StrategyChooser;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.*;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.prepare.opt.RunCountOptimization;
import org.matsim.run.Activities;
import org.matsim.run.RunMexicoCityScenario;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.matsim.prepare.MexicoCityUtils.CAR_FACTOR;
import static org.matsim.prepare.MexicoCityUtils.isDefined;

/**
 * This scenario class is used to run a MATSim scenario in various stages of the calibration process.
 */
@CommandLine.Command(header = ":: Open Mexico-City Calibration ::", version = RunMexicoCityScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
	CreateLandUseShp.class, CreateMexicoCityPopulation.class, MergePopulations.class,
	DownSamplePopulation.class, CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class,
	CleanNetwork.class, CreateMATSimFacilities.class, InitLocationChoice.class, RunActivitySampling.class,
	SplitActivityTypesDuration.class, CleanPopulation.class, RunCountOptimization.class, SetCarAvailabilityByAge.class
})
public class RunMexicoCityCalibration extends MATSimApplication {


	/**
	 * Flexible activities, which need to be known for location choice and during generation.
	 * A day can not end on a flexible activity.
	 */
	public static final Set<String> FLEXIBLE_ACTS = Set.of("shop_daily", "shop_other", "leisure", "dining");
	private static final Logger log = LogManager.getLogger(RunMexicoCityCalibration.class);
	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(100, 25, 10, 1);
	@CommandLine.Option(names = "--mode", description = "Calibration mode that should be run.")
	private CalibrationMode mode;
	@CommandLine.Option(names = "--weight", description = "Strategy weight.", defaultValue = "1")
	private double weight;
	@CommandLine.Option(names = "--population", description = "Path to population.")
	private Path populationPath;
	@CommandLine.Option(names = "--all-car", description = "All plans will use car mode. Capacity is adjusted automatically by " + CAR_FACTOR, defaultValue = "false")
	private boolean allCar;

	@CommandLine.Option(names = "--scale-factor", description = "Scale factor for capacity to avoid congestions.", defaultValue = "1.5")
	private double scaleFactor;

	@CommandLine.Option(names = "--plan-index", description = "Only use one plan with specified index")
	private Integer planIndex;

	@CommandLine.Option(names = "--subpopulations", description = "Set of subpopulations, which are to be included into the calibration", defaultValue = "person")
	private String subPopulations;

//	list of subpopulations in the scenario. So far, no small or large scale freight traffic has been integrated. sme0124
	private static final String SUB_POP_PERSON = "person";
	public RunMexicoCityCalibration() {
		super("input/v1.0/mexico-city-v1.0-1pct.input.config.xml");
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunMexicoCityCalibration.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {

		if (mode == null)
			throw new IllegalArgumentException("Calibration mode [--mode} not set!");

		if (!isDefined(populationPath)) {
			throw new IllegalArgumentException("Population path is required [--population]");
		}

		String regex = ",";
		if (subPopulations.contains(";")) {
			regex = ";";
		}

//		create mutable set
		Set<String> subPops = new HashSet<>();
		Set.of(subPopulations.split(regex)).forEach(subPops::add);
//		add person subPop if not parsed from run params
		subPops.add(SUB_POP_PERSON);

		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		log.info("Running {} calibration {}", mode, populationPath);

		config.plans().setInputFile(populationPath.toString());
		config.controller().setRunId(mode.toString());
		config.scoring().setWriteExperiencedPlans(true);

		// Location choice does not work with the split types
		Activities.addScoringParams(config, mode != CalibrationMode.LOCATION_CHOICE);

		configureSimwrapperCfgGroup(config);

		if (sample.isSet()) {
			double sampleSize = sample.getSample();

			double countScale = allCar ? CAR_FACTOR : 1;

			config.qsim().setFlowCapFactor(sampleSize * countScale);
			config.qsim().setStorageCapFactor(sampleSize * countScale);

			// Counts can be scaled with sample size
			config.counts().setCountsScaleFactor(sampleSize * countScale);
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
		}

		// Routes are not relaxed yet, and there should not be too heavy congestion
		// factors are increased to accommodate for more than usual traffic
		config.qsim().setFlowCapFactor(config.qsim().getFlowCapFactor() * scaleFactor);
		config.qsim().setStorageCapFactor(config.qsim().getStorageCapFactor() * scaleFactor);

		log.info("Running with flow and storage capacity: {} / {}", config.qsim().getFlowCapFactor(), config.qsim().getStorageCapFactor());

		if (allCar)
			config.transit().setUseTransit(false);

//		clear strategies before adding new ones
		config.replanning().clearStrategySettings();

		// Required for all calibration strategies
		for (String subpopulation : subPops) {
			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
					.setWeight(1.0)
					.setSubpopulation(subpopulation)
			);
		}

		if (mode == CalibrationMode.LOCATION_CHOICE) {

			config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(FrozenTastes.LOCATION_CHOICE_PLAN_STRATEGY)
				.setWeight(weight)
				.setSubpopulation(SUB_POP_PERSON)
			);

			config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
				.setWeight(weight / 5)
				.setSubpopulation(SUB_POP_PERSON)
			);

			// Overwrite these to fix scoring warnings
			config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(8 * 3600.));
			config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("pt interaction").setTypicalDuration(30));

			config.vspExperimental().setAbleToOverwritePtInteractionParams(true);

			config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
			config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);

			FrozenTastesConfigGroup dccg = ConfigUtils.addOrGetModule(config, FrozenTastesConfigGroup.class);

			dccg.setEpsilonScaleFactors(FLEXIBLE_ACTS.stream().map(s -> "1.0").collect(Collectors.joining(",")));
			dccg.setAlgorithm(FrozenTastesConfigGroup.Algotype.bestResponse);
			dccg.setFlexibleTypes(String.join(",", FLEXIBLE_ACTS));
			dccg.setTravelTimeApproximationLevel(FrozenTastesConfigGroup.ApproximationLevel.localRouting);
			dccg.setRandomSeed(2);
			dccg.setDestinationSamplePercent(25);

		} else if (mode == CalibrationMode.CADYTS) {

			// Re-route for all populations
			for (String subpopulation : subPops) {
				config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
					.setWeight(weight)
					.setSubpopulation(subpopulation)
				);
			}

			config.controller().setRunId("cadyts");
			config.controller().setOutputDirectory("./output/cadyts-" + scaleFactor);

			config.scoring().setFractionOfIterationsToStartScoreMSA(0.75);
			config.replanning().setFractionOfIterationsToDisableInnovation(0.75);
			// Need to store more plans because of plan types
			config.replanning().setMaxAgentPlanMemorySize(8);

			config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.ignore);

		} else if (mode == CalibrationMode.ROUTE_CHOICE) {

			// Re-route for all populations
			for (String subpopulation : subPops) {
				config.replanning().addStrategySettings(new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
					.setWeight(weight)
					.setSubpopulation(subpopulation)
				);
			}

		} else if (mode == CalibrationMode.EVAL) {

		} else
			throw new IllegalStateException("Mode not implemented:" + mode);

		return config;
	}

	private void configureSimwrapperCfgGroup(Config config) {
		SimWrapperConfigGroup sw = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		sw.defaultParams().mapCenter = "19.43,-99.13";
		sw.defaultParams().mapZoomLevel = 9.1;
		sw.defaultParams().shp = "./area/area.shp";

		if (sample.isSet()) {
			double countScale = allCar ? CAR_FACTOR : 1;
			sw.sampleSize = sample.getSample() * countScale;
		}
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		ChangeModeNames.changeNames(scenario.getPopulation());

		if (mode == CalibrationMode.CADYTS)
			// each initial plan needs a separate type, so it won't be removed
			for (Person person : scenario.getPopulation().getPersons().values()) {
				for (int i = 0; i < person.getPlans().size(); i++) {
					person.getPlans().get(i).setType(String.valueOf(i));
				}
			}

		if (planIndex != null) {

			log.info("Using plan with index {}", planIndex);

			for (Person person : scenario.getPopulation().getPersons().values()) {
				List<? extends Plan> plans = person.getPlans();
				Set<Plan> toRemove = new HashSet<>();

				for (int i = 0; i < plans.size(); i++) {
					if (i == planIndex) {
						person.setSelectedPlan(plans.get(i));
					} else
						toRemove.add(plans.get(i));
				}
				toRemove.forEach(person::removePlan);
			}
		}

		if (allCar) {

			log.info("Converting all agents to car plans.");

			RoutingModeMainModeIdentifier mmi = new RoutingModeMainModeIdentifier();

			for (Person person : scenario.getPopulation().getPersons().values()) {
				for (Plan plan : person.getPlans()) {
					final List<PlanElement> planElements = plan.getPlanElements();
					final List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);

					for (TripStructureUtils.Trip trip : trips) {
						final List<PlanElement> fullTrip =
							planElements.subList(
								planElements.indexOf(trip.getOriginActivity()) + 1,
								planElements.indexOf(trip.getDestinationActivity()));

						double dist = CoordUtils.calcEuclideanDistance(getCoord(scenario, trip.getOriginActivity()), getCoord(scenario, trip.getDestinationActivity()));

						// very short trips remain walk
						String desiredMode = dist <= 100 ? TransportMode.walk : TransportMode.car;

						if (!Objects.equals(mmi.identifyMainMode(fullTrip), desiredMode)) {
							fullTrip.clear();
							Leg leg = PopulationUtils.createLeg(desiredMode);
							TripStructureUtils.setRoutingMode(leg, desiredMode);
							fullTrip.add(leg);
						}
					}
				}
			}
		}
	}

	private Coord getCoord(Scenario scenario, Activity act) {

		if (act.getCoord() != null)
			return act.getCoord();

		if (act.getFacilityId() != null)
			return scenario.getActivityFacilities().getFacilities().get(act.getFacilityId()).getCoord();

		return scenario.getNetwork().getLinks().get(act.getLinkId()).getCoord();
	}

	@Override
	protected void prepareControler(Controler controler) {

		if (mode == CalibrationMode.LOCATION_CHOICE) {
			FrozenTastes.configure(controler);

			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					binder().bind(new TypeLiteral<StrategyChooser<Plan, Person>>() {
					}).toInstance(new ForceInnovationStrategyChooser<>(5, ForceInnovationStrategyChooser.Permute.no));
				}
			});

		} else if (mode == CalibrationMode.CADYTS) {

			controler.addOverridingModule(new CadytsCarModule());
			controler.setScoringFunctionFactory(new ScoringFunctionFactory() {
				@Inject
				ScoringParametersForPerson parameters;
				@Inject
				private CadytsContext cadytsContext;

				@Override
				public ScoringFunction createNewScoringFunction(Person person) {
					SumScoringFunction sumScoringFunction = new SumScoringFunction();

					Config config = controler.getConfig();

					final ScoringParameters params = parameters.getScoringParameters(person);

					sumScoringFunction.addScoringFunction(new CharyparNagelLegScoring(params, controler.getScenario().getNetwork()));
					sumScoringFunction.addScoringFunction(new CharyparNagelActivityScoring(params));
					sumScoringFunction.addScoringFunction(new CharyparNagelAgentStuckScoring(params));

					final CadytsScoring<Link> scoringFunction = new CadytsScoring<>(person.getSelectedPlan(), config, cadytsContext);
					scoringFunction.setWeightOfCadytsCorrection(30 * config.scoring().getBrainExpBeta());
					sumScoringFunction.addScoringFunction(scoringFunction);

					return sumScoringFunction;
				}
			});

		}

		controler.addOverridingModule(new SimWrapperModule());
	}

	@Override
	protected List<MATSimAppCommand> preparePostProcessing(Path outputFolder, String runId) {
		return List.of(
			new CleanPopulation().withArgs(
				"--plans", outputFolder.resolve(runId + ".output_plans.xml.gz").toString(),
				"--output", outputFolder.resolve(runId + ".output_selected_plans.xml.gz").toString(),
				"--remove-unselected-plans"
			)
		);
	}

	/**
	 * method was used for testing and therefore is @deprecated.
	 */
	@Deprecated(since="1.0")
	protected void setTestPopToScenario(Scenario scenario) {
		Population population = scenario.getPopulation();
		population.getPersons().clear();

		PopulationFactory fac = population.getFactory();

		Person car = fac.createPerson(Id.createPersonId("car"));
		Person bike = fac.createPerson(Id.createPersonId("bike"));
		Person walk = fac.createPerson(Id.createPersonId("walk"));
		Person taxibus = fac.createPerson(Id.createPersonId("taxibus"));
		Person pt = fac.createPerson(Id.createPersonId("pt"));

		Set.of(car, bike, walk, taxibus, pt).forEach(p -> {
			Plan plan = fac.createPlan();

			Activity home1 = fac.createActivityFromCoord("home",
				new Coord(1735035.0689842855, 2182320.2493319022));
			home1.setEndTime(28800.);

			Activity work = fac.createActivityFromCoord("work",
				new Coord(1738767.8423261235, 2165194.347756476));
			work.setEndTime(32000.);

			Activity home2 = fac.createActivityFromCoord("home",
				new Coord(1735035.0689842855, 2182320.2493319022));
			home2.setEndTime(40000.);

			plan.addActivity(home1);
			plan.addLeg(fac.createLeg(p.getId().toString()));
			plan.addActivity(work);
			plan.addLeg(fac.createLeg(p.getId().toString()));
			plan.addActivity(home2);

			p.addPlan(plan);
			p.setSelectedPlan(plan);
			p.getAttributes().putAttribute("subpopulation", SUB_POP_PERSON);
			population.addPerson(p);
		});
	}

	/**
	 * Different calibration stages.
	 */
	public enum CalibrationMode {
		EVAL,
		LOCATION_CHOICE,
		CADYTS,
		ROUTE_CHOICE
	}

}
