package org.matsim.prepare.population;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.prepare.MexicoCityUtils;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
	name = "bike-population",
	description = "Check for every leg with mode bike if the agent has the chance to actually use the mode."
)
public class PrepareBikePopulation implements MATSimAppCommand {

	Logger log = LogManager.getLogger(PrepareBikePopulation.class);
	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;
	@CommandLine.Option(names = "--output", description = "Output path of altered population. If not defined, input population will be overwritten.")
	private Path output;
	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	public static void main(String[] args) {
		new PrepareBikePopulation().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input.toString());

		int count = 0;

		for (Person person : population.getPersons().values()) {
			for (Plan p : person.getPlans()) {
				for (Leg l : TripStructureUtils.getLegs(p)) {
					if (l.getRoute() == null || l.getRoute().getRouteType() == null || l.getRoute().getRouteType().equals("generic")) {
						if (l.getMode().equals(TransportMode.bike)) {
							l.setMode(TransportMode.walk);
							count++;
						}
					}
				}
			}
		}

		String outputPath = "";
		if (MexicoCityUtils.isDefined(output)) {
			outputPath = output.toString();
		} else {
			outputPath = input.toString();
		}
		PopulationUtils.writePopulation(population, outputPath);

		log.info("For {} legs, the mode was changed from bike to walk. " +
			"This is due to potential problems when switching from teleported bike to bike as network mode.", count);

		return 0;
	}
}
