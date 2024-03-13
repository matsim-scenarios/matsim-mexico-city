package org.matsim.run;

import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.roadpricing.RoadPricingScheme;
import org.matsim.contrib.roadpricing.RoadPricingSchemeImpl;
import org.matsim.contrib.roadpricing.RoadPricingUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.misc.Time;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * This class bundles some run parameter options and functionalities connected to road-pricing-scenarios.
 */
public class RoadPricingOptions {

	@CommandLine.Option(names = "--road-pricing-area", description = "Path to SHP file specifying an area, where tolls are charged. If provided, road pricing for mode car will be used.")
	static Path roadPricingAreaPath;

	@CommandLine.Option(names = "--road-pricing-toll", defaultValue = "52.0", description = "Amount of charged toll. Can be absolute or relative (see --road-pricing-type)")
	static double toll;

	@CommandLine.Option(names = "--road-pricing-type", defaultValue = "ABSOLUTE", description = "Enum to decide if the charged toll is an absolute value or relative to the agent's income.")
	static RoadPricingType roadPricingType;

	enum RoadPricingType {ABSOLUTE, RELATIVE_TO_INCOME}

	/**
	 * configure an area based toll scheme.
	 */
	void configureAreaTollScheme(Scenario scenario) {

		RoadPricingSchemeImpl scheme = RoadPricingUtils.addOrGetMutableRoadPricingScheme(scenario);

		RoadPricingUtils.setName(scheme, "ITDP_congestion_pricing");
		RoadPricingUtils.setType(scheme, RoadPricingScheme.TOLL_TYPE_AREA);
		RoadPricingUtils.setDescription(scheme, "Area based road pricing scheme based on work of ITDP Mexico");


//			52MXN = ~ 3 dollar
		RoadPricingUtils.createAndAddGeneralCost(scheme,
			Time.parseTime("06:00:00"),
			Time.parseTime("22:00:00"),
			RoadPricingOptions.toll);

		ShpOptions shp = new ShpOptions(roadPricingAreaPath, null, null);

		List<SimpleFeature> features = shp.readFeatures();

		for (Link link : scenario.getNetwork().getLinks().values()) {
			if (link.getId().toString().contains("pt_")) {
				continue;
			}

			Geometry geometry;

			for (SimpleFeature feat : features) {
				geometry = (Geometry) feat.getDefaultGeometry();

				boolean isInsideArea = MGC.coord2Point(link.getFromNode().getCoord()).within(geometry)
					&& MGC.coord2Point(link.getToNode().getCoord()).within(geometry);

				boolean isRoadType = true;

				List<String> roadTypes = new ArrayList<>(List.of("highway.motorway", "highway.primary", "highway.trunk", "highway.secondary"));

				if (features.size() > 1) {
//					case avenidas principales
					if (feat.getAttribute("name").toString().equals("avenida-gran-canal") || feat.getAttribute("name").toString().equals("avenida-canal-nacional")) {
//						add highway tertiary for avenida gran canal and avenida canal nacional
						roadTypes.add("highway.tertiary");
					}
					isRoadType = roadTypes.stream().anyMatch(link.getAttributes().getAttribute("type").toString()::contains);
				}

				if (isInsideArea && isRoadType) {
					RoadPricingUtils.addLink(scheme, link.getId());
					link.getAttributes().putAttribute("roadPricing", "enabled");
					break;
				}
			}
		}
	}
}
