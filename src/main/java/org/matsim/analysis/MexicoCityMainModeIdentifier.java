package org.matsim.analysis;

import com.google.inject.Inject;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.prepare.MexicoCityUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Hierarchical main mode identifier.
 */
public final class MexicoCityMainModeIdentifier implements AnalysisMainModeIdentifier {
	private final List<String> modeHierarchy = new ArrayList<>();

	@Inject
	public MexicoCityMainModeIdentifier() {
		this.modeHierarchy.add(TransportMode.transit_walk);
		this.modeHierarchy.add(TransportMode.walk);
		this.modeHierarchy.add(TransportMode.bike);
		this.modeHierarchy.add(TransportMode.car);

		this.modeHierarchy.add(TransportMode.pt);
		this.modeHierarchy.add(MexicoCityUtils.TAXIBUS);
	}

	@Override
	public String identifyMainMode(List<? extends PlanElement> planElements) {
		int mainModeIndex = -1;
		List<String> modesFound = new ArrayList<>();
		for (PlanElement pe : planElements) {
			int index;
			String mode;
			if (pe instanceof Leg leg) {
				leg = (Leg) pe;
				mode = leg.getMode();
			} else {
				continue;
			}
			if (mode.equals(TransportMode.non_network_walk)) {
				// skip, this is only a helper mode for access, egress and pt transfers
				continue;
			}
			if (mode.equals(TransportMode.transit_walk)) {
				mode = TransportMode.walk;
			}

			modesFound.add(mode);
			index = modeHierarchy.indexOf(mode);
			if (index < 0) {
				throw new IllegalArgumentException("unknown mode=" + mode);
			}
			if (index > mainModeIndex) {
				mainModeIndex = index;
			}
		}
		if (mainModeIndex == -1) {
			throw new IllegalStateException("no main mode found for trip " + planElements);
		}

		return modeHierarchy.get(mainModeIndex);
	}
}
