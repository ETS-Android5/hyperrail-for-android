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

package eu.opentransport.irail;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.RetryPolicy;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.crashlytics.android.Crashlytics;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import eu.opentransport.BuildConfig;
import eu.opentransport.OpenTransportApi;
import eu.opentransport.common.contracts.QueryTimeDefinition;
import eu.opentransport.common.contracts.TransportDataSource;
import eu.opentransport.common.exceptions.StopLocationNotResolvedException;
import eu.opentransport.common.models.Disturbance;
import eu.opentransport.common.models.LiveboardType;
import eu.opentransport.common.models.Route;
import eu.opentransport.common.models.VehicleStop;
import eu.opentransport.common.requests.ExtendLiveboardRequest;
import eu.opentransport.common.requests.ExtendRoutePlanningRequest;
import eu.opentransport.common.requests.ActualDisturbancesRequest;
import eu.opentransport.common.requests.LiveboardRequest;
import eu.opentransport.common.requests.OccupancyPostRequest;
import eu.opentransport.common.requests.RouteRefreshRequest;
import eu.opentransport.common.requests.RoutePlanningRequest;
import eu.opentransport.common.requests.VehicleRequest;
import eu.opentransport.common.requests.VehicleStopRequest;

import static java.util.logging.Level.WARNING;

/**
 * Synchronous API for api.irail.be
 *
 * @inheritDoc
 */
public class IrailApi implements TransportDataSource {

    private static final String LOGTAG = "iRailApi";
    private final RequestQueue requestQueue;
    private static final String UA = "OpenTransport for Android - " + BuildConfig.VERSION_NAME;
    private final RetryPolicy requestPolicy;

    private final Context context;
    private final IrailApiParser parser;
    private final ConnectivityManager mConnectivityManager;
    private final int TAG_IRAIL_API_GET = 0;

    public IrailApi(Context context) {
        this.context = context;
        this.parser = new IrailApiParser((IrailStationsDataProvider) OpenTransportApi.getStationsProviderInstance());
        this.requestQueue = Volley.newRequestQueue(context);
        this.requestPolicy = new DefaultRetryPolicy(
                750,
                3,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        );
        mConnectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);


    }

    private boolean isInternetAvailable() {
        NetworkInfo activeNetwork = mConnectivityManager.getActiveNetworkInfo();
        return activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();
    }


    @Override
    public void getRoute(RouteRefreshRequest... requests) {
        for (RouteRefreshRequest request : requests
                ) {
            RoutePlanningRequest routesRequest = new RoutePlanningRequest(
                    request.getOrigin(), request.getDestination(), request.getTimeDefinition(),
                    request.getSearchTime()
            );

            // Create a new routerequest. A successful response will be iterated to find a matching route. An unsuccessful query will cause the original error handler to be called.
            routesRequest.setCallback((data, tag) -> {
                for (Route r : data.getRoutes()) {
                    if (r.getTransfers()[0].getDepartureSemanticId() != null && r.getTransfers()[0].getDepartureSemanticId().equals(
                            request.getDepartureSemanticId())) {
                        request.notifySuccessListeners(r);
                    }
                }
            }, (e, tag) -> request.notifyErrorListeners(e), request.getTag());

            getRoutes(routesRequest);
        }
    }

    @Override
    public void getRoutePlanning(RoutePlanningRequest... requests) {
        for (RoutePlanningRequest request :
                requests) {
            getRoutes(request);
        }
    }

    @Override
    public void extendRoutePlanning(ExtendRoutePlanningRequest... requests) {
        for (ExtendRoutePlanningRequest request :
                requests) {
            IrailRouteAppendHelper helper = new IrailRouteAppendHelper();
            helper.extendRoutesRequest(request);
        }
    }

    public void getRoutes(RoutePlanningRequest request) {

        // https://api.irail.be/connections/?to=Halle&from=Brussels-south&date={dmy}&time=2359&timeSel=arrive or depart&format=json

        DateTimeFormatter dateformat = DateTimeFormat.forPattern("ddMMyy");
        DateTimeFormatter timeformat = DateTimeFormat.forPattern("HHmm");

        String locale = PreferenceManager.getDefaultSharedPreferences(context).getString(
                "pref_stations_language", "");
        if (locale.isEmpty()) {
            // Only get locale when needed
            locale = Locale.getDefault().getISO3Language();
        }

        String url = "https://api.irail.be/connections/?format=json"
                + "&to=" + request.getDestination().getHafasId()
                + "&from=" + request.getOrigin().getHafasId()
                + "&date=" + dateformat.print(request.getSearchTime())
                + "&time=" + timeformat.print(request.getSearchTime().withZone(DateTimeZone.forID("Europe/Brussels")))
                + "&lang=" + locale.substring(0, 2);

        if (request.getTimeDefinition() == QueryTimeDefinition.DEPART_AT) {
            url += "&timeSel=depart";
        } else {
            url += "&timeSel=arrive";
        }

        Response.Listener<JSONObject> successListener = response -> {
            IrailRoutesList routeResult;
            try {
                routeResult = parser.parseRouteResult(
                        response, request.getOrigin(), request.getDestination(),
                        request.getSearchTime(), request.getTimeDefinition()
                );
            } catch (JSONException e) {
                Crashlytics.log(
                        WARNING.intValue(), "Failed to parse routes", e.getMessage());
                Crashlytics.logException(e);
                request.notifyErrorListeners(e);
                return;
            }
            request.notifySuccessListeners(routeResult);
        };

        Response.ErrorListener errorListener = e -> {
            Crashlytics.log(
                    WARNING.intValue(), "Failed to get routes", e.getMessage());
            request.notifyErrorListeners(e);
        };

        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, successListener, errorListener) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-agent", UA);
                return headers;
            }
        };
        jsObjRequest.setRetryPolicy(requestPolicy);
        jsObjRequest.setTag(TAG_IRAIL_API_GET);

        tryOnlineOrServerCache(jsObjRequest, successListener, errorListener);
    }

    @Override
    public void getLiveboard(LiveboardRequest... requests) {
        for (LiveboardRequest request : requests) {
            if (request.getTimeDefinition() == QueryTimeDefinition.DEPART_AT) {
                getLiveboardAfter(request);
            } else {
                getLiveboardBefore(request);
            }
        }
    }

    @Override
    public void extendLiveboard(ExtendLiveboardRequest... requests) {
        for (ExtendLiveboardRequest request :
                requests) {
            IrailLiveboardAppendHelper helper = new IrailLiveboardAppendHelper();
            helper.extendLiveboard(request);
        }
    }

    private void getLiveboardBefore(LiveboardRequest request) {
        LiveboardRequest actualRequest = request.withSearchTime(
                request.getSearchTime().minusHours(1));

        actualRequest.setCallback((data, tag) -> {

            if (!(data instanceof IrailLiveboard)) {
                throw new IllegalArgumentException("IrailApi should only handle Irail specific models");
            }

            List<VehicleStop> stops = new ArrayList<>();
            for (VehicleStop s : data.getStops()) {
                if (s.getDepartureTime().isBefore(actualRequest.getSearchTime())) {
                    stops.add(s);
                }
            }
            request.notifySuccessListeners(
                    new IrailLiveboard(data, stops.toArray(new IrailVehicleStop[]{}),
                                       data.getSearchTime(), data.getLiveboardType(), QueryTimeDefinition.ARRIVE_AT
                    ));
        }, (e, tag) -> request.notifyErrorListeners(e), actualRequest.getTag());
        getLiveboardAfter(request);
    }

    private void getLiveboardAfter(LiveboardRequest request) {
        // https://api.irail.be/liveboard/?station=Halle&fast=true

        // suppress errors, this formatting is for an API call
        DateTimeFormatter dateformat = DateTimeFormat.forPattern("ddMMyy");
        DateTimeFormatter timeformat = DateTimeFormat.forPattern("HHmm");

        String url = "https://api.irail.be/liveboard/?format=json"
                + "&id=" + request.getStation().getHafasId()
                + "&date=" + dateformat.print(request.getSearchTime())
                + "&time=" + timeformat.print(request.getSearchTime().withZone(DateTimeZone.forID("Europe/Brussels")))
                + "&arrdep=" + ((request.getType() == LiveboardType.DEPARTURES) ? "dep" : "arr");

        Response.Listener<JSONObject> successListener = response -> {
            IrailLiveboard result;
            try {
                result = parser.parseLiveboard(response, request.getSearchTime(), request.getType(), request.getTimeDefinition());
            } catch (JSONException | StopLocationNotResolvedException e) {
                Crashlytics.log(WARNING.intValue(), "Failed to parse liveboard", e.getMessage());
                Crashlytics.logException(e);
                request.notifyErrorListeners(e);
                return;
            }

            request.notifySuccessListeners(result);
        };

        Response.ErrorListener errorListener = e -> {
            Log.w(LOGTAG, "Tried loading liveboard from " + url + " failed with error " + e);
            Crashlytics.log(
                    WARNING.intValue(), "Failed to get liveboard", e.getMessage());
            request.notifyErrorListeners(e);
        };

        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, successListener, errorListener) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-agent", UA);
                return headers;
            }
        };

        jsObjRequest.setRetryPolicy(requestPolicy);
        jsObjRequest.setTag(TAG_IRAIL_API_GET);

        tryOnlineOrServerCache(jsObjRequest, successListener, errorListener);
    }

    @Override
    public void getVehicleJourney(VehicleRequest... requests) {
        for (VehicleRequest request :
                requests) {
            getVehicle(request);
        }
    }

    public void getVehicle(VehicleRequest request) {
        DateTimeFormatter dateTimeformat = DateTimeFormat.forPattern("ddMMyy");

        String url = "https://api.irail.be/vehicle/?format=json"
                + "&id=" + request.getVehicleId() + "&date=" + dateTimeformat.print(
                request.getSearchTime());

        Response.Listener<JSONObject> successListener = response -> {
            IrailVehicle result;
            try {
                result = parser.parseTrain(response, request.getSearchTime());
            } catch (JSONException | StopLocationNotResolvedException e) {
                Crashlytics.log(
                        WARNING.intValue(), "Failed to parse vehicle", e.getMessage());
                Crashlytics.logException(e);
                request.notifyErrorListeners(e);
                return;
            }
            request.notifySuccessListeners(result);
        };

        Response.ErrorListener errorListener = e -> {
            Crashlytics.log(
                    WARNING.intValue(), "Failed to get vehicle", e.getMessage());
            request.notifyErrorListeners(e);
        };
        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, successListener, errorListener)

        {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-agent", UA);
                return headers;
            }
        };

        jsObjRequest.setRetryPolicy(requestPolicy);
        jsObjRequest.setTag(TAG_IRAIL_API_GET);

        tryOnlineOrServerCache(jsObjRequest, successListener, errorListener);
    }

    @Override
    public void getStop(VehicleStopRequest... requests) {
        for (VehicleStopRequest request :
                requests) {
            getStop(request);
        }
    }

    private void getStop(VehicleStopRequest request) {
        DateTime time = request.getStop().getDepartureTime();
        if (time == null) {
            time = request.getStop().getArrivalTime();
        }
        VehicleRequest vehicleRequest = new VehicleRequest(request.getStop().getVehicle().getId(), time);
        vehicleRequest.setCallback((data, tag) -> {
            for (IrailVehicleStop stop :
                    data.getStops()) {
                if (stop.getDepartureUri().equals(request.getStop().getDepartureUri())) {
                    request.notifySuccessListeners(stop);
                    return;
                }
            }
        }, request.getOnErrorListener(), null);
        getVehicle(vehicleRequest);
    }

    @Override
    public void getActualDisturbances(ActualDisturbancesRequest... requests) {
        for (ActualDisturbancesRequest request :
                requests) {
            getDisturbances(request);
        }
    }

    public void getDisturbances(ActualDisturbancesRequest request) {

        String locale = PreferenceManager.getDefaultSharedPreferences(context).getString(
                "pref_stations_language", "");
        if (locale.isEmpty()) {
            // Only get locale when needed
            locale = Locale.getDefault().getISO3Language();
        }

        String url = "https://api.irail.be/disturbances/?format=json&lang=" + locale.substring(
                0, 2);


        Response.Listener<JSONObject> successListener = response -> {
            Disturbance[] result;
            try {
                result = parser.parseDisturbances(response);
            } catch (JSONException e) {
                Crashlytics.log(WARNING.intValue(), "Failed to parse disturbances", e.getMessage());
                Crashlytics.logException(e);
                request.notifyErrorListeners(e);
                return;
            }
            request.notifySuccessListeners(result);
        };

        Response.ErrorListener errorListener = e -> {
            Crashlytics.log(WARNING.intValue(), "Failed to get disturbances", e.getMessage());
            request.notifyErrorListeners(e);
        };

        JsonObjectRequest jsObjRequest = new JsonObjectRequest
                (Request.Method.GET, url, null, successListener, errorListener) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-agent", UA);
                return headers;
            }
        };

        jsObjRequest.setRetryPolicy(requestPolicy);
        jsObjRequest.setTag(TAG_IRAIL_API_GET);
        tryOnlineOrServerCache(jsObjRequest, successListener, errorListener);
    }

    /**
     * If internet is available, make a request. Otherwise, check the cache
     *
     * @param jsObjRequest    The request which should be made to the server
     * @param successListener The listener for successful responses, which will be used by the cache
     * @param errorListener   The listener for unsuccessful responses
     */
    private void tryOnlineOrServerCache(JsonObjectRequest jsObjRequest, Response.Listener<JSONObject> successListener, Response.ErrorListener errorListener) {
        if (isInternetAvailable()) {
            requestQueue.add(jsObjRequest);
        } else {
            if (requestQueue.getCache().get(jsObjRequest.getCacheKey()) != null) {
                try {
                    JSONObject cache;
                    cache = new JSONObject(new String(requestQueue.getCache().get(jsObjRequest.getCacheKey()).data));
                    successListener.onResponse(cache);
                } catch (JSONException e) {
                    Crashlytics.log(
                            WARNING.intValue(), "Failed to get result from cache", e.getMessage());
                    errorListener.onErrorResponse(new NoConnectionError());
                }

            } else {
                errorListener.onErrorResponse(new NoConnectionError());
            }
        }
    }

    @Override
    public void postOccupancy(OccupancyPostRequest... requests) {
        for (OccupancyPostRequest request :
                requests) {
            postOccupancy(request);
        }
    }

    public void postOccupancy(OccupancyPostRequest request) {

        String url = "https://api.irail.be/feedback/occupancy.php";

        try {
            JSONObject payload = new JSONObject();

            payload.put("connection", request.getDepartureSemanticId());
            payload.put("from", request.getStationSemanticId());
            payload.put("date", DateTimeFormat.forPattern("YYYYMMdd").print(request.getDate()));
            payload.put("vehicle", request.getVehicleSemanticId());
            payload.put(
                    "occupancy",
                    "http://api.irail.be/terms/" + request.getOccupancy().name().toLowerCase()
            );

            Log.d(LOGTAG, "Posting feedback: " + url + " : " + payload);

            PostOccupancyTask t = new PostOccupancyTask(url, request);
            t.execute(payload.toString());
        } catch (Exception e) {
            request.notifyErrorListeners(e);
        }
    }

    @Override
    public void abortAllQueries() {
        this.requestQueue.cancelAll(TAG_IRAIL_API_GET);
    }

    /**
     * Make a synchronous POST request with a JSON body.
     *
     * @param uri  The URI to make the request to
     * @param json The request body
     * @return The return text from the server
     */

    private static String postJsonRequest(String uri, String json) {
        HttpURLConnection urlConnection;
        String result;
        try {
            //Connect
            urlConnection = (HttpURLConnection) ((new URL(uri).openConnection()));
            urlConnection.setDoOutput(true);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestMethod("POST");
            urlConnection.connect();

            //Write
            OutputStream outputStream = urlConnection.getOutputStream();
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(outputStream, "UTF-8"));
            writer.write(json);
            writer.close();
            outputStream.close();

            //Read
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

            String line;
            StringBuilder sb = new StringBuilder();

            while ((line = bufferedReader.readLine()) != null) {
                sb.append(line);
            }

            bufferedReader.close();
            result = sb.toString();

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return result;
    }

    private static class PostOccupancyTask extends AsyncTask<String, Void, String> {

        private final String url;
        private final OccupancyPostRequest request;

        public PostOccupancyTask(String url, OccupancyPostRequest request) {
            this.url = url;
            this.request = request;
        }

        @Override
        protected String doInBackground(String... payload) {
            return postJsonRequest(this.url, payload[0]);
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);

            if (result != null) {
                request.notifySuccessListeners(true);
            } else {
                // TODO: better exception handling
                request.notifyErrorListeners(new Exception("Failed to submit occupancy data"));
            }
        }
    }

}
