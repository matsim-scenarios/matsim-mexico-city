package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.application.MATSimAppCommand;
import org.matsim.vehicles.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@CommandLine.Command(
	name = "correct-pt-vehicle-types",
	description = "Changes wrongly assigned transit vehicle types for a given transit vehicle file."
)
public class CorrectPtVehicleTypes implements MATSimAppCommand {
	Logger log = LogManager.getLogger(CorrectPtVehicleTypes.class);

	@CommandLine.Option(names = "--vehicles", description = "Path to transit vehicles file", required = true)
	private String transitVehiclesFile;

	@CommandLine.Option(names = "--output", description = "Path to output transit vehicles file. If not present, input transit vehicles will be overwritten.")
	private Path output;

	public static void main(String[] args) {
		new CorrectPtVehicleTypes().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Vehicles vehicles = VehicleUtils.createVehiclesContainer();

		new MatsimVehicleReader(vehicles).readFile(transitVehiclesFile);

		VehiclesFactory fac = vehicles.getFactory();

		List<Vehicle> substituteVehicles = new ArrayList<>();

		int count = 0;

		for (Vehicle veh : vehicles.getVehicles().values()) {
//			in the gtfs files tren ligero (CMX06) + ferrocarril suburbano (CMX07) were assigned incorrect route types (TRAM + METRO)
//			both should be type "SBAHN"
			if (veh.getId().toString().contains("CMX06") || veh.getId().toString().contains("CMX07")) {
				substituteVehicles.add(fac.createVehicle(veh.getId(), vehicles.getVehicleTypes().get(Id.create("S-Bahn_veh_type", VehicleType.class))));
				count++;
			}
		}

		for (Vehicle v : substituteVehicles) {
			if (!vehicles.getVehicles().containsKey(v.getId())) {
				log.error("Vehicle {} is not present in parsed transit vehicles file. This should not happen! Aborting.", v.getId());
				return 2;
			}
			vehicles.removeVehicle(v.getId());
			vehicles.addVehicle(v);
		}

		String outputPath;
		if (MexicoCityUtils.isDefined(output)) {
			outputPath = output.toString();
		} else {
			outputPath = transitVehiclesFile;
		}

		VehicleUtils.writeVehicles(vehicles, outputPath);

		log.info("For {} transit vehicles the type was changed to type {}.", count, vehicles.getVehicleTypes().get(Id.create("S-Bahn_veh_type", VehicleType.class)).getId());

		return 0;
	}
}
