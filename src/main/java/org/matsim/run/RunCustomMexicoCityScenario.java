package org.matsim.run;

import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.simwrapper.SimWrapperConfigGroup;

import javax.annotation.Nullable;

public class RunCustomMexicoCityScenario extends MATSimApplication {

	private final RunMexicoCityScenario baseScenario = new RunMexicoCityScenario();

	public RunCustomMexicoCityScenario() {
		super("input/v1.0/mexico-city-v1.0-1pct.input.config.xml");
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunMexicoCityScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
//		apply all config changes from base scenario
		baseScenario.prepareConfig(config);

		SimWrapperConfigGroup simWrapperConfigGroup = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);
		simWrapperConfigGroup.defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

//		custom config changes can be implemented here

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
//		apply all scenario changes from base scenario
		baseScenario.prepareScenario(scenario);

//		custom scenario changes can be implemented here

	}

	@Override
	protected void prepareControler(Controler controler) {
//		apply all controler changes from base scenario
		baseScenario.prepareControler(controler);

//		custom controler changes can be implemented here

	}
}
