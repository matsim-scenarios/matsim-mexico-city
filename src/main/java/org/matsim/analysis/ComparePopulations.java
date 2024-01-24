package org.matsim.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * A short analysis class to compare the share of mobile persons in provided populations.
 * The analysis might be extended in the future.
 */
public class ComparePopulations implements MATSimAppCommand {
	Logger log = LogManager.getLogger(ComparePopulations.class);
	@CommandLine.Option(names = "--directory", description = "path to directory with populations.", required = true)
	private Path directory;

	public static void main(String[] args) {
		new ComparePopulations().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		List<Path> files = new ArrayList<>();

		try (Stream<Path> stream = Files.list(directory)) {
			files.addAll(stream.toList());
		}

		Map<String, Population> populations = new HashMap<>();
		List<Stats> stats = new ArrayList<>();

		files.stream()
			.filter(p -> p.toString().contains("population") || p.toString().contains("plans"))
			.forEach(p -> populations.putIfAbsent(p.getFileName().toString(), PopulationUtils.readPopulation(p.toString())));

		for (Map.Entry<String, Population> e: populations.entrySet()) {
			AtomicReference<Double> atHome = new AtomicReference<>(0.);

			e.getValue().getPersons().values()
				.stream().forEach(p -> {
					if (TripStructureUtils.getLegs(p.getSelectedPlan()).isEmpty())
						atHome.getAndSet((atHome.get() + 1));
				});

			int size = e.getValue().getPersons().size();
			double mobile = (size - atHome.get()) / size;

			stats.add(new Stats(e.getKey(), size, mobile));
		}

		List<Double> mobileShares = new ArrayList<>();

		stats.stream()
			.forEach(s -> {
				mobileShares.add(s.mobileShare);
				log.info("Stats for population {}: " +
					"Total size of {} agents with {} % of them mobile.", s.name, s.size, s.mobileShare);
			});

		Collections.sort(mobileShares);

		int medianIndex = mobileShares.size() / 2;
		double median;
		if (mobileShares.size() % 2 == 0) {
			double upper = mobileShares.get(medianIndex).doubleValue();
			double lower = mobileShares.get(medianIndex - 1).doubleValue();
			median = (lower + upper) / 2.0;
		} else {
			median = mobileShares.get(medianIndex).doubleValue();
		}

		log.info("For the {} analyzed populations " +
			"the median share of mobile persons is: {} %.", populations.size(), median);

		AtomicReference<Double> sum = new AtomicReference<>(0.);

		mobileShares.stream().forEach(s -> sum.set(sum.get() + s));

		double mean = sum.get() / mobileShares.size();

		log.info("For the {} analyzed populations " +
			"the mean share of mobile persons is: {} %.", populations.size(), mean);

		return 0;
	}

	private record Stats(String name, int size, double mobileShare) {
	}
}
