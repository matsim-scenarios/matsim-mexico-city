package org.matsim.analysis.roadpricing;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang.math.DoubleRange;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.prepare.population.PrepareIncome;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/**
 * analysis for determining the income distribution of agebs for a given road pricing area.
 */
public class IncomeDistributionAnalysis implements MATSimAppCommand {

	@CommandLine.Option(names = "--income-shp", description = "Path to shp file with income information", required = true)
	private String incomeShpPath;
	@CommandLine.Option(names = "--road-pricing-area-shp", description = "Path to shp file of road pricing area", required = true)
	private String roadPricingShpPath;
	@CommandLine.Option(names = "--output", description = "Path to output folder", required = true)
	private Path output;

	private final Map<String, Integer> incomeGroupCount = new HashMap<>();
	private final Map<String, DoubleRange> incomeGroups = new HashMap<>();

	public static void main(String[] args) {
		new IncomeDistributionAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		ShpOptions incomeShp = new ShpOptions(incomeShpPath, null, null);
		Geometry roadPricingArea = new ShpOptions(roadPricingShpPath, null, null).getGeometry();

		//		data from https://www.economia.com.mx/niveles_de_ingreso.htm / amai.org for 2005
		incomeGroupCount.put("E", 0);
		incomeGroupCount.put("D_me", 0);
		incomeGroupCount.put("D_mas", 0);
		incomeGroupCount.put("C_menos", 0);
		incomeGroupCount.put("C_me", 0);
		incomeGroupCount.put("C_mas", 0);
		incomeGroupCount.put("AB", 0);
		incomeGroupCount.put("#N/A", 0);

		PrepareIncome.prepareIncomeGroupsMap(incomeGroups);

		double inflationFactor = 1.6173;

//		avg hh size 3.6 according to ENIGH 2018, see class PrepareIncome
		int avgHHSize = 4;

		//		apply factor to calc 2017 income values for 2005 income values
		incomeGroups.forEach((key, value) -> incomeGroups.replace(key, new DoubleRange(value.getMinimumDouble() * inflationFactor / avgHHSize, value.getMaximumDouble() * inflationFactor / 4)));
		incomeGroups.put("#N/A", new DoubleRange(0, 999999999));

		Map<Geometry, String> geometries = new HashMap<>();

		for (SimpleFeature feature : incomeShp.readFeatures()) {
			Geometry geom;
			if (!(((Geometry) feature.getDefaultGeometry()).isValid())) {
				geom = BufferOp.bufferOp((Geometry) feature.getDefaultGeometry(), 0.0, BufferParameters.CAP_ROUND);
			} else {
				geom = (Geometry) feature.getDefaultGeometry();
			}
			geometries.put(geom, feature.getAttribute("amai").toString());
		}

		for (Map.Entry<Geometry, String> e : geometries.entrySet()) {
			if (e.getKey().getCentroid().within(roadPricingArea)) {
				incomeGroupCount.replace(e.getValue(), incomeGroupCount.get(e.getValue()) + 1);
			}
		}

//		c_me and c_menos are the same
		incomeGroupCount.replace("C_me", incomeGroupCount.get("C_me") + incomeGroupCount.get("C_menos"));
		incomeGroupCount.remove("C_menos");

		int sum = incomeGroupCount.values().stream().mapToInt(Integer::intValue).sum();

		List<String> sortedKeys = new ArrayList<>(incomeGroupCount.keySet());
		Collections.sort(sortedKeys);

		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output + "/income-level-distr.csv"), CSVFormat.DEFAULT)) {
			printer.printRecord("incomeGroup", "incomeRangePerPerson2017", "count", "share");

			for (String s : sortedKeys) {
				String range = f.format(incomeGroups.get(s).getMinimumDouble()) + "-" + f.format(incomeGroups.get(s).getMaximumDouble());
				printer.printRecord(s, range, incomeGroupCount.get(s), f.format(incomeGroupCount.get(s) / (double) sum));
			}
		}

		return 0;
	}
}
