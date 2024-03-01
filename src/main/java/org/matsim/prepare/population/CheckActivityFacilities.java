package org.matsim.prepare.population;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.MatsimFacilitiesReader;
import org.matsim.prepare.MexicoCityUtils;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(
	name = "check-facilities",
	description = "Searches for activities without assigned coords in a plans file and assigns the coord from the activity facility."
)
public class CheckActivityFacilities implements MATSimAppCommand {
	Logger log = LogManager.getLogger(CheckActivityFacilities.class);

	@CommandLine.Option(names = "--input", required = true, description = "Path to input population file.")
	private Path input;

	@CommandLine.Option(names = "--facilities", required = true, description = "Path to facilities file.")
	private Path facilitiesPath;

	public static void main(String[] args) {
		new CheckActivityFacilities().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Population population = PopulationUtils.readPopulation(input.toString());

		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();

		new MatsimFacilitiesReader(MexicoCityUtils.CRS, MexicoCityUtils.CRS, facilities)
			.readFile(facilitiesPath.toString());

		int count = 0;

		for (Person person : population.getPersons().values()) {
			for (Plan p : person.getPlans()) {
				for (Activity a : TripStructureUtils.getActivities(p, TripStructureUtils.StageActivityHandling.ExcludeStageActivities)) {
					if (a.getCoord() == null) {
						a.setCoord(facilities.getFacilities().get(a.getFacilityId()).getCoord());
						log.warn("Person {}: The activity {} has an assigned facility {}, but no coord.", person.getId(), a, a.getFacilityId());
						count++;
					}
				}
			}
		}

		PopulationUtils.writePopulation(population, input.toString());

		log.info("{} activities were assigned a facility, but did not have a coord. The coord of the corresponding facility was assigned to those activities.", count);


		return 0;
	}
}
