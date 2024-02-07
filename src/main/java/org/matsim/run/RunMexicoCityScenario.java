package org.matsim.run;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.analysis.traffic.TravelTimeAnalysis;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.prepare.CreateCountsFromDatosVialesPortal;
import org.matsim.prepare.CreateMexicoCityScenarioConfig;
import org.matsim.prepare.CreateVehicleTypes;
import org.matsim.prepare.opt.RunCountOptimization;
import org.matsim.prepare.population.*;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.util.List;

@CommandLine.Command(header = ":: Open Mexico-City Scenario ::", version = RunMexicoCityScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
		AdjustActivityToLinkDistances.class, CleanNetwork.class, CreateCommuterRelations.class, CreateCountsFromDatosVialesPortal.class, CreateLandUseShp.class,
		CreateMATSimFacilities.class, CreateMetropolitanAreaPopulation.class, CreateMexicoCityPopulation.class,	CreateMexicoCityScenarioConfig.class,
	CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, CreateVehicleTypes.class, DownSamplePopulation.class, ExtractHomeCoordinates.class,
		ExtractRelevantFreightTrips.class, FixSubtourModes.class, GenerateShortDistanceTrips.class, InitLocationChoice.class, MergePopulations.class,
		ResolveGridCoordinates.class, RunActivitySampling.class, RunCountOptimization.class, TrajectoryToPlans.class, XYToLinks.class
})
@MATSimApplication.Analysis({
		TravelTimeAnalysis.class, LinkStats.class, CheckPopulation.class
})

public class RunMexicoCityScenario extends MATSimApplication {

	public static final String VERSION = "1.0";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(25, 10, 1);


	public RunMexicoCityScenario(@Nullable Config config) {
		super(config);
	}

	public RunMexicoCityScenario() {
		super(String.format("input/v%s/template-v%s-1pct.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunMexicoCityScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

		// Add all activity types with time bins

		for (long ii = 600; ii <= 97200; ii += 600) {

			for (String act : List.of("home", "restaurant", "other", "visit", "errands", "accomp_other", "accomp_children",
					"educ_higher", "educ_secondary", "educ_primary", "educ_tertiary", "educ_kiga", "educ_other")) {
				config.planCalcScore()
						.addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams(act + "_" + ii).setTypicalDuration(ii));
			}

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("work_" + ii).setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("business_" + ii).setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("leisure_" + ii).setTypicalDuration(ii)
					.setOpeningTime(9. * 3600.).setClosingTime(27. * 3600.));

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_daily_" + ii).setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_other_" + ii).setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
		}

		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("car interaction").setTypicalDuration(60));
		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("other").setTypicalDuration((double) 600 * 3));

		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("freight_start").setTypicalDuration((double) 60 * 15));
		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("freight_end").setTypicalDuration((double) 60 * 15));

		config.controler().setOutputDirectory(sample.adjustName(config.controler().getOutputDirectory()));
		config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
		config.controler().setRunId(sample.adjustName(config.controler().getRunId()));

		config.qsim().setFlowCapFactor(sample.getSize() / 100.0);
		config.qsim().setStorageCapFactor(sample.getSize() / 100.0);

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.abort);
		config.plansCalcRoute().setAccessEgressType(PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLink);

		// TODO: Config options
//		TODO: setStrategyWeights to adequate values
//		TODO: implement ptFareModule
//		TODO: set correct money values in scoring Params -> they are in € right now.

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

//		TODO: iterate through pop and change each leg with mode colectivo to taxibus.
//		TODO: write MexicoCityMainModeIdentifier, which includes colectivo / taxibus
//		TODO: reduce linkCapacities because freight is missing



	}

	@Override
	protected void prepareControler(Controler controler) {

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new SwissRailRaptorModule());

				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);

			}
		});
	}
}
