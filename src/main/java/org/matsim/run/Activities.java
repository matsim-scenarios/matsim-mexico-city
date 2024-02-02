package org.matsim.run;

import org.matsim.core.config.Config;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;

/**
 * Defines available activity types.
 */
public enum Activities {
	home,
	other,
//	TODO: delete -> will be changed to accomp_other
	transport,
	accomp_other,
	edu(7, 1),
	work(6, 21),
	personal_business(8, 20),
	leisure(9, 27),
	shopping(8, 20);

	/**
	 * Start time of an activity in hours, can be -1 if not defined.
	 */
	private final double start;

	/**
	 * End time of an activity in hours, can be -1 if not defined.
	 */
	private final double end;

	Activities(double start, double end) {
		this.start = start;
		this.end = end;
	}

	Activities() {
		this.start = -1;
		this.end = -1;
	}


	/**
	 * Apply start and end time to params.
	 */
	public PlanCalcScoreConfigGroup.ActivityParams apply(PlanCalcScoreConfigGroup.ActivityParams params) {
		if (start >= 0)
			params = params.setOpeningTime(start * 3600.);
		if (end >= 0)
			params = params.setClosingTime(end * 3600.);

		return params;
	}

	/**
	 * Add required activity params for the scenario.
	 */
	public static void addScoringParams(Config config, boolean splitTypes) {

		for (Activities value : Activities.values()) {
			// Default length if none is given
			config.planCalcScore().addActivityParams(value.apply(new PlanCalcScoreConfigGroup.ActivityParams(value.name())).setTypicalDuration(6 * 3600));

			if (splitTypes)
				for (long ii = 600; ii <= 97200; ii += 600) {
					config.planCalcScore().addActivityParams(value.apply(new PlanCalcScoreConfigGroup.ActivityParams(value.name() + "_" + ii).setTypicalDuration(ii)));
				}
		}

		config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("car interaction").setTypicalDuration(1));
	}

}
