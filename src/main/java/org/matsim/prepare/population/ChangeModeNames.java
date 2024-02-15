package org.matsim.prepare.population;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.prepare.MexicoCityUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
	name = "change-mode-names",
	description = "Change mode names from act sampling to real matsim transport modes."
)
public class ChangeModeNames implements MATSimAppCommand {
	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;
	@CommandLine.Option(names = "--output", description = "Output path for population", required = true)
	private Path output;


	public static void main(String[] args) {
		new ChangeModeNames().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input.toString());

		changeNames(population);

		PopulationUtils.writePopulation(population, output.toString());
		return 0;
	}

	public static void changeNames(Population population) {
		for (Person p : population.getPersons().values()) {
			for (Plan plan : p.getPlans()){
				List<Leg> legs = TripStructureUtils.getLegs(plan);
				for (Leg l : legs) {
					if (l.getMode().equals("colectivo")) {
						l.setMode(MexicoCityUtils.TAXIBUS);
					} else if (l.getMode().equals(TransportMode.motorcycle)) {
						l.setMode(TransportMode.car);
					} else if (l.getMode().equals(TransportMode.ride)) {
						l.setMode(TransportMode.walk);
					}
				}
			}
		}
	}
}
