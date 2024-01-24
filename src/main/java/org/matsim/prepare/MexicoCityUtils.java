package org.matsim.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Scenario related utils class.
 */
@SuppressWarnings("ConstantName")
public final class MexicoCityUtils {

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

	//do not instantiate
	private MexicoCityUtils() {
	}

	public boolean isDefined(Path p) {
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


}
