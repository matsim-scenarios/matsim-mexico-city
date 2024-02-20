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
import org.matsim.facilities.*;
import org.matsim.prepare.MexicoCityUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

@CommandLine.Command(
	name = "change-facilities",
	description = "Changes assigned facilities in a plans file."
)
public class ChangeFacilities implements MATSimAppCommand {

	Logger log = LogManager.getLogger(ChangeFacilities.class);

	@CommandLine.Option(names = "--input", required = true, description = "Path to input population file.")
	private Path input;

	@CommandLine.Option(names = "--facilities-old", required = true, description = "Path to old facilities file.")
	private Path oldFacilitiesPath;

	@CommandLine.Option(names = "--facilities-new", required = true, description = "Path to new facilities file.")
	private Path newFacilitiesPath;

	@CommandLine.Option(names = "--output", description = "Path to output population file. If not present, input population will be overwritten.")
	private Path output;

	private int count = 0;

	public static void main(String[] args) {
		new ChangeFacilities().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input.toString());

		ActivityFacilities oldFacilities = FacilitiesUtils.createActivityFacilities();
		ActivityFacilities newFacilities = FacilitiesUtils.createActivityFacilities();

		new MatsimFacilitiesReader(MexicoCityUtils.CRS, MexicoCityUtils.CRS, oldFacilities)
			.readFile(oldFacilitiesPath.toString());
		new MatsimFacilitiesReader(MexicoCityUtils.CRS, MexicoCityUtils.CRS, newFacilities)
			.readFile(newFacilitiesPath.toString());

		for (Person person : population.getPersons().values()) {
			for (Plan p : person.getPlans()) {
//				stageActivities do not have facilities
				TripStructureUtils.getActivities(p, TripStructureUtils.StageActivityHandling.ExcludeStageActivities)
					.stream()
					.filter(a -> a.getFacilityId() != null)
					.toList()
					.forEach(a -> findAndReplaceFacility(a, oldFacilities, newFacilities));
			}
		}

		String outputPath;
		if (MexicoCityUtils.isDefined(output)) {
			outputPath = output.toString();
		} else {
			outputPath = input.toString();
		}

		PopulationUtils.writePopulation(population, outputPath);
		log.info("For the input plans file, {} assigned facilities have been changed.", count);
		return 0;
	}

	private void findAndReplaceFacility(Activity a, ActivityFacilities oldFacilities, ActivityFacilities newFacilities) {
		ActivityFacility oldFac = oldFacilities.getFacilities().get(a.getFacilityId());

		boolean facilityFound = false;
//		if fac have 1) the same coord 2) the same number of activities and 3) all single activities are the same -> oldFac = newFac
		for (ActivityFacility fac : newFacilities.getFacilities().values()) {
			if (fac.getCoord().equals(oldFac.getCoord()) && fac.getActivityOptions().size() == oldFac.getActivityOptions().size()) {
				List<Boolean> checkList = new ArrayList<>();
				for (String act : fac.getActivityOptions().keySet()) {
					if (!oldFac.getActivityOptions().keySet().contains(act)) {
						checkList.add(false);
					}
				}

				if (!checkList.contains(false)){
					a.setFacilityId(fac.getId());
					facilityFound = true;
					count++;
				}
			}
		}

		if (!facilityFound) {
			log.error("For old facility {}, the corresponding new facility could not be found. This should not happen.", oldFac.getId());
			throw new NoSuchElementException();
		}
	}
}
