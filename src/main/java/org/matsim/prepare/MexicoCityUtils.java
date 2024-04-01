package org.matsim.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.SimWrapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.matsim.application.ApplicationUtils.globFile;

/**
 * Scenario related utils class.
 */
@SuppressWarnings("ConstantName")
public final class MexicoCityUtils {

	public static final String CRS = "EPSG:4485";

	/**
	 * Scaling factor if all persons use car (~21.99% share).
	 */
	public static final double CAR_FACTOR = 4.55;

	public static final String HOME_X = "home_x";
	public static final String HOME_Y = "home_y";

	/**
	 * ENT = entidad / federal state.
	 * MUN = municipio
	 * LOC = localidad
	 * AGEB = area geoestadistica basica
	 * MZA = manzana
	 * DISTR = home district according to EOD2017 origin-destination survey districts
	 */
	public static final String ENT = "ent";
	public static final String MUN = "mun";
	public static final String LOC = "loc";
	public static final String AGEB = "ageb";
	public static final String MZA = "mza";
	public static final String DISTR = "distr";
	public static final String REGION_TYPE = "RegionType";

	public static final String BIKE_AVAIL = "bikeAvail";
	public static final String PT_ABO_AVAIL = "ptAboAvail";
	public static final String EMPLOYMENT = "employment";
	public static final String RESTRICTED_MOBILITY = "restricted_mobility";
	public static final String ECONOMIC_STATUS = "economic_status";
	public static final String HOUSEHOLD_SIZE = "household_size";
	public static final String TAXIBUS = "taxibus";
//	MXN / € 2017: https://www.exchange-rates.org/es/historial/eur-mxn-2017
//	-> avg of 2017: 1€=21.344 MXN
	public static final Double PESO_EURO = 21.344;

	public static final String ROAD_PRICING_AREA = "roadPricingAreaShp";

	//do not instantiate
	private MexicoCityUtils() {
	}

	public static boolean isDefined(Path p) {
		return p != null;
	}

	public static boolean isLinkUnassigned(Id<Link> link) {
		return link != null && Objects.equals(link.toString(), "unassigned");
	}

	/**
	 * Return home coordinate of a person.
	 */
	public static Coord getHomeCoord(Person p) {
		return new Coord((Double) p.getAttributes().getAttribute(HOME_X), (Double) p.getAttributes().getAttribute(HOME_Y));
	}

	/**
	 * Round to two digits.
	 */
	public static double roundNumber(double x) {
		return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
	}

	/**
	 * Round coordinates to sufficient precision.
	 */
	public static Coord roundCoord(Coord coord) {
		return new Coord(roundNumber(coord.getX()), roundNumber(coord.getY()));
	}

	public static void addDashboardToExistingRunOutput(Dashboard dashboard, Path runDir) throws IOException {
		SimWrapper sw = SimWrapper.create();

		sw.addDashboard(dashboard);

//		the added dashboard will overwrite an existing one, so the following workaround is done
//		this only generates the dashboard. If the dashboard includes analysis (like the standard dashboards), SimWrapper.run has to be executed additionally
		sw.generate(Path.of(runDir + "/dashboard"));

		String pattern = "*dashboard-*";
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
		String newFileName;

		try (Stream<Path> fileStream = Files.walk(runDir, 1)) {
			// Glob files in the directory matching the pattern
			List<Path> matchedFiles = fileStream
				.filter(Files::isRegularFile)
				.filter(path -> matcher.matches(path.getFileName()))
				.toList();

			int i = 0;
			for (Path p : matchedFiles) {
				int n = Integer.parseInt(p.getFileName().toString().substring(10, 11));
				if (n > i) {
					i = n;
				}
			}

			if (matchedFiles.isEmpty()) {
				newFileName = "dashboard-0.yaml";
			} else {
				newFileName = globFile(runDir, "*dashboard-" + i +"*").getFileName().toString().replace(String.valueOf(i), String.valueOf(i + 1));
			}

			Files.copy(Path.of(runDir + "/dashboard/dashboard-0.yaml"), Path.of(runDir + "/" + newFileName));

			try (Stream<Path> anotherStream = Files.walk(Path.of(runDir + "/dashboard"))){
				anotherStream
					.sorted((p1, p2) -> -p1.compareTo(p2))
					.forEach(path -> {
						try {
							Files.delete(path);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					});
			} catch (IOException f) {
				throw new RuntimeException(f);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}


}
