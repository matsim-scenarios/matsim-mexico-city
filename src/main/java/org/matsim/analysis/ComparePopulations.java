package org.matsim.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Plan;
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

			Map<String, Integer> activityCount = new HashMap<>();

			e.getValue().getPersons().values()
				.stream().forEach(p -> {

					Plan selected = p.getSelectedPlan();

					if (TripStructureUtils.getLegs(selected).isEmpty())
						atHome.getAndSet((atHome.get() + 1));

					TripStructureUtils.getActivities(selected, TripStructureUtils.StageActivityHandling.ExcludeStageActivities)
						.forEach(a -> {
							if (!activityCount.containsKey(a.getType())) {
								activityCount.put(a.getType(), 1);
							} else {
								activityCount.put(a.getType(), activityCount.get(a.getType()) + 1);
							}
						});
				});



			int size = e.getValue().getPersons().size();
			double mobile = (size - atHome.get()) / size;

			AtomicReference<Map<String, Integer>> countMap = new AtomicReference<>();
			countMap.set(activityCount);

			stats.add(new Stats(e.getKey(), size, mobile, countMap));
		}

		List<Double> mobileShares = new ArrayList<>();

		stats.stream()
			.forEach(s -> {
				mobileShares.add(s.mobileShare);
				log.info("Stats for population {}: " +
					"Total size of {} agents with {} % of them mobile.", s.name, s.size, s.mobileShare);

				s.actCount.set(breakDownActTypes(s.actCount.get()));

				double actSum = s.actCount.get().values().stream().mapToInt(Integer::intValue).sum();

				s.actCount.get().entrySet().forEach(a -> log.info("Population {} records {} activities ({}%) of type {} in selected plans."
					, s.name, a.getValue(), Math.round((a.getValue() / actSum)*100), a.getKey()));
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

	private Map<String, Integer> breakDownActTypes(Map<String, Integer> actCount) {
		Map<String, Integer> actCountNoDuration = new HashMap<>();

		for (Map.Entry<String, Integer> e : actCount.entrySet()) {
			String type = e.getKey().replaceAll("\\d", "");

			if (e.getKey().contains(type)) {
				if (!actCountNoDuration.containsKey(type)) {
					actCountNoDuration.put(type, e.getValue());
				} else {
					actCountNoDuration.put(type, actCountNoDuration.get(type) + e.getValue());
				}
			}
		}

		Map<String, Integer> actCountGeneral = new HashMap<>();

		for (Map.Entry<String, Integer> e : actCountNoDuration.entrySet()) {
			String type = e.getKey().split("_")[0];

			if (e.getKey().contains(type)) {
				if (!actCountGeneral.containsKey(type)) {
					actCountGeneral.put(type, e.getValue());
				} else {
					actCountGeneral.put(type, actCountGeneral.get(type) + e.getValue());
				}
			}
		}

		return actCountGeneral;
	}

	private record Stats(String name, int size, double mobileShare, AtomicReference<Map<String, Integer>> actCount) {
	}
}
