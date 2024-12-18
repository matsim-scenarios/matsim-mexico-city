package org.matsim.prepare.population;

import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.util.Pair;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Distribution with fixed probabilities for each entry.
 *
 * @param <T> type of the produced value.
 * class copied from
 * https://github.com/matsim-scenarios/matsim-berlin/blob/6.x/src/main/java/org/matsim/prepare/population/
 * -sme0923
 */
public class EnumeratedAttributeDistribution<T> implements AttributeDistribution<T> {

	private final EnumeratedDistribution<T> dist;

	/**
	 * Constructor.
	 *
	 * @param probabilities map of attributes to their probabilities.
	 */
	public EnumeratedAttributeDistribution(Map<T, Double> probabilities) {
		List<Pair<T, Double>> pairs = probabilities.entrySet().stream().map(
				e -> new Pair<>(e.getKey(), e.getValue())
		).collect(Collectors.toList());

		dist = new EnumeratedDistribution<T>(new MersenneTwister(0), pairs);
	}

	@Override
	public T sample() {
		return dist.sample();
	}
}
