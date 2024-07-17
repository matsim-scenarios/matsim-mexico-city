package org.matsim.prepare.population;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
	name = "fix-vehicle-availabilities",
	description = "Make sure that car and bike availability is set correctly."
)
public class FixVehicleAvailAttributes implements MATSimAppCommand {
	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;
	@CommandLine.Option(names = "--output", description = "Output path of altered population. If not defined, input population will be overwritten.")
	private Path output;

	public static void main(String[] args) {
		new FixVehicleAvailAttributes().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input.toString());

		for (Person person : population.getPersons().values()) {
			CAR_AVAIL carAvail = CAR_AVAIL.ALWAYS;

			if (PersonUtils.getAge(person) < 18) {
				carAvail = CAR_AVAIL.NEVER;
			}

			PersonUtils.setCarAvail(person, carAvail.toString().toLowerCase());

//			remove bikeAvail attribute because it might be confusing if people who have no bike available are using one
			if (person.getAttributes().getAttribute("bikeAvail") != null) {
				person.getAttributes().removeAttribute("bikeAvail");
			}
		}
		PopulationUtils.writePopulation(population, output.toString());

		return 0;
	}

	private enum CAR_AVAIL {
		ALWAYS,
		NEVER
	}
}
