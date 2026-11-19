package xyz.zedler.patrick.doodle.util;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * v9 F22: Sun-Time Auto Mode — the NOAA solar calculation equations
 * (https://gml.noaa.gov/grad/solcalc/calcdetails.html), pure math, zero
 * network calls. Everything the app needs derives from the solar
 * elevation angle:
 *
 *   DAY   = the sun's geometric elevation is above the CIVIL TWILIGHT
 *           threshold (-6 degrees) at the selected city RIGHT NOW —
 *           day scenes render from actual dawn, night scenes from
 *           actual dusk, including the polar summer/winter extremes
 *           (the elevation test is total: above the circle the sun
 *           never crosses -6 degrees and it is simply always day).
 *
 * The sunrise/sunset/civil-dawn/dusk event times come from the same
 * equations solved for elevation = -6 degrees at the requested date.
 * All internal math is UTC; the caller renders with the DEVICE clock.
 */
public final class Sh1Solar {

  /** civil twilight: the sun is 6 degrees below the horizon */
  public static final double CIVIL_TWILIGHT_DEG = -6.0;

  private Sh1Solar() {}

  /* ---- NOAA solar position (calc details, equations 1-12) ----------- */

  private static double deg(double rad) {
    return Math.toDegrees(rad);
  }

  private static double rad(double deg) {
    return Math.toRadians(deg);
  }

  private static double mod360(double x) {
    x = x % 360.0;
    return x < 0 ? x + 360.0 : x;
  }

  /**
   * The sun's geometric elevation in degrees at the given UTC instant.
   * lat/lon in decimal degrees (north/east positive).
   */
  public static double solarElevationDeg(double lat, double lon, long utcMillis) {
    // Julian day from the Java epoch (milliseconds since 1970-01-01 UTC):
    // JD = unixDays + 2440587.5; the fraction is the time of day.
    double jd = utcMillis / 86400000.0 + 2440587.5;
    double t = (jd - 2451545.0) / 36525.0;   // Julian century (eq. 1)

    double l0 = mod360(280.46646 + t * (36000.76983 + t * 0.0003032)); // (2)
    double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);           // (3)
    double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);      // (4)

    double mRad = rad(m);
    double c = Math.sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t))
        + Math.sin(2 * mRad) * (0.019993 - 0.000101 * t)
        + Math.sin(3 * mRad) * 0.000289;                                 // (5)

    double trueLong = l0 + c;
    double omega = 125.04 - 1934.136 * t;
    double lambda = trueLong - 0.00569 - 0.00478 * Math.sin(rad(omega)); // (6)

    double seconds = 21.448 - t * (46.815 + t * (0.00059 - t * 0.001813));
    double e0 = 23.0 + (26.0 + seconds / 60.0) / 60.0;                  // (7)
    double eps = e0 + 0.00256 * Math.cos(rad(omega));                   // (8)

    double decl = deg(Math.asin(Math.sin(rad(eps)) * Math.sin(rad(lambda)))); // (9)

    // Equation of time, minutes (eq. 10; y = tan^2(eps/2))
    double y = Math.tan(rad(eps / 2.0)) * Math.tan(rad(eps / 2.0));
    double eqTime = 4.0 * deg(
        y * Math.sin(2 * rad(l0))
            - 2 * e * Math.sin(mRad)
            + 4 * e * y * Math.sin(mRad) * Math.cos(2 * rad(l0))
            - 0.5 * y * y * Math.sin(4 * rad(l0))
            - 1.25 * e * e * Math.sin(2 * mRad));

    // True solar time -> hour angle. utcHours is UTC decimal hours.
    // v9.1 Fix 1: eqTime is in MINUTES (NOAA eq. 10) — 4 minutes = 1 degree,
    // so its degree equivalent is eqTime / 4. Adding the raw minutes to this
    // degrees sum overstated the equation of time by 4x (up to ~12.3 deg =
    // ~49 min of hour angle in November), flipping isCivilDay to day that
    // much EARLY while eventsUtc (minutes-consistent) displayed the correct
    // dawn — exactly the reported "dawn 06:50, switches at 06:00" bug.
    double utcHours = (utcMillis / 3600000.0) % 24.0;
    double trueSolarTime = mod360(
        utcHours * 15.0 + lon + eqTime / 4.0);            // minutes->deg: /4
    double ha = trueSolarTime / 15.0 * 15.0 - 180.0;      // hour angle, deg

    // Elevation (eq. 12 with the hour-angle form)
    double latRad = rad(lat);
    double declRad = rad(decl);
    double haRad = rad(ha);
    return deg(Math.asin(
        Math.sin(latRad) * Math.sin(declRad)
            + Math.cos(latRad) * Math.cos(declRad) * Math.cos(haRad)));
  }

  /**
   * True when the sun is above the civil twilight threshold at the city
   * RIGHT NOW (the state Sun-Time Auto Mode renders from).
   */
  public static boolean isCivilDay(double lat, double lon, long utcMillis) {
    return solarElevationDeg(lat, lon, utcMillis) > CIVIL_TWILIGHT_DEG;
  }

  /* ---- event times (sunrise/sunset/civil dawn/dusk) ----------------- */

  /**
   * Solve the elevation equation for the given sun angle at the given
   * date (a Calendar in ANY timezone; only its Y/M/D matter).
   * Returns {dawnUtcMillis, duskUtcMillis} — the hour angle crossings
   * around solar noon — or null when the sun never crosses the angle
   * that day (polar day/night).
   */
  public static long[] eventsUtc(double lat, double lon,
      int year, int month, int dayOfMonth, double sunAngleDeg) {
    // Julian century at ~local solar noon of that date (12:00 UTC is a
    // fine reference for the day's declination/eqTime)
    Calendar noonUtc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    noonUtc.clear();
    noonUtc.set(year, month, dayOfMonth, 12, 0, 0);
    double jd = noonUtc.getTimeInMillis() / 86400000.0 + 2440587.5;
    double t = (jd - 2451545.0) / 36525.0;

    double l0 = mod360(280.46646 + t * (36000.76983 + t * 0.0003032));
    double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
    double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
    double mRad = rad(m);
    double c = Math.sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t))
        + Math.sin(2 * mRad) * (0.019993 - 0.000101 * t)
        + Math.sin(3 * mRad) * 0.000289;
    double trueLong = l0 + c;
    double omega = 125.04 - 1934.136 * t;
    double lambda = trueLong - 0.00569 - 0.00478 * Math.sin(rad(omega));
    double seconds = 21.448 - t * (46.815 + t * (0.00059 - t * 0.001813));
    double e0 = 23.0 + (26.0 + seconds / 60.0) / 60.0;
    double eps = e0 + 0.00256 * Math.cos(rad(omega));
    double decl = deg(Math.asin(Math.sin(rad(eps)) * Math.sin(rad(lambda))));
    double y = Math.tan(rad(eps / 2.0)) * Math.tan(rad(eps / 2.0));
    double eqTimeMin = 4.0 * deg(
        y * Math.sin(2 * rad(l0))
            - 2 * e * Math.sin(mRad)
            + 4 * e * y * Math.sin(mRad) * Math.cos(2 * rad(l0))
            - 0.5 * y * y * Math.sin(4 * rad(l0))
            - 1.25 * e * e * Math.sin(2 * mRad));

    // hour angle for the requested sun angle
    double latRad = rad(lat);
    double declRad = rad(decl);
    double cosHa = (Math.sin(rad(sunAngleDeg)) - Math.sin(latRad) * Math.sin(declRad))
        / (Math.cos(latRad) * Math.cos(declRad));
    if (cosHa < -1.0 || cosHa > 1.0) {
      return null;   // never reaches the angle (polar day/night)
    }
    double ha = deg(Math.acos(cosHa));    // degrees, setting side

    // solar noon in UTC minutes: 720 - lon*4 - eqTime
    double noonUtcMin = 720.0 - lon * 4.0 - eqTimeMin;
    double dawnUtcMin = noonUtcMin - ha * 4.0;
    double duskUtcMin = noonUtcMin + ha * 4.0;

    long base = noonUtc.getTimeInMillis();
    return new long[]{
        base + (long) Math.round((dawnUtcMin - 720.0) * 60000.0),
        base + (long) Math.round((duskUtcMin - 720.0) * 60000.0)
    };
  }

  /** civil dawn/dusk for today at the city, in UTC (null in polar extremes) */
  public static long[] civilTwilightUtc(double lat, double lon) {
    Calendar cal = Calendar.getInstance();
    return eventsUtc(lat, lon, cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
        CIVIL_TWILIGHT_DEG);
  }
}
