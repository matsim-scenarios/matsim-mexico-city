package org.matsim.dashboard;

import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.ColorScheme;
import org.matsim.simwrapper.viz.Plotly;
import org.matsim.simwrapper.viz.Sankey;
import org.matsim.simwrapper.viz.Table;
import tech.tablesaw.plotly.components.Axis;
import tech.tablesaw.plotly.traces.BarTrace;

import java.util.List;
import java.util.Map;

/**
 * Shows advanced information about an optional road pricing policy case, where lanes for cars are repurposed for bike only.
 */
public class AdvancedRoadPricingDashboard implements Dashboard {

	String analysisDir;
	private static final String SOURCE = "source";
	private static final String MAIN_MODE = "main_mode";
	private static final String SHARE = "share";

	public AdvancedRoadPricingDashboard(String analysisDir) {
		if (analysisDir.startsWith("./")) {
			analysisDir = analysisDir.replace("./", "");
		}
		this.analysisDir = analysisDir;
	}

	@Override
	public void configure(Header header, Layout layout) {

		this.analysisDir = analysisDir.replace('\\', '/');

		header.title = "Road Pricing - advanced";
		header.description = "Advanced information about the simulated road pricing policy case.";

		layout.row("first")
			.el(Plotly.class, (viz, data) -> {
			viz.title = "Modal split";
			viz.description = "of road pricing area";

			viz.layout = tech.tablesaw.plotly.components.Layout.builder()
				.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
				.build();

			Plotly.DataSet ds = viz.addDataset(analysisDir + "mode_share.csv")
				.constant(SOURCE, "RoadPricing Case")
				.aggregate(List.of(MAIN_MODE), SHARE, Plotly.AggrFunc.SUM);


			viz.addDataset("../../baseCaseRoadPricing/mode_share.csv")
				.constant(SOURCE, "Base Case")
				.aggregate(List.of(MAIN_MODE), SHARE, Plotly.AggrFunc.SUM);

			viz.mergeDatasets = true;
			viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
				ds.mapping()
					.name(MAIN_MODE)
					.y(SOURCE)
					.x(SHARE)
			);
		});

		layout.row("second")
			.el(Sankey.class, (viz, data) -> {
				viz.title = "Modal Shift Sankey Diagram";
				viz.description = "Base case => Road Pricing policy case";
				viz.csv = analysisDir + "modalShift.csv";
			})
			.el(Table.class, (viz, data) -> {
				viz.title = "Mode Statistics";
				viz.description = "by main mode, over whole trip (including access & egress)";
				viz.dataset = analysisDir + "trip_stats.csv";
				viz.showAllRows = true;
			});

		layout.row("third")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Mode usage";
				viz.description = "Share of persons using a main mode at least once per day.";

				Plotly.DataSet ds = viz.addDataset(analysisDir + "mode_users.csv");
				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).build(), ds.mapping()
					.x(MAIN_MODE)
					.y("user")
					.name(MAIN_MODE)
				);

				ds.constant(SOURCE, "RoadPricing Case");

				viz.addDataset("../../baseCaseRoadPricing/mode_users.csv")
					.constant(SOURCE, "Base Case");

				viz.multiIndex = Map.of(MAIN_MODE, SOURCE);
				viz.mergeDatasets = true;
			});

		layout.row("departures")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Departures";
				viz.description = "by hour and purpose";
				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.xAxis(Axis.builder().title("Hour").build())
					.yAxis(Axis.builder().title(SHARE).build())
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).build(),
					viz.addDataset(analysisDir + "trip_purposes_by_hour.csv").mapping()
						.name("purpose", ColorScheme.Spectral)
						.x("h")
						.y("departure")
				);
		});

		layout.row("arrivals")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Arrivals";
				viz.description = "by hour and purpose";
				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.xAxis(Axis.builder().title("Hour").build())
					.yAxis(Axis.builder().title(SHARE).build())
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).build(),
					viz.addDataset(analysisDir + "trip_purposes_by_hour.csv").mapping()
						.name("purpose", ColorScheme.Spectral)
						.x("h")
						.y("arrival")
				);
		});
	}

	@Override
	public double priority() {
		return -3;
	}
}
