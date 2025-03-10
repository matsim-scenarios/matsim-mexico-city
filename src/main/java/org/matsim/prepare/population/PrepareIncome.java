package org.matsim.prepare.population;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.lang.math.DoubleRange;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.prepare.MexicoCityUtils;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

@CommandLine.Command(
	name = "income",
	description = "Add an income attribute to each persons of the population."
)
public class PrepareIncome implements MATSimAppCommand {
	static Logger log = LogManager.getLogger(PrepareIncome.class);

	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;
	@CommandLine.Option(names = "--output", description = "Output path for population", required = true)
	private Path output;
	@CommandLine.Mixin()
	private final ShpOptions shp = new ShpOptions();

	private final CsvOptions csv = new CsvOptions(CSVFormat.Predefined.Default);

	private static Map<String, DoubleRange> incomeGroups = new HashMap<>();

	private static SplittableRandom rnd = new SplittableRandom();

	public static void main(String[] args) {
		new PrepareIncome().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Population population = PopulationUtils.readPopulation(input.toString());

		assignIncomeAttr(shp, population);

		PopulationUtils.writePopulation(population, output.toString());
		log.info("Population with income attributes has been written to {}", output);

		return 0;
	}

	/**
	 * puts values for income groups into a map.
	 */
	public static void prepareIncomeGroupsMap(Map<String, DoubleRange> incomeGroups) {
//		data from https://www.economia.com.mx/niveles_de_ingreso.htm / amai.org for 2005
		incomeGroups.put("E", new DoubleRange(0., 2699.));
		incomeGroups.put("D_me", new DoubleRange(2700., 6799.));
		incomeGroups.put("D_mas", new DoubleRange(6800., 11599.));
		incomeGroups.put("C_menos", new DoubleRange(11600., 34999.));
		incomeGroups.put("C_me", new DoubleRange(11600., 34999.));
		incomeGroups.put("C_mas", new DoubleRange(35000., 84999.));
		incomeGroups.put("AB", new DoubleRange(85000., 170000.));
	}

	/**
	 * assign income attributes to each person. This is extracted to a method for being able to call the method in the RunClass.
	 */
	public static void assignIncomeAttr(ShpOptions shp, Population population) {

		prepareIncomeGroupsMap(incomeGroups);

//		shp file contains the avg familiar income group based on analysis of ITDP México
		Map<Geometry, String> geometries = new HashMap<>();

		for (SimpleFeature feature : shp.readFeatures()) {
			Geometry geom;
			if (!(((Geometry) feature.getDefaultGeometry()).isValid())) {
				geom = BufferOp.bufferOp((Geometry) feature.getDefaultGeometry(), 0.0, BufferParameters.CAP_ROUND);
			} else {
				geom = (Geometry) feature.getDefaultGeometry();
			}
			geometries.put(geom, feature.getAttribute("amai").toString());
		}

		int count = 0;
		int countNoHHSize = 0;
		for (Person p: population.getPersons().values()) {

			Coord homeCoord = MexicoCityUtils.getHomeCoord(p);

			if (p.getAttributes().getAttribute(MexicoCityUtils.HOUSEHOLD_SIZE) == null) {
//				some agents do not have a HH size assigned, will assign mean HH size from ENIGH 2018: 3.6 rounded to 4
//				source ENIGH2018: https://www.inegi.org.mx/contenidos/programas/enigh/nc/2018/doc/enigh2018_ns_presentacion_resultados.pdf p9
				p.getAttributes().putAttribute(MexicoCityUtils.HOUSEHOLD_SIZE, 4);
				countNoHHSize++;
			}

			int hhSize = (int) p.getAttributes().getAttribute(MexicoCityUtils.HOUSEHOLD_SIZE);

			if (PersonUtils.getIncome(p) == null) {
				Double income2017 = null;

				for (Map.Entry<Geometry, String> e : geometries.entrySet()) {
					if (MGC.coord2Point(homeCoord).within(e.getKey())) {
//					the income groups are called amai because of the institution who calculated them (amai.org)
						String group = e.getValue();

						if (group.equals("#N/A")) {
//						if na -> random income
							income2017 = rnd.nextDouble(0., 170001.) * 1.6173;
							count++;
						} else {
							DoubleRange incomeRange = incomeGroups.get(group);

//					values for income ranges are for 2005 -> value for 2017 = value2005  + value2005 * accumulated inflation2005-2017 (0.6173)
//					https://www.dineroeneltiempo.com/inflacion/peso-mexicano?valor=1&ano1=2005&ano2=2017
							income2017 = rnd.nextDouble(incomeRange.getMinimumDouble(), incomeRange.getMaximumDouble() + 1) * 1.6173;
						}
						break;
					}
				}

				if (income2017 == null) {
//					if homeLoc is not inside any of the shp areas -> assign randomly
					income2017 = rnd.nextDouble(0., 170001.) * 1.6173;
					count++;
				}

				PersonUtils.setIncome(p, income2017 / hhSize);

			}


		}

		log.info("For {} persons, no household size was assigned. The average household size of 4 persons (ENIGH2018) was assigned to them.", countNoHHSize);
		log.info("For {} persons, a random income was added. This either happened due to no match with the shp file " +
			"OR because the assigned income group of the shp file was n/a.", count);
	}
}
