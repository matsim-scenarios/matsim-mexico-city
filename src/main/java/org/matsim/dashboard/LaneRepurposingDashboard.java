package org.matsim.dashboard;

import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.*;

/**
 * Shows information about an optional lane repurposing policy case, where lanes for cars are repurposed for bike only.
 */
public class LaneRepurposingDashboard implements Dashboard {
	String analysisDir;
	String bikeCsv = "bikes_on_bike_links_stats.csv";

	public LaneRepurposingDashboard(String analysisDir) {
		if (analysisDir.startsWith("./")) {
			analysisDir = analysisDir.replace("./", "");
		}
		this.analysisDir = analysisDir;
	}
	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Lane Repurposing";
		header.description = "General information about the simulated lane repurposing policy case. Here, every link (except motorways) " +
			"with 2 or more car lanes or more, will be modified such that one of the lanes will be available for bike exclusively.";

		layout.row("first")
			.el(Tile.class, (viz, data) -> {
			viz.dataset = analysisDir + "avg_share_stats.csv";
			viz.height = 0.1;
		});

		layout.row("second")
			.el(Sankey.class, (viz, data) -> {
				viz.title = "Modal Shift Sankey Diagram";
				viz.description = "Base case => Lane Repurposing policy case";
				viz.csv = analysisDir + "modalShift.csv";
				viz.height = 2.;
			});

		layout.row("third")
				.el(Tile.class, (viz, data) -> {
					viz.dataset = analysisDir + "avg_stats.csv";
					viz.height = 0.1;
				});

		layout.row("fourth")
			.el(Table.class, (viz, data) -> {
				viz.title = "Bike trip statistics";
				viz.description = "for bike-only links and in general";
				viz.dataset = analysisDir + bikeCsv;
				viz.showAllRows = false;
			});
	}
}
