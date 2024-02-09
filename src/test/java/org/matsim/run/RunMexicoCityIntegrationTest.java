package org.matsim.run;

import org.junit.Test;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.simwrapper.SimWrapperConfigGroup;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class RunMexicoCityIntegrationTest {

	@Test
	public void runPoint1PctIntegrationTest() {

		double sampleSize = 0.001;

		Path outputPath = Path.of("output/it-" + sampleSize * 100 + "pct");

		Config config = ConfigUtils.loadConfig("input/v1.0/mexico-city-v1.0-1pct.input.config.xml");

		config.global().setNumberOfThreads(1);
		config.qsim().setNumberOfThreads(1);
		config.qsim().setFlowCapFactor(sampleSize);
		config.qsim().setStorageCapFactor(sampleSize);
		config.controller().setLastIteration(1);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setOutputDirectory(outputPath.toString());
//		TODO: create this plans file
		config.plans().setInputFile("mexico-city-initial-" + sampleSize * 100 + "pct.plans.xml.gz");

		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		assert MATSimApplication.execute(RunMexicoCityScenario.class) == 0 : "Must return non error code";

		assertThat(outputPath)
			.exists()
			.isNotEmptyDirectory();
	}
}
