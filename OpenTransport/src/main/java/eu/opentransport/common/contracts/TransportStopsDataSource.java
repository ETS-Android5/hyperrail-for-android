/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package eu.opentransport.common.contracts;

import android.location.Location;
import android.support.annotation.Nullable;

import eu.opentransport.common.exceptions.StopLocationNotResolvedException;
import eu.opentransport.common.models.Station;

/**
 * A station provider, returning stations from irail/stationscsv or a datasource with similar fields.
 * See http://docs.irail.be
 */
public interface TransportStopsDataSource {

    /**
     * Get all station names, localized.
     *
     * @param Stations The list of stations for which a name should be retrieved.
     * @return An array of localized station names.
     */
    String[] getStationNames(Station[] Stations);

    /**
     * Get a station by its UIC ID (International Railway Station ID)
     *
     * @param id a 7 digit ID string
     * @return The station object, null if not found
     */
    @Nullable
    Station getStationByUIC( String id) throws StopLocationNotResolvedException;

    /**
     * Get a station by its UIC ID (International Railway Station ID)
     *
     * @param id a 7 digit ID string
     * @return The station object, null if not found
     */

    Station getStationByUIC( String id, boolean suppressErrors) throws StopLocationNotResolvedException;

    /**
     * Get a station by its Hafas ID (This format is similar to the UIC format, but longer and can include bus stops
     * Example custom country code for bus stops: 02
     *
     * @param id a 9 digit ID String
     * @return The station object.
     */

    Station getStationByHID( String id) throws StopLocationNotResolvedException;

    /**
     * Get a station by its Hafas ID (This format is similar to the UIC format, but longer and can include bus stops
     * Example custom country code for bus stops: 02
     *
     * @param id a 9 digit ID String
     * @return The station object.
     */

    Station getStationByHID( String id, boolean suppressErrors) throws StopLocationNotResolvedException;

    /**
     * Get a station by its ID
     *
     * @param id an ID string, in BE.NMBS.XXXXXXXX or Hafas ID format
     * @return The station object.
     */
    @Deprecated

    Station getStationByIrailApiId( String id) throws StopLocationNotResolvedException;

    /**
     * Get a station by its URI
     *
     * @param uri a uri string
     * @return The station object.
     */

    Station getStationByUri( String uri) throws StopLocationNotResolvedException;

    /**
     * Get a station by its URI
     *
     * @param uri a uri string
     * @return The station object.
     */

    Station getStationByUri( String uri, boolean suppressErrors) throws StopLocationNotResolvedException;

    /**
     * Get a station by its name.
     *
     * @param name The name of the station to find
     * @return The station object.
     */
    @Nullable
    Station getStationByExactName( String name);

    /**
     * Get stations by their name (or a part thereof), ordered by their size, measured in average train stops per day.
     *
     * @param name The (beginning of) the station name.
     * @return An array of station objects ordered by their size, measured in average train stops per day.
     */
    @Nullable
    Station[] getStationsByNameOrderBySize( String name);

    /**
     * Get stations by their name (or a part thereof), ordered by their distance from a given location
     *
     * @param name     The (beginning of) the station name.
     * @param location The location from which distances should be measured
     * @return An array of station objects ordered by their distance from the given location
     */
    @Nullable
    Station[] getStationsByNameOrderByLocation( String name,  Location location);

    /**
     * Get all stations ordered by their distance from a given location
     *
     * @param location The location from which distances should be measured
     * @return An array of all station objects ordered by their distance from the given location
     */

    Station[] getStationsOrderByLocation(Location location);

    /**
     * Get all stations ordered by their size, measured in average train stops per day.
     *
     * @return An array of station objects ordered by their size, measured in average train stops per day.
     */

    Station[] getStationsOrderBySize();

    /**
     * Get the n closest stations to a location, ordered by their size, measured in average train stops per day.
     *
     * @param limit    The number of stations to return
     * @param location The location from which distance should be measured
     * @return An array of station objects ordered by their size, measured in average train stops per day.
     */

    Station[] getStationsOrderByLocationAndSize(Location location, int limit);
}
