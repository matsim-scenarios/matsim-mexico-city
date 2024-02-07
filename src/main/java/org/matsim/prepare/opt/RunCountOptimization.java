package org.matsim.prepare.opt;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.counts.Count;
import org.matsim.counts.Counts;
import org.matsim.counts.MatsimCountsReader;
import org.matsim.counts.Volume;
import org.matsim.prepare.MexicoCityUtils;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@CommandLine.Command(name = "run-count-opt", description = "Select plans to match counts data")
public class RunCountOptimization implements MATSimAppCommand {

	static final Logger log = LogManager.getLogger(RunCountOptimization.class);

	@CommandLine.Option(names = "--input", description = "Path to input plans (Usually experienced plans).", required = true)
	private Path input;

	@CommandLine.Option(names = "--output", description = "Output plan selection csv.", required = true)
	private Path output;

	@CommandLine.Option(names = "--network", description = "Path to network", required = true)
	private Path networkPath;

	@CommandLine.Option(names = "--counts", description = "Path to counts", required = true)
	private Path countsPath;

	@CommandLine.Option(names = "--all-car", description = "Plans have been created with the all car option and counts should be scaled. ", defaultValue = "false")
	private boolean allCar;

	@CommandLine.Option(names = "--metric")
	private ErrorMetric metric = ErrorMetric.ABS_ERROR;

	@CommandLine.Option(names = "--sample-size", defaultValue = "0.01")
	private double sampleSize;

	@CommandLine.Option(names = "--k", description = "Number of plans to use from each agent", defaultValue = "5")
	private int maxK;

	@CommandLine.Option(names = "--count-values", description = "Number of time steps (usually hours), for which each count station records traffic volumes. " +
		"1 = daily (avg) traffic volume.", defaultValue = "1")
	private static int h;

	@CommandLine.Mixin
	private CsvOptions csv;

	private Object2IntMap<Id<Link>> linkMapping;

	public static void main(String[] args) {
		new RunCountOptimization().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Counts<Link> linkCounts = new Counts<>();

		new MatsimCountsReader(linkCounts).readFile(countsPath.toString());

//		prepare int array to store counts (volumes) -sme0124
		int[] counts = new int[linkCounts.getCounts().size() * h];

		linkMapping = new Object2IntLinkedOpenHashMap<>();

		int k = 0;
		for (Count<Link> value : linkCounts.getCounts().values()) {
			Map<Integer, Volume> volumes = value.getVolumes();
			if (h == 1) {
//				for the MATSim Open Mexico-City scenario we only have daily average traffic volumes for each count station
				counts[k] = (int) volumes.get(h).getValue();

				if (allCar)
					counts[k] = (int) (counts[k] * MexicoCityUtils.CAR_FACTOR);
			} else {
//				although for the MATSim Open Mexico-City scenario there only exist daily count volumes
//				I want to keep the possibility to process hourly count volumes
				for (int i = 1; i <= h; i++) {
					if (volumes.containsKey(i)) {
						int idx = k * h + i;

						counts[idx] = (int) volumes.get(i).getValue();
						if (allCar)
							counts[idx] = (int) (counts[idx] * MexicoCityUtils.CAR_FACTOR);
					}
				}
			}
			linkMapping.put(value.getId(), k++);
		}

		Network network = NetworkUtils.readNetwork(networkPath.toString());

		List<PlanPerson> persons = processPopulation(input, network, linkCounts);

		PlanAssignmentProblem problem = new PlanAssignmentProblem(maxK, metric, persons, counts);

		log.info("Collected {} relevant plans", persons.size());

		if (allCar)
			log.info("Scaled counts by car factor of {}", MexicoCityUtils.CAR_FACTOR);

		// Error scales are very different so different betas are needed
		double beta = switch (metric) {
			case ABS_ERROR -> 1;
			case LOG_ERROR -> 100;
			case SYMMETRIC_PERCENTAGE_ERROR -> 300;
		};

		problem.iterate(5000, 0.5, beta, 0.01);

		PlanAssignmentProblem solution = solve(problem);

		try (CSVPrinter printer = csv.createPrinter(output)) {

			printer.printRecord("id", "idx");

			for (PlanPerson person : solution) {
				printer.printRecord(person.getId(), person.getK() - person.getOffset());
			}
		}

		return 0;
	}

	/**
	 * Create an array for each person.
	 */
	private List<PlanPerson> processPopulation(Path input, Network network, Counts<Link> linkCounts) {

		Population population = PopulationUtils.readPopulation(input.toString());
		List<PlanPerson> persons = new ArrayList<>();

		Set<Id<Link>> links = linkCounts.getCounts().keySet();

		SplittableRandom rnd = new SplittableRandom(0);

		for (Person person : population.getPersons().values()) {

			int scale = (int) (1 / sampleSize);

			Int2IntMap[] plans = new Int2IntMap[maxK];
			for (int i = 0; i < plans.length; i++) {
				plans[i] = new Int2IntOpenHashMap();
			}

			boolean keep = false;

			int offset = 0;
			// Commercial traffic, which can be chosen to not be included at all
			if (person.getId().toString().startsWith("commercialPersonTraffic")) {

				offset = 1;
				// if other trips have been scaled, these unscaled trips are scaled as well
				if (allCar)
					// scale with mean of CAR_FACTOR
					scale += (rnd.nextDouble() < 0.85 ? 5: 4);
			}

			// Index for plan
			int k = offset;
			for (Plan plan : person.getPlans()) {

				if (k >= maxK)
					break;

				for (PlanElement el : plan.getPlanElements()) {
					if (el instanceof Leg leg) {

						if (!leg.getMode().equals(TransportMode.car))
							continue;

						// TODO: scale travel time with factor from the leg

						if (leg.getRoute() instanceof NetworkRoute route) {
							boolean relevant = route.getLinkIds().stream().anyMatch(links::contains);

							double time = leg.getDepartureTime().seconds();

							if (relevant) {
								keep = true;
								for (Id<Link> linkId : route.getLinkIds()) {

									Link link = network.getLinks().get(linkId);

									// Assume free speed travel time
									time += link.getLength() / link.getFreespeed() + 1;

									if (linkMapping.containsKey(linkId)) {
										int idx = linkMapping.getInt(linkId);

										if (h == 1) {
											plans[k].merge(idx * RunCountOptimization.h, scale, Integer::sum);
										} else {
											int hour = (int) Math.floor(time / 3600);
											if (hour >= h)
												continue;

											plans[k].merge(idx * RunCountOptimization.h + hour, scale, Integer::sum);
										}
									}
								}
							}
						}
					}
				}
				k++;
			}

			if (keep) {
				for (int i = 0; i < plans.length; i++) {
					if (plans[i].isEmpty())
						plans[i] = PlanPerson.NOOP_PLAN;
				}

				persons.add(new PlanPerson(person.getId(), offset, plans));
			}
		}

		return persons;
	}

	private PlanAssignmentProblem solve(PlanAssignmentProblem problem) {

		// Loading fails if xerces is on the classpath
		// -> xerces needs to be excluded in pom for matsim-application, matsim-vsp (and freight if used)

//		the file solver.xml was created manually by CR for matsim-berlin. It was then copied to this scenario.
		SolverFactory<PlanAssignmentProblem> factory = SolverFactory.createFromXmlResource("solver.xml");

		Solver<PlanAssignmentProblem> solver = factory.buildSolver();

		AtomicLong ts = new AtomicLong(System.currentTimeMillis());

		solver.addEventListener(event -> {

			// Only log every x seconds
			if (ts.get() + 60_000 < System.currentTimeMillis()) {
				log.info("New best solution: {}", event.getNewBestScore());
				ts.set(System.currentTimeMillis());
			}
		});

		return solver.solve(problem);
	}

	/**
	 * Error metric to calculate.
	 */
	enum ErrorMetric {
		ABS_ERROR,
		LOG_ERROR,
		SYMMETRIC_PERCENTAGE_ERROR
	}
}
