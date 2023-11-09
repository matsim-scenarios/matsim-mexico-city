package org.matsim.prepare.population;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Scenario related utils class.
 */
@SuppressWarnings("ConstantName")
public final class MexicoCityUtils {

	static final String HOME_X = "home_x";
	static final String HOME_Y = "home_y";

	/**
	 * ENT = entidad / federal state.
	 * MUN = municipio
	 * LOC = localidad
	 * AGEB = area beoestadistica basica
	 * MZA = manzana
	 */
	static final String ENT = "ent";
	static final String MUN = "mun";
	static final String LOC = "loc";
	static final String AGEB = "ageb";
	static final String MZA = "mza";

	static final String BIKE_AVAIL = "bikeAvail";
	static final String PT_ABO_AVAIL = "ptAboAvail";
	static final String EMPLOYMENT = "employment";
	static final String RESTRICTED_MOBILITY = "restricted_mobility";
	static final String ECONOMIC_STATUS = "economic_status";
	static final String HOUSEHOLD_SIZE = "household_size";

	//do nopt instantiate
	private MexicoCityUtils() {
	}

	static boolean isLinkUnassigned(Id<Link> link) {
		return link != null && Objects.equals(link.toString(), "unassigned");
	}

	/**
	 * Return home coordinate of a person.
	 */
	static Coord getHomeCoord(Person p) {
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
