package org.matsim.run;

import org.junit.Test;
import org.matsim.application.MATSimApplication;

public class RunIntegrationTest {

	@Test
	public void runScenario() {

		assert MATSimApplication.execute(RunMexicoCityScenario.class,
				"--1pct",
				"--iteration", "2") == 0 : "Must return non error code";

	}
}
