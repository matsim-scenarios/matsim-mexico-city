package org.matsim.run;

import org.junit.jupiter.api.Test;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.simwrapper.SimWrapperConfigGroup;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunMexicoCityIntegrationTest {

	String URL = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/mx/mexico-city/mexico-city-v1.0/input/";

	@Test
	 void runPoint1PctIntegrationTest() {

		double sampleSize = 0.0001;

		Path outputPath = Path.of("output/it-0.1pct");

		Config config = ConfigUtils.loadConfig("input/v1.0/mexico-city-v1.0-1pct.input.config.xml");

		config.global().setNumberOfThreads(1);
		config.qsim().setNumberOfThreads(1);
		config.qsim().setFlowCapFactor(sampleSize);
		config.qsim().setStorageCapFactor(sampleSize);
		config.controller().setLastIteration(1);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setOutputDirectory(outputPath.toString());
		config.plans().setInputFile(URL + "mexico-city-v1.0-" + sampleSize * 100 + "pct.input.plans.xml.gz");
		config.replanning().getStrategySettings().forEach(s -> s.setWeight(0.1));

		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		assert MATSimApplication.execute(RunMexicoCityScenario.class, config, "run", "--1pct",
			"--income-area", "input/v1.0/nivel_amai/nivel_amai.shp") == 0 : "Must return non error code";

		assertThat(outputPath)
			.exists()
			.isNotEmptyDirectory();
	}
}
