package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimAppCommand;
import org.matsim.counts.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.OptionalDouble;

@CommandLine.Command(name = "scale-counts", description = "Set MATSim count to the wished scale.")
public class AdaptCountsScale implements MATSimAppCommand {
	private Logger log = LogManager.getLogger(AdaptCountsScale.class);

	@CommandLine.Option(names = "--input", description = "input counts file", required = true)
	Path input;
	@CommandLine.Option(names = "--output", description = "output counts file")
	Path output;
	@CommandLine.Option(names = "--scale", description = "scale to be applied to count values.", defaultValue = "1")
	double scale;
	@CommandLine.Option(names = "--mode", description = "Transport mode of counts.", defaultValue = TransportMode.car)
	String mode;


	public static void main(String[] args) {
		new AdaptCountsScale().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Counts<Link> counts = new Counts<>();

		MatsimCountsReader reader = new MatsimCountsReader(counts);
		reader.readFile(input.toString());

		for (MeasurementLocation<Link> loc : counts.getMeasureLocations().values()) {
			for (int i = 0; i < 24; i++) {
				OptionalDouble volume = loc.getVolumesForMode(mode).getAtHour(i);
				double hourlyVolume;
				if (volume.isPresent()) {
					hourlyVolume = volume.getAsDouble();
				} else {
					return 2;
				}
				loc.getVolumesForMode(mode).setAtHour(i, hourlyVolume * scale / 100);
			}
		}

		counts.setDescription(counts.getDescription() + ". A scale of " + scale + "% was applied to the hourly count values.");

		String outputPath = input.toString();
		if (MexicoCityUtils.isDefined(output)) {
			outputPath = output.toString();
		}

		new CountsWriter(counts).write(outputPath);

		log.info("Counts with an applied scale of {} % were written to {}.", scale, outputPath);

		return 0;
	}
}
