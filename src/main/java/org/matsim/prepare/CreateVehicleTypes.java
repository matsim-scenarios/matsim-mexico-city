package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimAppCommand;
import org.matsim.run.RunMexicoCityScenario;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Set;

@CommandLine.Command(name = "vehicle-types", description = "Create MATSim vehicle types for the mexico-city scenario.")
public class CreateVehicleTypes implements MATSimAppCommand {

	Logger log = LogManager.getLogger(CreateVehicleTypes.class);

	@CommandLine.Option(names = "--directory", description = "path to output directory.", required = true)
	private Path directory;

	@CommandLine.Option(names = "--modes", description = "Transport modes to be included into the simulation.", defaultValue = TransportMode.car)
	private String modes;

	public static void main(String[] args) {
		new CreateVehicleTypes().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Set<String> vehicularModes = Set.of(modes.split(","));

		String outputString = !directory.toString().endsWith("/") || !directory.toString().endsWith("\\") ? directory + "/" : directory.toString();

		Vehicles vehicles = VehicleUtils.createVehiclesContainer();

		int count = 0;

		for (String mode : vehicularModes) {
			VehicleType type = VehicleUtils.createVehicleType(Id.create(mode, VehicleType.class));

			if (mode.equals(TransportMode.car)) {
//				max. velocity allowed in Mexico: 110km/h
				type.setMaximumVelocity(110 / 3.6);
				type.setDescription("The general max. allowed velocity for cars in Mexico is 110km/h.");
			} else if (mode.equals(TransportMode.bike)) {
				type.setMaximumVelocity(15 / 3.6);
				type.setLength(2.);
				type.setPcuEquivalents(0.2);
				type.setNetworkMode(TransportMode.bike);
				type.setDescription("This vehicle type is set in case of bike simulation on the network. Per default, bike is simulated as a teleported mode. Max. bike velocity set to 15km/h");
			} else {
				count++;
				log.warn("You are trying to create a VehicleType for mode {}. Specific vehicle type information will only be set for modes {} and {}."
					, mode, TransportMode.car, TransportMode.bike);
			}
			vehicles.addVehicleType(type);
		}

		VehicleUtils.writeVehicles(vehicles, outputString + "mexico-city-v" + RunMexicoCityScenario.VERSION + "-vehicle-types.xml");

		if (count > 0) {
			log.warn("For {} modes no specific vehicle type information have been set.", count);
		}

		return 0;
	}
}
