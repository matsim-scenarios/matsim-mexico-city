package org.matsim.dashboard;

import org.matsim.analysis.roadpricing.RoadPricingAnalysis;
import org.matsim.prepare.MexicoCityUtils;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.*;

import java.util.List;

/**
 * Shows information about an optional road pricing policy case.
 */
public class RoadPricingDashboard implements Dashboard {
	String share = "share";

	public RoadPricingDashboard() {}
	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Road Pricing";
		header.description = "General information about the simulated road pricing policy case.";

		layout.row("first").el(Tile.class, (viz, data) -> {
			viz.dataset = data.compute(RoadPricingAnalysis.class, "roadPricing_tolled_agents.csv");
			viz.height = 0.1;
		});

		layout.row("second")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Tolled agents";
				viz.description = "income groups";
				viz.stacked = false;
				viz.dataset = data.compute(RoadPricingAnalysis.class, "roadPricing_income_groups.csv");
				viz.x = "incomeGroup";
				viz.xAxisName = "income group";
				viz.yAxisName = share;
				viz.columns = List.of(share);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "Tolled agents";
				viz.description = "per hour";
				viz.stacked = false;
				viz.dataset = data.compute(RoadPricingAnalysis.class, "roadPricing_daytime_groups.csv");
				viz.x = "hour";
				viz.xAxisName = "hour";
				viz.yAxisName = share;
				viz.columns = List.of(share);
			});

		layout.row("third")
			.el(Hexagons.class, (viz, data) -> {

				viz.title = "Tolled agents home locations";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;

				viz.file = data.compute(RoadPricingAnalysis.class, "roadPricing_tolled_agents_home_locations.csv");
				viz.projection = MexicoCityUtils.CRS;
				viz.addAggregation("home locations", "person", "home_x", "home_y");
			})
			.el(MapPlot.class, (viz, data) -> {
				viz.title = "Toll area";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;
				viz.setShape(data.compute(RoadPricingAnalysis.class, "roadPricing_area.shp"), "id");
			});

		layout.row("fourth")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Paid toll per income group";
				viz.description = "mean and median values";
				viz.stacked = false;
				viz.dataset = data.compute(RoadPricingAnalysis.class, "roadPricing_avg_toll_income_groups.csv");
				viz.x = "incomeGroup";
				viz.xAxisName = "income group";
				viz.yAxisName = "Toll paid [MXN]";
				viz.columns = List.of("Mean [amount]","Median [amount]");
			});
	}

	@Override
	public double priority() {
		return -2;
	}
}
