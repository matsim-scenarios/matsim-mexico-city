package org.matsim.prepare.population;

/**
 * Distribution of attribute values.
 * @param <T> attribute type
 * interface copied from
 * https://github.com/matsim-scenarios/matsim-berlin/blob/6.x/src/main/java/org/matsim/prepare/population/
 * -sme0923
 */
public interface AttributeDistribution<T> {


	/**
	 * Draw a random sample from the distribution.
	 */
	T sample();

}
