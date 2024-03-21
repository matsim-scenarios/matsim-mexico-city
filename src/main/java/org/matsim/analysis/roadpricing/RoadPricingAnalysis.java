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
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;
import tech.tablesaw.aggregate.AggregateFunctions;
import tech.tablesaw.api.*;
import tech.tablesaw.io.csv.CsvReadOptions;
import tech.tablesaw.joining.DataFrameJoiner;
import tech.tablesaw.selection.Selection;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static tech.tablesaw.aggregate.AggregateFunctions.count;

@CommandLine.Command(name = "roadPricing", description = "Calculates various road pricing related metrics.")
@CommandSpec(
	requires = {"personMoneyEvents.tsv", "persons.csv"},
	produces = {"roadPricing_income_groups.csv", "roadPricing_tolled_agents.csv", "roadPricing_daytime_groups.csv", "roadPricing_tolled_agents_home_locations.csv"}
)
public class RoadPricingAnalysis implements MATSimAppCommand {

	Logger log = LogManager.getLogger(RoadPricingAnalysis.class);

	@CommandLine.Mixin
	private InputOptions input = InputOptions.ofCommand(RoadPricingAnalysis.class);
	@CommandLine.Mixin
	private OutputOptions output = OutputOptions.ofCommand(RoadPricingAnalysis.class);
	@CommandLine.Option(names = "--income-groups", split = ",", description = "List of income for binning", defaultValue = "0,5000,10000,15000,20000,25000,30000,35000,40000,45000,50000")
	private List<Integer> incomeGroups;

	@CommandLine.Option(names = "--hour-groups", split = ",", description = "List of income for binning", defaultValue = "0.,1.,2.,3.,4.,5.,6.,7.,8.,9.,10.,11.,12.,13.,14.,15.,16.,17.,18.,19.,20.,21.,22.,23.,24.")
	private List<Double> hourGroups;

	private static final CsvOptions csv = new CsvOptions();

	String share = "share";
	String person = "person";
	String incomeGroup = "incomeGroup";

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
			.columnTypesPartial(Map.of(person, ColumnType.TEXT, "time", ColumnType.DOUBLE, "purpose", ColumnType.TEXT, "amount", ColumnType.DOUBLE))
			.sample(false)
			.separator(csv.detectDelimiter(input.getPath("personMoneyEvents.tsv"))).build());

//		filter person money events for toll events only
		IntList idx = new IntArrayList();
		for (int i = 0; i < moneyEvents.rowCount(); i++) {
			Row row = moneyEvents.row(i);
			if (row.getString("purpose").contains("toll")) {
				idx.add(i);
			}
		}
		Table filtered = moneyEvents.where(Selection.with(idx.toIntArray()));

		double totalToll = (double) filtered.summarize("amount", AggregateFunctions.sum).apply().column("Sum [amount]").get(0);

		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("roadPricing_tolled_agents.csv").toString()), CSVFormat.DEFAULT)) {
			printer.printRecord("total toll paid [MXN]", totalToll, "paid_FILL1_wght400_GRAD0_opsz48.png", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/roadPricing/");
			printer.printRecord("number of tolled agents", filtered.rowCount(), "tag_FILL1_wght400_GRAD0_opsz48.png", "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/roadPricing/");
		}

		Table joined = new DataFrameJoiner(moneyEvents, person).inner(persons);

//		write income distr of tolled persons
		writeIncomeDistr(joined);
//		write time distr of tolled persons
		writeHourlyDistr(joined);

//		write home locations of tolled persons
		Table homes = joined.retainColumns(person, "home_x", "home_y");
		homes.write().csv(output.getPath("roadPricing_tolled_agents_home_locations.csv").toFile());

		return 0;
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

//		how to sort rows here? this does not work! Using workaround instead. -sme0324
		DoubleColumn shareCol = aggr.numberColumn(1).divide(aggr.numberColumn(1).sum()).setName(this.share);
		aggr.addColumns(shareCol);
		aggr.sortOn(this.share);

		List<String> incomeDistr = new ArrayList<>();

		for (String k : labels.keySet()) {
			for (int i = 0; i < aggr.rowCount() - 1; i++) {
				Row row = aggr.row(i);
				if (row.getString(incomeGroup).equals(k)) {
					incomeDistr.add(k + "," + row.getDouble("Count [person]") + "," + row.getDouble("share"));
					break;
				}
			}
		}

		incomeDistr.sort(Comparator.comparingInt(RoadPricingAnalysis::getLowerBound));

		CSVFormat format = CSVFormat.DEFAULT.builder()
			.setQuote(null)
			.setDelimiter(',')
			.setRecordSeparator("\r\n")
			.build();


		try (CSVPrinter printer = new CSVPrinter(new FileWriter(output.getPath("roadPricing_income_groups.csv").toString()), format)) {
			for (String s : incomeDistr) {
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
