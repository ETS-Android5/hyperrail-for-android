/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package be.hyperrail.opentransportdata.be.irail;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.NonNull;
import android.support.annotation.RawRes;

import com.google.firebase.perf.metrics.AddTrace;

import org.joda.time.DateTime;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Scanner;

import be.hyperrail.opentransportdata.be.R;
import be.hyperrail.opentransportdata.common.webdb.WebDbDataDefinition;
import be.hyperrail.opentransportdata.logging.OpenTransportLog;
import be.hyperrail.opentransportdata.util.StringUtils;

import static be.hyperrail.opentransportdata.be.irail.IrailStationsDataContract.SQL_CREATE_INDEX_ID;
import static be.hyperrail.opentransportdata.be.irail.IrailStationsDataContract.SQL_CREATE_INDEX_NAME;
import static be.hyperrail.opentransportdata.be.irail.IrailStationsDataContract.SQL_CREATE_TABLE_STATIONS;
import static be.hyperrail.opentransportdata.be.irail.IrailStationsDataContract.SQL_DELETE_TABLE_STATIONS;
import static be.hyperrail.opentransportdata.be.irail.IrailStationsDataContract.StationsDataColumns;
import static java.util.logging.Level.INFO;

/**
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * (c) Bert Marcelis 2018
 */
class IrailStopsWebDbDataDefinition implements WebDbDataDefinition {
    private static final String LOGTAG = "IrailWebDb";
    private final Context mContext;

    @Override
    public boolean updateOnlyOnWifi() {
        return true;
    }

    @Override
    @RawRes
    public int getEmbeddedDataResourceId() {
        return R.raw.stations;
    }

    @Override
    public String getOnlineDataURL() {
        return "https://raw.githubusercontent.com/iRail/stations/master/stations.csv";
    }

    @Override
    public String getDatabaseName() {
        return "irail-stations.db";
    }

    @Override
    public DateTime getLastModifiedLocalDate() {
        return new DateTime(2019, 4, 1, 0, 0);
    }

    @Override
    public DateTime getLastModifiedOnlineDate() {
        // Github doesn't send a proper last modified header. Instead we use the last saturday (can be today)
        return getSaturdayBeforeToday();
    }

    @NonNull
    private DateTime getSaturdayBeforeToday() {
        DateTime now = DateTime.now();
        // On a saturday (6) this will be now (+0), on sunday it will be saturday (+1), on monday it will be +2, ...
        // This way we update every saturday
        return now.minusDays(now.dayOfWeek().get() + 1 % 7);
    }

    private String getOnlineData() {
        URL url;
        try {
            url = new URL(getOnlineDataURL());
        } catch (MalformedURLException e) {
            return "";
        }
        HttpURLConnection httpCon;
        try {
            httpCon = (HttpURLConnection) url.openConnection();
            return httpCon.getResponseMessage();
        } catch (IOException e) {
            return "";
        }
    }

    private InputStream getLocalData() {
        return mContext.getResources().openRawResource(getEmbeddedDataResourceId());
    }

    IrailStopsWebDbDataDefinition(Context context) {
        mContext = context;
    }

    /**
     * Create the database.
     *
     * @param db Handle in which the database should be created.
     */
    @AddTrace(name = "StationsDb.onCreate")
    public void onCreate(SQLiteDatabase db, boolean useOnlineData) {
        OpenTransportLog.log(INFO.intValue(), LOGTAG, "Creating stations database");

        db.execSQL(SQL_CREATE_TABLE_STATIONS);
        db.execSQL(SQL_CREATE_INDEX_ID);
        db.execSQL(SQL_CREATE_INDEX_NAME);

        OpenTransportLog.log(INFO.intValue(), LOGTAG, "Filling stations database");
        fillStations(db, useOnlineData);
        OpenTransportLog.log(INFO.intValue(), LOGTAG, "Stations table ready");
        OpenTransportLog.log(INFO.intValue(), LOGTAG, "Stations facilities table ready");
        OpenTransportLog.log(INFO.intValue(), LOGTAG, "Stations database ready");
    }

    /**
     * Fill the database with data from the embedded CSV file (raw resource).
     *
     * @param db The database to fillStations
     */
    @AddTrace(name = "StationsDb.fillStations")
    private void fillStations(SQLiteDatabase db, boolean useOnlineData) {
        if (useOnlineData) {
            db.beginTransaction();
            try (Scanner lines = new Scanner(getOnlineData())) {
                importData(db, lines);
                db.setTransactionSuccessful();
            } catch (Exception e) {
                OpenTransportLog.log("Failed to fill stations db with online data! Reverting to local.");
                OpenTransportLog.logException(e);
                // If we can't get online data, start a new transaction to load offline data instead.
                // This fallback should guarantee that everything keeps working should an online update wreck the schema.
                db.endTransaction();
                db.beginTransaction();
                try (Scanner lines = new Scanner(getLocalData())) {
                    importData(db, lines);
                    db.setTransactionSuccessful();
                }
            } finally {
                db.endTransaction();
            }
        } else {
            // Load offline data
            db.beginTransaction();
            try (Scanner lines = new Scanner(getLocalData())) {
                importData(db, lines);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    private void importData(SQLiteDatabase db, Scanner lines) {
        lines.useDelimiter("\n");

        while (lines.hasNext()) {
            String line = lines.next();
            if (line.startsWith("URI,name")) {
                // Header line
                continue;
            }

            try (Scanner fields = new Scanner(line)) {
                importRow(db, fields);
            }
        }
    }

    private void importRow(SQLiteDatabase db, Scanner fields) {
        fields.useDelimiter(",");

        ContentValues values = new ContentValues();

        String id = fields.next();

        // By default, the CSV contains ids in iRail URI format,
        // reformat them to 9-digit HAFAS IDs, as HAFAS IDs are an extension upon UIC station codes.
        if (id.startsWith("http")) {
            id = id.replace("http://irail.be/stations/NMBS/", "");
        }

        // Store ID as XXXXXXXX
        values.put(StationsDataColumns._ID, id);
        // Replace special characters (for search purposes)
        values.put(
                StationsDataColumns.COLUMN_NAME_NAME,
                StringUtils.cleanAccents(fields.next())
        );
        values.put(
                StationsDataColumns.COLUMN_NAME_ALTERNATIVE_FR,
                StringUtils.cleanAccents(fields.next())
        );
        values.put(
                StationsDataColumns.COLUMN_NAME_ALTERNATIVE_NL,
                StringUtils.cleanAccents(fields.next())
        );
        values.put(
                StationsDataColumns.COLUMN_NAME_ALTERNATIVE_DE,
                StringUtils.cleanAccents(fields.next())
        );
        values.put(
                StationsDataColumns.COLUMN_NAME_ALTERNATIVE_EN,
                StringUtils.cleanAccents(fields.next())
        );
        values.put(StationsDataColumns.COLUMN_NAME_COUNTRY_CODE, fields.next());

        String field = fields.next();
        if (field != null && !field.isEmpty()) {

            values.put(
                    StationsDataColumns.COLUMN_NAME_LONGITUDE,
                    Double.parseDouble(field)
            );

        } else {
            values.put(StationsDataColumns.COLUMN_NAME_LONGITUDE, 0);
        }

        field = fields.next();
        if (field != null && !field.isEmpty()) {
            values.put(
                    StationsDataColumns.COLUMN_NAME_LATITUDE,
                    Double.parseDouble(field)
            );

        } else {
            values.put(StationsDataColumns.COLUMN_NAME_LATITUDE, 0);
        }

        field = fields.next();
        if (field != null && !field.isEmpty()) {
            try {
                values.put(
                        StationsDataColumns.COLUMN_NAME_AVG_STOP_TIMES,
                        Double.parseDouble(field)
                );
            } catch (NumberFormatException e) {
                values.put(StationsDataColumns.COLUMN_NAME_AVG_STOP_TIMES, 0);
            }
        } else {
            values.put(StationsDataColumns.COLUMN_NAME_AVG_STOP_TIMES, 0);
        }

        field = fields.next();
        if (field != null && !field.isEmpty()) {
            try {
                values.put(
                        StationsDataColumns.COLUMN_NAME_OFFICIAL_TRANSFER_TIME,
                        Double.parseDouble(field)
                );
            } catch (NumberFormatException e) {
                values.put(StationsDataColumns.COLUMN_NAME_OFFICIAL_TRANSFER_TIME, 0);
            }
        } else {
            values.put(StationsDataColumns.COLUMN_NAME_OFFICIAL_TRANSFER_TIME, 0);
        }
        // Insert row
        db.insert(StationsDataColumns.TABLE_NAME, null, values);
    }


    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion, boolean useOnlineData) {
        // This database is only a cache from a local file, so just recreate it
        db.execSQL(SQL_DELETE_TABLE_STATIONS);
        onCreate(db, useOnlineData);
    }


}
