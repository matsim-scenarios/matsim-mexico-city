package org.matsim.run.MexicoCityRoadPricing;

import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.roadpricing.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.run.MexicoCityRoadPricing.MexicoCityRoadPricingModuleDefaults.*;

/**
 * copied from org.matsim.contrib.roadpricing.
 */
public final class MexicoCityRoadPricingModule extends AbstractModule {
	final Logger log = LogManager.getLogger(MexicoCityRoadPricingModule.class);

	private RoadPricingScheme scheme;

	public MexicoCityRoadPricingModule() {}

	/* For the time being this has to be public, otherwise the roadpricing TollFactor
	cannot be considered, rendering integration tests useless, JWJ Jan'20 */
	public MexicoCityRoadPricingModule(RoadPricingScheme scheme ) {
		this.scheme = scheme;
	}

	@Override
	public void install() {
		ConfigUtils.addOrGetModule(getConfig(), RoadPricingConfigGroup.class);

		// TODO sort out different ways to set toll schemes; reduce automagic
		// TODO JWJ: is this still too "automagic"?
		if ( scheme != null) {
			// scheme has come in from the constructor, use that one:
			bind(RoadPricingScheme.class).toInstance(scheme);
		} else {
			// no scheme has come in from the constructor, use a class that reads it from file:
			bind(RoadPricingScheme.class).toProvider(RoadPricingSchemeProvider.class).in(Singleton.class);
		}
		// also add RoadPricingScheme as ScenarioElement.  yyyy TODO might try to get rid of this; binding it is safer
		// (My personal preference is actually to have it as scenario element ... since then it can be set before controler is even called.  Which
		// certainly makes more sense for a clean build sequence.  kai, oct'19)
		bind(RoadPricingInitializer.class).in( Singleton.class );

		// add the toll to the routing disutility.  also includes "randomizing":
		addTravelDisutilityFactoryBinding(TransportMode.car).toProvider(TravelDisutilityIncludingTollFactoryProvider.class);

//		// specific re-routing strategy for area toll:
//		// yyyy TODO could probably combine them somewhat
		addPlanStrategyBinding("ReRouteAreaToll").toProvider(MexicoCityReRouteAreaToll.class);
		addTravelDisutilityFactoryBinding( MexicoCityPlansCalcRouteWithTollOrNot.CAR_WITH_PAYED_AREA_TOLL ).toInstance(new RandomizingTimeDistanceTravelDisutilityFactory(TransportMode.car, getConfig()) );
		addRoutingModuleBinding( MexicoCityPlansCalcRouteWithTollOrNot.CAR_WITH_PAYED_AREA_TOLL ).toProvider(new MexicoCityRoadPricingNetworkRouting() );

		// yyyy TODO It might be possible that the area stuff is adequately resolved by the randomizing approach.  Would need to try
		// that out.  kai, sep'16

		// this is what makes the mobsim compute tolls and generate money events
		addControlerListenerBinding().to(MexicoCityRoadPricingControlerListener.class);

	}
}
