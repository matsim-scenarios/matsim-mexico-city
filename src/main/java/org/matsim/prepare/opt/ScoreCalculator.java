package org.matsim.prepare.opt;


import it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.apache.commons.math3.util.FastMath;
import org.optaplanner.core.api.score.buildin.simplebigdecimal.SimpleBigDecimalScore;
import org.optaplanner.core.api.score.calculator.IncrementalScoreCalculator;

import java.math.BigDecimal;

/**
 * Score calculator.
 */
public final class ScoreCalculator implements IncrementalScoreCalculator<PlanAssignmentProblem, SimpleBigDecimalScore> {

	private static final double C = 15.0;
	/**
	 * Error metric.
	 */
	private double error = 0;

	/**
	 * Real counts.
	 */
	private int[] counts;
	/**
	 * Observed counts from sim.
	 */
	private int[] observed;

	private RunCountOptimization.ErrorMetric metric;

	static double diffChange(RunCountOptimization.ErrorMetric err, int count, int old, int update) {

		// Floating point arithmetic still leads to score corruption in full assert mode
		// logarithm can not even be efficiently calculated using big decimal, the corruption needs to be accepted as this point

		return switch (err) {
			case ABS_ERROR -> Math.abs(count - update) - Math.abs(count - old);
			case LOG_ERROR -> FastMath.abs(FastMath.log((update + C) / (count + C))) - FastMath.abs(FastMath.log((old + C) / (count + C)));
			case SYMMETRIC_PERCENTAGE_ERROR -> FastMath.abs((double) (update - count) / (update + count + 2 * C) / 2.) -
					FastMath.abs((double) (old - count) / (old + count + 2 * C) / 2.);
		};
	}


	@Override
	public void resetWorkingSolution(PlanAssignmentProblem problem) {

		observed = new int[problem.counts.length];
		counts = problem.counts;
		metric = problem.metric;

		for (PlanPerson person : problem) {
			for (Int2IntMap.Entry e : person.selected().int2IntEntrySet()) {
				observed[e.getIntKey()] += e.getIntValue();
			}
		}

		calcScoreInternal();
	}

	private void calcScoreInternal() {
		error = 0;

		// Log score needs to shift counts by 1.0 to avoid log 0

		if (metric == RunCountOptimization.ErrorMetric.ABS_ERROR)
			for (int j = 0; j < counts.length; j++)
				error += Math.abs(counts[j] - observed[j]);
		else if (metric == RunCountOptimization.ErrorMetric.LOG_ERROR)
			for (int j = 0; j < counts.length; j++)
//				the closer observed[j] and counts[j] are to each other (lim->1, because of division)
//				-> the smaller the error (ln(1) = 0)
				error += FastMath.abs(Math.log((observed[j] + C) / (counts[j] + C)));
		else if (metric == RunCountOptimization.ErrorMetric.SYMMETRIC_PERCENTAGE_ERROR) {
			for (int j = 0; j < counts.length; j++)
//				the closer observed[j] and counts[j] are to each other (lim->0, because of subtraction)
//				-> the smaller the error (if observed = count -> 0 / "denominator" -> 0)
				error += FastMath.abs((double) (observed[j] - counts[j]) / (observed[j] + counts[j] + 2 * C) / 2);
		}
	}

	@Override
	public void beforeEntityAdded(Object entity) {
	}

	@Override
	public void afterEntityAdded(Object entity) {
	}

	@Override
	public void beforeVariableChanged(Object entity, String variableName) {

		assert variableName.equals("k");
		PlanPerson person = (PlanPerson) entity;

		// remove this persons plan from the calculation
		for (Int2IntMap.Entry e : person.selected().int2IntEntrySet()) {

			int old = observed[e.getIntKey()];
			int update = observed[e.getIntKey()] -= e.getIntValue();

			error += diffChange(metric, counts[e.getIntKey()], old, update);
		}

	}

	@Override
	public void afterVariableChanged(Object entity, String variableName) {

		assert variableName.equals("k");
		PlanPerson person = (PlanPerson) entity;

		// add this persons contribution to the score
		for (Int2IntMap.Entry e : person.selected().int2IntEntrySet()) {

			int old = observed[e.getIntKey()];
			int update = observed[e.getIntKey()] += e.getIntValue();

			error += diffChange(metric, counts[e.getIntKey()], old, update);
		}
	}

	@Override
	public void beforeEntityRemoved(Object entity) {
	}

	@Override
	public void afterEntityRemoved(Object entity) {

	}

	@Override
	public SimpleBigDecimalScore calculateScore() {
		return SimpleBigDecimalScore.of(BigDecimal.valueOf(-error));
	}

	double scoreEntry(Int2IntMap.Entry e) {

		int idx = e.getIntKey();

		// Calculate impact compared to a plan without the observations of this plan
		// old can not get negative

		return -diffChange(metric, counts[idx], Math.max(0, observed[idx] - e.getIntValue()), observed[idx]);
	}
}
