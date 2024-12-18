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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(name = "config", description = "Create a MATSim config from scratch and fill it with scenario specific values.")
public class CreateMexicoCityScenarioConfig implements MATSimAppCommand {

	@CommandLine.Option(names = "--input-directory", description = "path to directory with config inputs. Plans, facilities, counts etc. Can be a URL, too.", required = true)
	private Path dir;

	@CommandLine.Option(names = "--sample-size", description = "Scenario sample size. Typically 1, 10 or 25.", defaultValue = "1")
	private double sampleSize;

	@CommandLine.Option(names = "--modes", description = "Transport modes to be included into the simulation.", required = true)
	private String modes;

	@CommandLine.Option(names = "--year", description = "year of count data", defaultValue = "2017")
	int year;

	@CommandLine.Option(names = "--output-directory", description = "output directory", required = true)
	Path outputPath;

	private static final String SUBPOP_PERSON = "person";

	private String url = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/";

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

		String countsPath = dir.relativize(globFile(dir, "*counts_car." + year + "*")).toString();
		String facilitiesPath = dir.relativize(globFile(dir, "*facilities*")).toString();
		String networkPath = dir.relativize(globFile(dir, "*network-with-pt*")).toString();
		String plansPath = dir.relativize(globFile(dir, "*plans*")).toString();
		String transitSchedulePath = dir.relativize(globFile(dir, "*transitSchedule*")).toString();
		String transitVehiclesPath = dir.relativize(globFile(dir, "*transitVehicles*")).toString();
		String vehicleTypesPath = dir.relativize(globFile(dir, "*vehicle-types*")).toString();

		Config config = ConfigUtils.createConfig();

		config.controller().setOutputDirectory("./output/output-mexico-city-v1.0-1pct");
		config.controller().setRoutingAlgorithmType(ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT);
		config.controller().setRunId("mexico-city-v1.0-1pct");
		config.controller().setWriteEventsInterval(100);
		config.controller().setWritePlansInterval(100);

		config.counts().setInputFile(url + countsPath);

		config.facilities().setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.fromFile);
		config.facilities().setInputFile(url + facilitiesPath);

		config.global().setCoordinateSystem(MexicoCityUtils.CRS);
		config.global().setNumberOfThreads(14);
		config.global().setDefaultDelimiter(",");
		config.global().setInsistingOnDeprecatedConfigVersion(false);

		config.network().setInputFile(url + networkPath);

		configureScoringModule(config, relevantModes);

		config.plans().setInputFile(url + plansPath);
		config.plans().setRemovingUnneccessaryPlanAttributes(true);

		configureRoutingModule(config);

		configureQsimModule(config);

		config.timeAllocationMutator().setMutationRange(900.);

		config.replanning().setFractionOfIterationsToDisableInnovation(0.9);
		config.replanning().clearStrategySettings();

		ReplanningConfigGroup.StrategySettings reRoute = new ReplanningConfigGroup.StrategySettings();
		reRoute.setStrategyName("ReRoute");
		reRoute.setSubpopulation(SUBPOP_PERSON);

		ReplanningConfigGroup.StrategySettings changeExpBeta = new ReplanningConfigGroup.StrategySettings();
		changeExpBeta.setStrategyName("ChangeExpBeta");
		changeExpBeta.setSubpopulation(SUBPOP_PERSON);

		ReplanningConfigGroup.StrategySettings smc = new ReplanningConfigGroup.StrategySettings();
		smc.setStrategyName("SubtourModeChoice");
		smc.setSubpopulation(SUBPOP_PERSON);

		ReplanningConfigGroup.StrategySettings timeAlloc = new ReplanningConfigGroup.StrategySettings();
		timeAlloc.setStrategyName("TimeAllocationMutator");
		timeAlloc.setSubpopulation(SUBPOP_PERSON);

		Set.of(reRoute, changeExpBeta, smc, timeAlloc).forEach(config.replanning()::addStrategySettings);

		config.subtourModeChoice().setBehavior(SubtourModeChoice.Behavior.betweenAllAndFewerConstraints);
		config.subtourModeChoice().setConsiderCarAvailability(true);
		config.subtourModeChoice().setCoordDistance(100.);
		config.subtourModeChoice().setModes(relevantModes.toArray(new String[0]));
		config.subtourModeChoice().setProbaForRandomSingleTripMode(0.5);

		config.transit().setTransitScheduleFile(url + transitSchedulePath);
		config.transit().setUseTransit(true);
		config.transit().setVehiclesFile(url + transitVehiclesPath);

//		increase stepsize as cdmx is a big city
		config.transitRouter().setExtensionRadius(500.);
		config.transitRouter().setMaxBeelineWalkConnectionDistance(500.);
		config.transitRouter().setSearchRadius(1500.);

		config.vehicles().setVehiclesFile(url + vehicleTypesPath);

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.abort);

		ConfigUtils.writeConfig(config, outputString + "mexico-city-v" +
			RunMexicoCityScenario.VERSION + "-" + Math.round(sampleSize) + "pct.input.config.xml");

		return 0;
	}

	private static void configureRoutingModule(Config config) {
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);
		config.routing().setNetworkModes(Set.of(TransportMode.car));
		config.routing().clearTeleportedModeParams();

		RoutingConfigGroup.TeleportedModeParams walk = new RoutingConfigGroup.TeleportedModeParams(TransportMode.walk);
		walk.setBeelineDistanceFactor(1.3);
		walk.setTeleportedModeSpeed(1.0555556);

		RoutingConfigGroup.TeleportedModeParams bike = new RoutingConfigGroup.TeleportedModeParams(TransportMode.bike);
		bike.setBeelineDistanceFactor(1.3);
		bike.setTeleportedModeSpeed(3.1388889);

		RoutingConfigGroup.TeleportedModeParams taxibus = new RoutingConfigGroup.TeleportedModeParams(MexicoCityUtils.TAXIBUS);
		taxibus.setBeelineDistanceFactor(1.3);
//		the speed for colectivo / taxibus is an assumption as there is almost no data on this transport mode
		taxibus.setTeleportedModeSpeed(3.1388889);

		Set.of(walk, bike, taxibus).forEach(config.routing()::addTeleportedModeParams);
	}

	private static void configureScoringModule(Config config, Set<String> relevantModes) {
		config.scoring().setFractionOfIterationsToStartScoreMSA(0.9);
		config.scoring().setWriteExperiencedPlans(true);

//		marginalUtilityOfMoney germany: 1ut/1€ = 1 -> mx: marginalUtilityOfMoney / PESO_EURO
		double margUtilityOfMoneyMx = config.scoring().getMarginalUtilityOfMoney() / MexicoCityUtils.PESO_EURO;

		DecimalFormat df = new DecimalFormat("0.000");

		DecimalFormatSymbols symbols = new DecimalFormatSymbols();
		symbols.setDecimalSeparator('.');

		df.setDecimalFormatSymbols(symbols);

		margUtilityOfMoneyMx = Double.parseDouble(df.format(margUtilityOfMoneyMx));

		config.scoring().setMarginalUtilityOfMoney(margUtilityOfMoneyMx);

		config.scoring().getActivityParams()
			.stream()
			.filter(p -> p.getActivityType().contains("interaction"))
			.forEach(p -> p.setTypicalDuration(1.));

		Activities.addScoringParams(config, true);

		relevantModes.forEach(m -> {
//			iterate 2 times, first time to create missing modeParams, 2nd time to set correct values.
			for (int i = 0; i <=1; i++) {
				if (config.scoring().getModes().containsKey(m)) {
//				1) values for car cost calculated based on https://www.eleconomista.com.mx/finanzaspersonales/Taxis-por-apps-vs-automovil-propio-Cual-me-conviene-20220526-0110.html
//				for 2022: yearly car fix cost = 29134 MXN -> daily veh fix cost 79.82 MXN
//				based on accumulated inflation from 2017 to 2022 the equivalent for 2017 can be calculated https://www.dineroeneltiempo.com/inflacion/peso-mexicano?valor=1&ano1=2017&ano2=2022
//				cost 2017: 79.82 / (1 + accumulated inflation 2017-2022 (0.2818) => 62.27 MNX / day
//				for distance cost same approach: 2022 yearly distance cost = 33675 MXN; when on avg mexicans travel 15000km per year, 2022 cost per m = 0.002245
//				2017 equivalent: 0.00175 MXN / m

//				2) based on ENIGH2018 (National Household Income and Expenses Survey, Mexico) p. 20 cuadro14 https://www.inegi.org.mx/contenidos/programas/enigh/nc/2018/doc/enigh2018_ns_nota_tecnica.pdf
//				in general, the expenses in urban areas are higher than the national mean values, this also goes for transport cost.
//				This model displays the urban area of Mexico City + surroundings, therefore, a factor will be applied to the car cost:
//				trimestral transport expenses urban (7168) / trimestral transport expenses national (6369) = 1.125

					if (m.equals(TransportMode.car)) {
						double urbanFactor = 7168. / 6369.;
						double dailyMonetaryConstantCarMx = -62.27 * urbanFactor;
						double monetaryDistanceRateCarMx = -0.00175 * urbanFactor;

						config.scoring().getModes().get(m).getComments().replace("dailyMonetaryConstant",
							config.scoring().getModes().get(m).getComments().get("dailyMonetaryConstant") +
								" For the calculation of dailyMonetaryConstant and monetaryDistanceRate for car see class CreateMexicoCityScenarioConfig.");

						config.scoring().getModes().get(m).setDailyMonetaryConstant(dailyMonetaryConstantCarMx);
						config.scoring().getModes().get(m).setMonetaryDistanceRate(monetaryDistanceRateCarMx);
					} else if (m.equals(TransportMode.pt)) {
						double avgPtTicketPrice = Double.parseDouble(df.format(calcPtDailyMonetaryConstant()));

						config.scoring().getModes().get(m).setDailyMonetaryConstant(avgPtTicketPrice);
					} else if (m.equals(MexicoCityUtils.TAXIBUS)) {
//						mean taxibus rides per day: 2 -> see analysis class eod2017_trip_analysis.R
						config.scoring().getModes().get(m).setDailyMonetaryConstant(-7. * 2);
					}
				} else {
					ScoringConfigGroup.ModeParams params = new ScoringConfigGroup.ModeParams(m);
					config.scoring().addModeParams(params);
				}
			}
		});
		//		set all marginal ut of trav to 0, otherwise simulation will abort (vsp standards)
		config.scoring().getModes().values().forEach(m -> m.setMarginalUtilityOfTraveling(0.));
	}

	private static double calcPtDailyMonetaryConstant() {
//		official prices set by the mexican secretary of mobility (SEMOVI):
//		https://www.semovi.cdmx.gob.mx/tramites-y-servicios/transporte-de-pasajeros/nuevas-tarifas-de-transporte-publico-vigentes

		Map<String, Double> ptTicketCosts = new HashMap<>();
		ptTicketCosts.put("metrobus", 6.);
		ptTicketCosts.put("trolebus", 4.);
		ptTicketCosts.put("trenLigero", 3.);
		ptTicketCosts.put("troleBusCuTlahuac", 2.);
		ptTicketCosts.put("troleBusElevado", 7.);
		ptTicketCosts.put("metro", 5.);
		ptTicketCosts.put("servicioOrdinario", 7.5);
		ptTicketCosts.put("servicioEjecutivo", 8.);
		ptTicketCosts.put("rtp_ordinario", 2.);
		ptTicketCosts.put("rtp_expreso", 4.);
		ptTicketCosts.put("rtp_ecobus", 5.);
		ptTicketCosts.put("rtp_nochebus", 7.);
		ptTicketCosts.put("cablebus", 7.);

		AtomicReference<Double> sum = new AtomicReference<>(0.);

		ptTicketCosts.values().forEach(d -> sum.updateAndGet(v -> (v + d)));

//		mean pt rides per day: 2 -> see analysis class eod2017_trip_analysis.R
		return sum.get() / ptTicketCosts.size() * -2;
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
