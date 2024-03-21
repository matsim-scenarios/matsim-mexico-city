package org.matsim.dashboard;

import org.matsim.core.config.Config;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.dashboard.TripDashboard;

import java.util.List;

/**
 * Provider for default dashboards in the scenario.
 * Declared in META-INF/services
 */
public class MexicoCityDashboardProvider implements DashboardProvider {

	String roadPricingAreaPath;

	@Override
	public List<Dashboard> getDashboards(Config config, SimWrapper simWrapper) {

		TripDashboard trips = new TripDashboard("mode_share_ref.csv", null, null);

		return List.of(trips, new RoadPricingDashboard(roadPricingAreaPath));
	}

	/**
	 * set path to shp file of road pricing area.
	 */
	public void setRoadPricingAreaPath(String roadPricingAreaPath) {
		this.roadPricingAreaPath = roadPricingAreaPath;
	}
}
