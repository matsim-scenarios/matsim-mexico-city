package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(name = "bike-links", description = "Analyze and check vehicles, which travel on bike links created for the lane repurposing scenario.")
public class BikeLinksAnalysis implements MATSimAppCommand, LinkEnterEventHandler, LinkLeaveEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler {

	Logger log = LogManager.getLogger(BikeLinksAnalysis.class);

	@CommandLine.Option(names = "--dir", description = "Path to run directory.")
	private Path runDir;
	@CommandLine.Option(names = "--output", description = "Path to output directory.", defaultValue = "/analysis/repurposeLanes")
	private String output;

	private Map<Id<Vehicle>, List<Id<Link>>> carsOnBikeLinks = new HashMap<>();
//	this needs to be a list instead of a map, because we need it ordered
	private Map<Id<Vehicle>, List<Map.Entry<Id<Link>, Double>>> bikeTravelTimes = new HashMap<>();
	private Map<Id<Vehicle>, Map<Id<Link>, Double>> linkLeaveTimes = new HashMap<>();
	List<BikeData> bikeData = new ArrayList<>();
	private Network network;

	public static void main(String[] args) {
		new BikeLinksAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(this);

		manager.initProcessing();

		String eventsPath = globFile(runDir, "*output_events.*").toString();
		String networkPath = globFile(runDir, "*output_network.*").toString();

		this.network = NetworkUtils.readNetwork(networkPath);

		EventsUtils.readEvents(manager, eventsPath);

		manager.finishProcessing();

//		write csv files
		File analysisDir = new File(runDir.toString() + output);

		if (!analysisDir.exists()) {
			analysisDir.mkdirs();
		}

//		write cars on bike links csv
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(analysisDir.getPath() + "/cars_on_bike_only_links.csv"), CSVFormat.DEFAULT)) {
			printer.printRecord("vehicleId", "linkId");

			for (Map.Entry<Id<Vehicle>, List<Id<Link>>> e : this.carsOnBikeLinks.entrySet()) {
				String vehId = e.getKey().toString();

				for (Id<Link> l : e.getValue()) {
					printer.printRecord(vehId, l);
				}
			}
		}

//		write bike stats on bike links
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(analysisDir.getPath() + "/bikes_on_bike_links_stats.csv"), CSVFormat.DEFAULT)) {
			printer.printRecord("vehicleId", "travelTime [s]", "travelDistance [m]", "avgSpeed [m/s]", "totalTravelTime [s]",
				"totalTravelDistance [m]", "totalAvgSpeed [m/s]", "shareTravelTimeOnBikeOnlyLinks", "shareTravelDistOnBikeOnlyLinks");

			for (BikeData data : this.bikeData) {
				printer.printRecord(data.bikeId, data.travelTime, data.travelDist, data.avgSpeed, data.totalTravelTime, data.totalTravelDist, data.totalAvgSpeed,
					data.shareTravelTimeBikeLink, data.shareTravelDistBikeLink);
			}
		}

		return 0;
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
//		this event is only needed for getting travel times of agents who travel only one link
		Id<Vehicle> id = event.getVehicleId();

		if (id.toString().contains(TransportMode.bike)) {
			linkLeaveTimes.putIfAbsent(id, new HashMap<>());
			linkLeaveTimes.get(id).put(event.getLinkId(), event.getTime());
		}

	}

	@Override
	public void handleEvent(LinkEnterEvent event) {
		registerVehicle(event.getVehicleId(), event.getLinkId(), event.getTime());
	}

	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {
		registerVehicle(event.getVehicleId(), event.getLinkId(), event.getTime());
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {
		if (event.getVehicleId().toString().contains(TransportMode.bike) && bikeTravelTimes.containsKey(event.getVehicleId())) {
//			calc stats and put into data list
			Id<Vehicle> vehId = event.getVehicleId();

			List<Map.Entry<Id<Link>, Double>> bikeTravels = bikeTravelTimes.get(vehId);


			double travelTime = 0;
			double travelDist = 0;

			List<Map.Entry<Id<Link>, Double>> filtered = bikeTravels.stream().filter(en -> en.getKey().toString().contains("bike_")).toList();

			if (filtered.isEmpty()) {
//					do nothing
			}

			for (Map.Entry<Id<Link>, Double> entry : filtered) {
				if (bikeTravels.indexOf(entry) != 0) {
					travelTime += entry.getValue() - bikeTravels.get(bikeTravels.indexOf(entry) - 1).getValue();
					travelDist += network.getLinks().get(entry.getKey()).getLength();
				} else {
					travelTime += linkLeaveTimes.get(vehId).get(filtered.get(0).getKey()) - filtered.get(0).getValue();
					travelDist += network.getLinks().get(filtered.get(0).getKey()).getLength();
				}
			}

			double avgSpeed;

			if (travelTime > 0) {
				avgSpeed = travelDist /travelTime;
			} else {
				avgSpeed = 0;
			}

			if (travelTime < 0) {
				log.error("Travel time {} for vehicle {} is <= 0, this should not happen.", travelTime, vehId);
				throw new IllegalArgumentException();
			} else if (travelTime > 0) {
				bikeData.add(getAllStats(vehId, travelTime, travelDist, avgSpeed, bikeTravels));
			}

			bikeTravelTimes.remove(event.getVehicleId());
			linkLeaveTimes.remove(event.getVehicleId());
		}
	}

	private BikeData getAllStats(Id<Vehicle> vehId, double travelTime, double travelDist, double avgSpeed, List<Map.Entry<Id<Link>, Double>> bikeTravels) {
		double totalTravelTime = 0;
		double totalTravelDist = 0;

		for (Map.Entry<Id<Link>, Double> entry : bikeTravels) {
			if (bikeTravels.indexOf(entry) != 0) {
				totalTravelTime += entry.getValue() - bikeTravels.get(bikeTravels.indexOf(entry) - 1).getValue();
				totalTravelDist += network.getLinks().get(entry.getKey()).getLength();
			}
		}

		double totalAvgSpeed = 0;
		double shareTravelTime = 0;
		double shareTravelDist = 0;

		if (totalTravelTime > 0) {
			totalAvgSpeed = totalTravelDist /totalTravelTime;
			shareTravelTime = travelTime / totalTravelTime;
		}

		if (totalTravelDist > 0) {
			shareTravelDist = travelDist / totalTravelDist;
		}

		return new BikeData(vehId, travelTime, travelDist, avgSpeed, totalTravelTime, totalTravelDist, totalAvgSpeed,
			shareTravelTime, shareTravelDist);
	}

	private void registerVehicle(Id<Vehicle> vehId, Id<Link> linkId, double time) {

		if (vehId.toString().contains(TransportMode.bike)) {
			bikeTravelTimes.putIfAbsent(vehId, new ArrayList<>());
			bikeTravelTimes.get(vehId).add(new AbstractMap.SimpleEntry<>(linkId, time));
		}

		if (linkId.toString().contains(TransportMode.bike)) {
			Link link = this.network.getLinks().get(linkId);

			if (link.getAllowedModes().contains(TransportMode.bike) && !link.getAllowedModes().contains(TransportMode.car)
				&& vehId.toString().contains(TransportMode.car)) {
				carsOnBikeLinks.putIfAbsent(vehId, new ArrayList<>());
				carsOnBikeLinks.get(vehId).add(linkId);
			}
		}
	}

	private static final class BikeData {
		Id<Vehicle> bikeId;
		double travelTime;
		double travelDist;
		double avgSpeed;
		double totalTravelTime;
		double totalTravelDist;
		double totalAvgSpeed;
		double shareTravelTimeBikeLink;
		double shareTravelDistBikeLink;

		private BikeData(Id<Vehicle> bikeId, double travelTime, double travelDist, double avgSpeed, double totalTravelTime,
						 double totalTravelDist, double totalAvgSpeed, double shareTravelTimeBikeLink, double shareTravelDistBikeLink) {
			this.bikeId = bikeId;
			this.travelTime = travelTime;
			this.travelDist = travelDist;
			this.avgSpeed = avgSpeed;
			this.totalTravelTime = totalTravelTime;
			this.totalTravelDist = totalTravelDist;
			this.totalAvgSpeed = totalAvgSpeed;
			this.shareTravelTimeBikeLink = shareTravelTimeBikeLink;
			this.shareTravelDistBikeLink = shareTravelDistBikeLink;
		}
	}
}