package org.matsim.run;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.MexicoCityMainModeIdentifier;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.prepare.*;
import org.matsim.prepare.opt.RunCountOptimization;
import org.matsim.prepare.opt.SelectPlansFromIndex;
import org.matsim.prepare.population.*;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@CommandLine.Command(header = ":: Open Mexico-City Scenario ::", version = RunMexicoCityScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
	AdjustActivityToLinkDistances.class, ChangeFacilities.class, ChangeModeNames.class, CheckCarAvailability.class, CleanNetwork.class, CreateCommuterRelations.class, CreateCountsFromDatosVialesPortal.class,
	CreateLandUseShp.class, CreateMATSimFacilities.class, CreateMetropolitanAreaPopulation.class, CreateMexicoCityPopulation.class, CreateMexicoCityScenarioConfig.class,
	CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, CreateVehicleTypes.class, DownSamplePopulation.class, ExtractHomeCoordinates.class,
	FixSubtourModes.class, GenerateShortDistanceTrips.class, InitLocationChoice.class, MergePopulations.class, PrepareBikePopulation.class, PrepareIncome.class, PrepareNetwork.class, ResolveGridCoordinates.class,
	RunActivitySampling.class, RunCountOptimization.class, SelectPlansFromIndex.class, SplitActivityTypesDuration.class, XYToLinks.class
})
@MATSimApplication.Analysis({
		LinkStats.class, CheckPopulation.class
})

public class RunMexicoCityScenario extends MATSimApplication {

	Logger log = LogManager.getLogger(RunMexicoCityScenario.class);

	@CommandLine.Option(names = "--bikes-on-network", defaultValue = "false", description = "Define how bicycles are handled: True: as network mode, false: as teleported mode.")
	private boolean bike;

	@CommandLine.Option(names = "--income-area", description = "Path to SHP file specifying income ranges. If provided, income dependent scoring will be used.")
	private Path incomeAreaPath;

	@CommandLine.Option(names = "--random-seed", defaultValue = "4711", description = "setting random seed for the simulation. Can be used to compare several runs with the same config.")
	private long randomSeed;

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(1);

	public static final String VERSION = "1.0";

	public RunMexicoCityScenario(@Nullable Config config) {
		super(config);
	}

	public RunMexicoCityScenario() {
		super(String.format("input/v%s/mexico-city-v%s-1pct.input.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunMexicoCityScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

		// Add all activity types with time bins
		Activities.addScoringParams(config, true);

		config.controller().setOutputDirectory(sample.adjustName(config.controller().getOutputDirectory()));
		config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
		config.controller().setRunId(sample.adjustName(config.controller().getRunId()));

		config.qsim().setFlowCapFactor(sample.getSize() / 100.0);
		config.qsim().setStorageCapFactor(sample.getSize() / 100.0);

		config.global().setRandomSeed(randomSeed);

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.abort);
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);

		for (ReplanningConfigGroup.StrategySettings settings : config.replanning().getStrategySettings()) {
			if (settings.getSubpopulation().equals("person")) {
				if (settings.getStrategyName().equals("ReRoute") || settings.getStrategyName().equals("SubtourModeChoice")
					|| settings.getStrategyName().equals("TimeAllocationMutator")) {
					settings.setWeight(0.15);
				} else if (settings.getStrategyName().equals("ChangeExpBeta")) {
					settings.setWeight(1.0);
				}
			}
		}

		if (bike) {
//			remove bike as teleported mode (standard)
			config.routing().removeTeleportedModeParams(TransportMode.bike);
			Set<String> networkModes = new HashSet<>();
			networkModes.addAll(config.routing().getNetworkModes());
			networkModes.add(TransportMode.bike);
			config.routing().setNetworkModes(networkModes);

			config.qsim().setMainModes(networkModes);

			log.info("Deleted bike as a teleported mode and add added it as a network mode. Bike will be simulated on the network.");
		} else {
			log.info("Bike will be simulated as teleported mode.");
		}

		SimWrapperConfigGroup sw = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		sw.defaultParams().mapCenter = "19.43,-99.13";
		sw.defaultParams().mapZoomLevel = 9.1;
//		relative to config
		sw.defaultParams().shp = "./area/area.shp";

		if (sample.isSet()) {
			sw.defaultParams().sampleSize = sample.getSample();
		}

		ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		if (MexicoCityUtils.isDefined(incomeAreaPath)) {
			PrepareIncome.assignIncomeAttr(new ShpOptions(incomeAreaPath, null, null), scenario.getPopulation());
		}

		ChangeModeNames.changeNames(scenario.getPopulation());

//		reduce link capacities to compensate missing freight traffic in this scenario
//		avg freight percentage of count stations: 0.1029 -> see class freight_volume_analysis.R
		double freightPct = 0.1029;
		for (Link link : scenario.getNetwork().getLinks().values()) {
			if (link.getAllowedModes().contains(TransportMode.car)) {
				if (link.getAttributes().getAttribute("type").toString().contains("highway.residential") ||
					link.getAttributes().getAttribute("type").toString().contains("highway.living_street")) {
//					do not adapt capacity for residential streets

				} else if (link.getAttributes().getAttribute("type").toString().contains("primary") ||
					link.getAttributes().getAttribute("type").toString().contains("trunk") ||
					link.getAttributes().getAttribute("type").toString().contains("motorway")) {
//					As the available count stations are located on the above roadTypes, for trunk, primary and motorway road types the full 10.29% are applied
					link.setCapacity(link.getCapacity() - link.getCapacity() * freightPct);
				} else {
//					for all other road types it is assumed that freightPct might not be as high as on the above roadtypes
					link.setCapacity(link.getCapacity() - link.getCapacity() * (freightPct - 0.03));
				}
			}
		}

		if (bike) {
			PrepareNetwork.prepareNetworkBikeOnNetwork(scenario.getNetwork(), new ShpOptions(Path.of("input/v1.0/area/area.shp"), null, null));

//			add bike vehicle type if missing
			Id<VehicleType> bikeTypeId = Id.create(TransportMode.bike, VehicleType.class);

			if (!scenario.getVehicles().getVehicleTypes().containsKey(bikeTypeId)) {
				VehicleType bikeType = VehicleUtils.createVehicleType(bikeTypeId);

				bikeType.setMaximumVelocity(15 / 3.6);
				bikeType.setLength(2.);
				bikeType.setPcuEquivalents(0.2);
				bikeType.setNetworkMode(TransportMode.bike);
				bikeType.setDescription("This vehicle type is set in case of bike simulation on the network. Per default, bike is simulated as a teleported mode. Max. bike velocity set to 15km/h");

				scenario.getVehicles().addVehicleType(bikeType);
			}
		}
	}

	@Override
	protected void prepareControler(Controler controler) {

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new SwissRailRaptorModule());
				install(new SimWrapperModule());
				install(new PersonMoneyEventsAnalysisModule());

				bind(AnalysisMainModeIdentifier.class).to(MexicoCityMainModeIdentifier.class);

				if (MexicoCityUtils.isDefined(incomeAreaPath)) {
					bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).asEagerSingleton();
				}

				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);

			}
		});
	}
}
