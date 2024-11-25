package org.matsim.analysis.roadpricing;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.Range;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CsvOptions;
import org.matsim.application.options.InputOptions;
import org.matsim.application.options.OutputOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.gis.ShapeFileWriter;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.prepare.MexicoCityUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import picocli.CommandLine;
import tech.tablesaw.aggregate.AggregateFunctions;
import tech.tablesaw.api.*;
import tech.tablesaw.io.csv.CsvReadOptions;
import tech.tablesaw.joining.DataFrameJoiner;
import tech.tablesaw.selection.Selection;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

import static tech.tablesaw.aggregate.AggregateFunctions.*;

@CommandLine.Command(name = "roadPricing", description = "Calculates various road pricing related metrics.")
@CommandSpec(
	requires = {"personMoneyEvents.tsv", "persons.csv", "config.xml"},
	produces = {"roadPricing_income_groups.csv", "roadPricing_avg_toll_income_groups.csv", "roadPricing_tolled_agents.csv", "roadPricing_daytime_groups.csv", "roadPricing_tolled_agents_home_locations.csv", "roadPricing_area.shp"}
)
public class RoadPricingAnalysis implements MATSimAppCommand {

	Logger log = LogManager.getLogger(RoadPricingAnalysis.class);

	@CommandLine.Mixin
	private InputOptions input = InputOptions.ofCommand(RoadPricingAnalysis.class);
	@CommandLine.Mixin
	private OutputOptions output = OutputOptions.ofCommand(RoadPricingAnalysis.class);
	@CommandLine.Option(names = "--income-groups", split = ",", description = "List of income for binning", defaultValue = "0,4366,10997,18760,56605,137470")
	private List<Integer> incomeGroups;

	@CommandLine.Option(names = "--hour-groups", split = ",", description = "List of income for binning", defaultValue = "0.,1.,2.,3.,4.,5.,6.,7.,8.,9.,10.,11.,12.,13.,14.,15.,16.,17.,18.,19.,20.,21.,22.,23.,24.")
	private List<Double> hourGroups;

	private static final CsvOptions csv = new CsvOptions();

	String share = "share";
	String person = "person";
	String incomeGroup = "incomeGroup";
	String amount = "amount";

	public static void main(String[] args) {
		new RoadPricingAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Map<String, ColumnType> columnTypes = new HashMap<>(Map.of(person, ColumnType.TEXT, "home_x", ColumnType.DOUBLE,
			"home_y", ColumnType.DOUBLE, "income", ColumnType.STRING, "age", ColumnType.INTEGER));

		Table persons = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(input.getPath("persons.csv")))
			.columnTypesPartial(columnTypes)
			.sample(false)
			.separator(csv.detectDelimiter(input.getPath("persons.csv"))).build());

		Table moneyEvents = Table.read().csv(CsvReadOptions.builder(IOUtils.getBufferedReader(input.getPath("personMoneyEvents.tsv")))
			.columnTypesPartial(Map.of(person, ColumnType.TEXT, "time", ColumnType.DOUBLE, "purpose", ColumnType.TEXT, amount, ColumnType.DOUBLE))
			.sample(false)
			.separator(csv.detectDelimiter(input.getPath("personMoneyEvents.tsv"))).build());

//		filter person money events for toll events only
		IntList idx = new IntArrayList();
		for (int i = 0; i < moneyEvents.rowCount(); i++) {
			Row row = moneyEvents.row(i);
			if (row.getString("purpose").toLowerCase().contains("toll")) {
				idx.add(i);
			}
		}
		Table filtered = moneyEvents.where(Selection.with(idx.toIntArray()));

		double totalToll = (double) filtered.summarize(amount, AggregateFunctions.sum).apply().column("Sum [amount]").get(0);
		double medianTollPaid = MexicoCityUtils.calcMedian(filtered.doubleColumn(amount).asList());
		double meanTollPaid = totalToll / filtered.rowCount();

		DecimalFormat f = new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.ENGLISH));

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("roadPricing_tolled_agents.csv").toString()), CSVFormat.DEFAULT)) {
			printer.printRecord("\"total toll paid [MXN]\"", f.format(totalToll));
			printer.printRecord("\"number of tolled agents\"", filtered.rowCount());
			printer.printRecord("\"mean toll paid [MXN]\"", f.format(meanTollPaid));
			printer.printRecord("\"median toll paid [MXN]\"", f.format(medianTollPaid));
		}

		Table joined = new DataFrameJoiner(moneyEvents, person).inner(persons);

//		write income distr of tolled persons
		writeIncomeDistr(joined);
//		write time distr of tolled persons
		writeHourlyDistr(joined);

//		write home locations of tolled persons
		Table homes = joined.retainColumns(person, "home_x", "home_y");
		homes.write().csv(output.getPath("roadPricing_tolled_agents_home_locations.csv").toFile());

//		write road pricing shp such that simwrapper can access it
		Config config = ConfigUtils.loadConfig(input.getPath("config.xml"));

		writeTollAreaShpFile(config);

		return 0;
	}

	private void writeTollAreaShpFile(Config config) throws IOException {
		SimWrapperConfigGroup sw = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		ShpOptions shp = new ShpOptions(Path.of(sw.defaultParams().getValue(MexicoCityUtils.ROAD_PRICING_AREA)), null, null);

		ShapeFileWriter.writeGeometries(shp.readFeatures(), output.getPath("roadPricing_area.shp").toString());

//		We cannot use the same output option for 2 different files, so the string has to be manipulated
		String prj = output.getPath("roadPricing_area.shp").toString().replace(".shp", ".prj");

//		.prj file needs to be simplified to make it readable for simwrapper
		try (BufferedWriter writer = IOUtils.getBufferedWriter(prj)) {
			writer.write(MexicoCityUtils.CRS);
		}
	}

	private void writeHourlyDistr(Table joined) {

		joined.addColumns(IntColumn.create("hour"));

		for (int i = 0; i < joined.rowCount() - 1; i++) {
			Row row = joined.row(i);

			double seconds = row.getDouble("time");

			int hour = Integer.parseInt(String.valueOf(seconds / 3600).split("\\.")[0]);

			if (hour > 24) {
				hour = 24;
			}
			row.setInt("hour", hour);
		}

		Table aggr = joined.summarize(person, count).by("hour");

		DoubleColumn shareCol = aggr.numberColumn(1).divide(aggr.numberColumn(1).sum()).setName(this.share);
		aggr.addColumns(shareCol);

		Table result = Table.create();
		result.addColumns(IntColumn.create("hour"), DoubleColumn.create("Count [person]"), DoubleColumn.create(this.share));

		for (int i = 0; i < aggr.rowCount() - 1; i++) {
			Row row = aggr.row(i);

			if (!String.valueOf(row.getInt("hour")).equals("")) {
				result.addRow(i, aggr);
			}
		}
		result.write().csv(output.getPath("roadPricing_daytime_groups.csv").toFile());
	}

	private void writeIncomeDistr(Table joined) {
		Map<String, Range<Integer>> labels = new HashMap<>();
		for (int i = 0; i < incomeGroups.size() - 1; i++) {
			labels.put(String.format("%d - %d", incomeGroups.get(i), incomeGroups.get(i + 1) - 1),
				Range.of(incomeGroups.get(i), incomeGroups.get(i + 1) - 1));
		}
		labels.put(incomeGroups.get(incomeGroups.size() - 1) + "+", Range.of(incomeGroups.get(incomeGroups.size() - 1), 9999999));
		incomeGroups.add(Integer.MAX_VALUE);

		joined.addColumns(StringColumn.create(incomeGroup));

		for (int i = 0; i < joined.rowCount() - 1; i++) {
			Row row = joined.row(i);

			int income = (int) Math.round(Double.parseDouble(row.getString("income")));

			if (income < 0) {
				log.error("income {} is negative. This should not happen!", income);
				throw new IllegalArgumentException();
			}

			for (Map.Entry<String, Range<Integer>> e : labels.entrySet()) {
				Range<Integer> range = e.getValue();
				if (range.contains(income)) {
					row.setString(incomeGroup, e.getKey());
					break;
				}
			}
		}

		Table aggr = joined.summarize(person, count).by(incomeGroup);

		aggr = new DataFrameJoiner(new DataFrameJoiner(aggr, incomeGroup)
			.inner(joined.summarize(amount, mean).by(incomeGroup)), incomeGroup)
			.inner(joined.summarize(amount, median).by(incomeGroup));

//		how to sort rows here? agg.sortOn does not work! Using workaround instead. -sme0324
		DoubleColumn shareCol = aggr.numberColumn(1).divide(aggr.numberColumn(1).sum()).setName(this.share);
		aggr.addColumns(shareCol);
		aggr.sortOn(this.share);

		List<String> incomeDistr = new ArrayList<>();
		List<String> avgTolls = new ArrayList<>();

		for (String k : labels.keySet()) {
			for (int i = 0; i < aggr.rowCount() - 1; i++) {
				Row row = aggr.row(i);
				if (row.getString(incomeGroup).equals(k)) {
					incomeDistr.add(k + "," + row.getDouble("Count [person]") + "," + row.getDouble("share"));
					avgTolls.add(k + "," + Math.abs(row.getDouble("Mean [amount]")) + "," + Math.abs(row.getDouble("Median [amount]")));
					break;
				}
			}
		}

		incomeDistr.sort(Comparator.comparingInt(RoadPricingAnalysis::getLowerBound));
		avgTolls.sort(Comparator.comparingInt(RoadPricingAnalysis::getLowerBound));

		CSVFormat format = CSVFormat.DEFAULT.builder()
			.setQuote(null)
			.setDelimiter(',')
			.setRecordSeparator("\r\n")
			.build();


//		print income distr
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("roadPricing_income_groups.csv").toString()), format)) {
			printer.printRecord("incomeGroup,Count [person],share");
			for (String s : incomeDistr) {
				printer.printRecord(s);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}

//		print avg toll paid per income group
		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("roadPricing_avg_toll_income_groups.csv").toString()), format)) {
			printer.printRecord("incomeGroup,Mean [amount],Median [amount]");
			for (String s : avgTolls) {
				printer.printRecord(s);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException();
		}
	}

	private static int getLowerBound(String s) {
		String regex = " - ";
		if (s.contains("+")) {
			regex = "\\+";
		}
		return Integer.parseInt(s.split(regex)[0]);
	}
}
