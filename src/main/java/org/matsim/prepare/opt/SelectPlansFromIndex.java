package org.matsim.prepare.opt;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.prepare.MexicoCityUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@CommandLine.Command(name = "select-plans-idx", description = "Select plan index as specified from input. " +
	"If noPlans > maxK this class will delete plans (based on their score) until maxK is met.")
public class SelectPlansFromIndex implements MATSimAppCommand {
	Logger log = LogManager.getLogger(SelectPlansFromIndex.class);

	@CommandLine.Option(names = "--input", description = "Path to input plans.", required = true)
	private Path input;

	@CommandLine.Option(names = "--output", description = "Desired output plans.", required = true)
	private Path output;

	@CommandLine.Option(names = "--csv", description = "Path to input plans (Usually experienced plans).", required = true)
	private Path csv;

	@CommandLine.Option(names = "--exp-plans", description = "Path to experienced plans")
	private Path experiencedPlansPath;

	@CommandLine.Mixin
	private CsvOptions csvOpt;

	public static void main(String[] args) {
		new SelectPlansFromIndex().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input.toString());
		Object2IntMap<Id<Person>> idx = new Object2IntOpenHashMap<>();
		try (CSVParser parser = csvOpt.createParser(csv)) {
			for (CSVRecord row : parser) {
				idx.put(Id.createPersonId(row.get("id")), Integer.parseInt(row.get("idx")));
			}
		}

		Population experienced = null;

		if (MexicoCityUtils.isDefined(experiencedPlansPath)) {
			experienced = PopulationUtils.readPopulation(experiencedPlansPath.toString());
			log.info("Keeping plan with best score for all persons, who are not present in input csv file.");
		}

		Set<Id<Person>> toRemove = new HashSet<>();

		int count = 0;

		for (Person person : population.getPersons().values()) {

			List<? extends Plan> plans = person.getPlans();
			Set<Plan> removePlans = new HashSet<>();

			//for all persons which are included in csv:
			// set "optimal" plan from counts opt csv file as selected and delete all other plans for this person
			// will be 0 if no value is present
			AtomicReference<Integer> planIndex = new AtomicReference<>(idx.getInt(person.getId()));

//			planIndex = -1 -> for commercial traffic, not needed here
			if (planIndex.get() == -1) {
				toRemove.add(person.getId());
				continue;
			}

//			for all other persons:
//			set "optimal" plan based on score of experienced plans.
//			Therefore, each of the experienced plans should have a score in order to have a bigger plan pool -sme0224
			if (!idx.containsKey(person.getId()) && experienced != null) {

				List<? extends Plan> expPlans = experienced.getPersons().get(person.getId()).getPlans();

				List<? extends Plan> filtered = new ArrayList<>(expPlans
					.stream()
					.filter(e -> e.getScore() != null).toList());

				AtomicReference<Double> maxScore = new AtomicReference<>(0.);

				filtered.forEach(p -> {
					if (p.getScore() >= maxScore.get()) {
						maxScore.set(p.getScore());
						planIndex.set(expPlans.indexOf(p));
					}
				});
				count++;

			}

			for (int i = 0; i < plans.size(); i++) {
				if (i == planIndex.get()) {
					person.setSelectedPlan(plans.get(i));
				} else
					removePlans.add(plans.get(i));
			}
			removePlans.forEach(person::removePlan);
		}

		toRemove.forEach(population::removePerson);

		PopulationUtils.writePopulation(population, output.toString());

		log.info("For {} persons the optimal plan according to count optimization was chosen. For those agents, all other plans were deleted.", population.getPersons().size() - count);
		log.info("For {} persons the optimal plan according to plan scores was chosen. For those agents, all other plans were deleted.", count);

		return 0;
	}
}
