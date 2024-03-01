package org.matsim.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CommandLine.Command(
	name = "check-pt-network",
	description = "Check a given pt network for odd  link freespeeds and lengths."
)
public class CheckPtNetwork implements MATSimAppCommand {
	private Logger log = LogManager.getLogger(CheckPtNetwork.class);
	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private String networkFile;

	public static void main(String[] args) {
		new CheckPtNetwork().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Network network = NetworkUtils.readNetwork(networkFile);

		Map<Id<Link>, Double> distances = new HashMap<>();
		Map<Id<Link>, Double> velocities = new HashMap<>();

		for (Link l : network.getLinks().values()) {
			if (!l.getId().toString().contains("pt_")) {
				continue;
			}

			Id<Link> id = l.getId();

//			group links according to their length
			double length = l.getLength();
			categorizeLinkLength(length, distances, id);


//			group links according to their freespeed
			double freespeed = l.getFreespeed();
			categorizeLinkFreespeed(freespeed, velocities, id);
		}

		Map<Double, List<Id<Link>>> distanceGroups = distances.entrySet()
			.stream()
			.collect(Collectors.groupingBy(Map.Entry::getValue,
				Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

		Map<Double, List<Id<Link>>> velocitiyGroups = velocities.entrySet()
			.stream()
			.collect(Collectors.groupingBy(Map.Entry::getValue,
				Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

		List<Link> velocityEqualsDistance = new ArrayList<>();

		for (Id<Link> linkId : velocitiyGroups.get(99000.)) {
			Link link = network.getLinks().get(linkId);

			if (link.getLength() == link.getFreespeed()) {
				velocityEqualsDistance.add(link);
				log.warn("Link {} with freespeed {} and length {}. The freespeed of this link seems very high + is equal to its length. " +
					"Make sure it is correctly set!", linkId, link.getFreespeed(), link.getLength());
			} else {
				log.warn("Link {} with freespeed {} and length {}. The freespeed of this link seems very high. " +
					"Make sure it is correctly set!", linkId, link.getFreespeed(), link.getLength());
			}
		}

		log.info("############################################### PT link distance groups ###############################################");
		distanceGroups.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(e -> log.info("{} pt links are in length range {}. Length ranges consist of 1000m steps.", e.getValue().size(),
			e.getKey()));

		log.info("############################################### PT link freespeed groups ###############################################");
		velocitiyGroups.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(e -> log.info("{} pt links are in freespeed range {}.Freespeed ranges consist of 5 m/s steps.", e.getValue().size(),
			e.getKey()));

		return 0;
	}

	private void categorizeLinkFreespeed(double freespeed, Map<Id<Link>, Double> velocities, Id<Link> id) {
		if (freespeed <= 5.) {
			velocities.put(id, 5.);
		} else if (freespeed > 5. && freespeed <= 10.) {
			velocities.put(id, 10.);
		} else if (freespeed > 10. && freespeed <= 15.) {
			velocities.put(id, 15.);
		} else if (freespeed > 15. && freespeed <= 20.) {
			velocities.put(id, 20.);
		} else if (freespeed > 20. && freespeed <= 25.) {
			velocities.put(id, 25.);
		} else if (freespeed > 25. && freespeed <= 30.) {
			velocities.put(id, 30.);
		} else if (freespeed > 30. && freespeed <= 35.) {
			velocities.put(id, 35.);
		} else if (freespeed > 35.) {
			velocities.put(id, 99000.);
		} else {
			log.error("freespeed {} of link {} is invalid.", freespeed, id);
			throw new IllegalStateException();
		}
	}

	private void categorizeLinkLength(double length, Map<Id<Link>, Double> distances, Id<Link> id) {
		if (length <= 1000.) {
			distances.put(id, 1000.);
		} else if (length > 1000. && length <= 2000.) {
			distances.put(id, 2000.);
		} else if (length > 2000. && length <= 3000.) {
			distances.put(id, 3000.);
		} else if (length > 3000. && length <= 4000.) {
			distances.put(id, 4000.);
		} else if (length > 4000. && length <= 5000.) {
			distances.put(id, 5000.);
		} else if (length > 5000. && length <= 6000.) {
			distances.put(id, 6000.);
		} else if (length > 6000. && length <= 7000.) {
			distances.put(id, 7000.);
		} else if (length > 7000. && length <= 8000.) {
			distances.put(id, 8000.);
		} else if (length > 8000. && length <= 9000.) {
			distances.put(id, 9000.);
		} else if (length > 9000.) {
			distances.put(id, 99000.);
		} else {
			log.error("Length {} of link {} is invalid.", length, id);
			throw new IllegalStateException();
		}
	}

	private record LinkStats(Id<Link> id, double length, double freespeed, double capacity) {

	}
}
