package org.matsim.prepare.population;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.prepare.MexicoCityUtils;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
	name = "assign-EOD-districts",
	description = "Assigns a home district to each agent based on its home coord + EOD survey districts."
)
public class AssignODSurveyDistricts implements MATSimAppCommand {
	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;
	@CommandLine.Mixin
	private ShpOptions shp;

	private Population population;
	private Integer x = 0;
	private static final Logger log = LogManager.getLogger(AssignODSurveyDistricts.class);

	AssignODSurveyDistricts(Population population, ShpOptions shp) {
		this.population = population;
		this.shp = shp;
	}

	@Override
	public Integer call() throws Exception {

		if (shp.getShapeFile() == null) {
			log.error("Shape file with districts for EOD2017 is required.");
			return 2;
		}

		population = PopulationUtils.readPopulation(input.toString());

		assignDistricts();

		return x;
	}

	final void assignDistricts() {
		List<SimpleFeature> features = shp.readFeatures();

		population.getPersons().values().stream().forEach(p -> {
			Coord homeCoord = new Coord(Double.parseDouble(p.getAttributes().getAttribute(MexicoCityUtils.HOME_X).toString()),
				Double.parseDouble(p.getAttributes().getAttribute(MexicoCityUtils.HOME_Y).toString()));

			features.stream().forEach(f -> {
				if (MGC.coord2Point(homeCoord).within((Geometry) f.getDefaultGeometry())) {
					p.getAttributes().putAttribute(MexicoCityUtils.DISTR, f.getAttribute("Distrito").toString());
				}
			});
		});

//		collect all persons for whom no distr could be assigned and delete them
		List<? extends Person> noDistr = population.getPersons().values()
			.stream()
			.filter(p -> p.getAttributes().getAttribute(MexicoCityUtils.DISTR) == null)
			.toList();

		Double noDistrShare = Double.valueOf(noDistr.size()) / population.getPersons().size();

		if (noDistrShare >= 0.05) {
			log.error("For {} of the populations agents (in total: {} agents) no home district could be assigned, " +
				"please check your shp file and population.", noDistrShare, noDistr.size());
			x=2;
		} else {
			log.warn("For {} persons (share of {} on total population) no home district could be assigned. " +
				"Those persons will be deleted from the population.", noDistr.size(), noDistrShare);
			noDistr.stream().forEach(person -> population.removePerson(person.getId()));
		}
	}
}
