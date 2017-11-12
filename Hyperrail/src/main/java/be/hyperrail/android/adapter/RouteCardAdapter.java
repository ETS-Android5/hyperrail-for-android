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

package be.hyperrail.android.adapter;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.util.ArrayList;

import be.hyperrail.android.LiveboardActivity;
import be.hyperrail.android.R;
import be.hyperrail.android.TrainActivity;
import be.hyperrail.android.infiniteScrolling.InfiniteScrollingAdapter;
import be.hyperrail.android.infiniteScrolling.InfiniteScrollingDataSource;
import be.hyperrail.android.irail.db.Station;
import be.hyperrail.android.irail.implementation.Route;
import be.hyperrail.android.irail.implementation.RouteResult;
import be.hyperrail.android.irail.implementation.TrainStub;
import be.hyperrail.android.irail.implementation.Transfer;
import be.hyperrail.android.util.DurationFormatter;

/**
 * Recyclerview adapter to show results of route searches
 */
public class RouteCardAdapter extends InfiniteScrollingAdapter<Route> {

    private Route[] routes;
    private final Context context;

    private Object[] displayList;

    protected final static int VIEW_TYPE_DATE = 1;

    public RouteCardAdapter(Context context, RecyclerView recyclerView, InfiniteScrollingDataSource listener) {
        super(context, recyclerView, listener);
        this.context = context;
    }

    public void updateRoutes(RouteResult routeResult) {
        if (routeResult == null || routeResult.getRoutes() == null || routeResult.getRoutes().length < 1) {
            this.routes = null;
            this.displayList = null;
            return;
        }

        this.routes = routeResult.getRoutes();

        ArrayList<Integer> daySeparatorPositions = new ArrayList<>();

        if (routes != null && routes.length > 0) {
            // Default day to compare to is today
            DateTime lastday = DateTime.now().withTimeAtStartOfDay();

            if (!routes[0].getDepartureTime().withTimeAtStartOfDay().isEqual(lastday)) {
                // If the first stop is not today, add date separators everywhere
                lastday = routes[0].getDepartureTime().withTimeAtStartOfDay().minusDays(1);
            } else if (!routeResult.getSearchTime().withTimeAtStartOfDay().equals(routes[0].getDepartureTime().withTimeAtStartOfDay())) {
                // If the search results differ from the date searched, everything after the date searched should have separators
                lastday = routeResult.getSearchTime().withTimeAtStartOfDay();
            }
            for (int i = 0; i < routes.length; i++) {
                Route route = routes[i];

                if (route.getDepartureTime().withTimeAtStartOfDay().isAfter(lastday)) {
                    lastday = route.getDepartureTime().withTimeAtStartOfDay();
                    daySeparatorPositions.add(i);
                }
            }

            this.displayList = new Object[daySeparatorPositions.size() + routes.length];

            // Convert to array + take previous separators into account for position of next separator
            int dayPosition = 0;
            int routePosition = 0;
            int resultPosition = 0;

            while (resultPosition < daySeparatorPositions.size() + routes.length) {
                // Keep in mind that position shifts with the number of already placed date separators
                if (dayPosition < daySeparatorPositions.size() && resultPosition == daySeparatorPositions.get(dayPosition) + dayPosition) {
                    RouteCardAdapter.DateSeparator separator = new RouteCardAdapter.DateSeparator();
                    // The date of this separator is the one of the next departure
                    separator.separatorDate = routes[routePosition].getDepartureTime();
                    this.displayList[resultPosition] = separator;

                    dayPosition++;
                } else {
                    this.displayList[resultPosition] = routes[routePosition];
                    routePosition++;
                }

                resultPosition++;
            }
        } else {
            displayList = null;
        }

        this.notifyDataSetChanged();
    }

    @Override
    protected int onGetItemViewType(int position) {
        if (displayList[position] instanceof DateSeparator) {
            return VIEW_TYPE_DATE;
        }
        return VIEW_TYPE_ITEM;
    }

    @Override
    public RecyclerView.ViewHolder onCreateItemViewHolder(ViewGroup parent, int viewType) {
        View itemView;

        if (viewType == VIEW_TYPE_DATE) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.listview_separator_date, parent, false);
            return new DateSeparatorViewHolder(itemView);
        }

        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean("use_card_layout", false)) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.listview_route, parent, false);
        } else {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.cardview_route, parent, false);
        }

        return new RouteViewHolder(itemView);
    }

    @Override
    public void onBindItemViewHolder(RecyclerView.ViewHolder genericHolder, int position) {

        if (genericHolder instanceof DateSeparatorViewHolder) {
            final DateSeparatorViewHolder holder = (DateSeparatorViewHolder) genericHolder;
            DateTimeFormatter df = DateTimeFormat.forPattern("EEE dd MMMMMMMM yyyy");
            holder.vDateText.setText(df.print(((RouteCardAdapter.DateSeparator) displayList[position]).separatorDate));
            return;
        }

        final RouteViewHolder holder = (RouteViewHolder) genericHolder;
        final Route route = (Route) displayList[position];

        DateTimeFormatter hhmm = DateTimeFormat.forPattern("HH:mm");

        holder.vDepartureTime.setText(hhmm.print(route.getDepartureTime()));
        if (route.getDepartureDelay().getStandardSeconds() > 0) {
            holder.vDepartureDelay.setText(context.getString(R.string.delay, route.getDepartureDelay().getStandardMinutes()));
        } else {
            holder.vDepartureDelay.setText("");
        }

        holder.vArrivalTime.setText(hhmm.print(route.getArrivalTime()));
        if (route.getArrivalDelay().getStandardSeconds() > 0) {
            holder.vArrivalDelay.setText(context.getString(R.string.delay, route.getArrivalDelay().getStandardMinutes()));
        } else {
            holder.vArrivalDelay.setText("");
        }

        holder.vDirection.setText(route.getOrigin().getDepartingTrain().getDirection().getLocalizedName());

        Duration routeWithDelays = route.getDurationIncludingDelays();
        Duration routeWithoutDelays = route.getDuration();

        if (routeWithDelays.equals(routeWithoutDelays)) {
            holder.vDuration.setTextColor(ContextCompat.getColor(context, R.color.colorMuted));
            holder.vDurationIcon.setColorFilter(ContextCompat.getColor(context, R.color.colorMuted));
        } else if (routeWithDelays.isLongerThan(routeWithoutDelays)) {
            holder.vDuration.setTextColor(ContextCompat.getColor(context, R.color.colorDelay));
            holder.vDurationIcon.setColorFilter(ContextCompat.getColor(context, R.color.colorDelay));
        } else {
            holder.vDuration.setTextColor(ContextCompat.getColor(context, R.color.colorFaster));
            holder.vDurationIcon.setColorFilter(ContextCompat.getColor(context, R.color.colorFaster));
        }

        holder.vDuration.setText(DurationFormatter.formatDuration(route.getDurationIncludingDelays().toPeriod()));

        holder.vTrainCount.setText(String.valueOf(route.getTrains().length));

        holder.vPlatform.setText(route.getDeparturePlatform());

        if (route.getOrigin().isDepartureCanceled()) {
            holder.vPlatform.setText("");
            holder.vPlatformContainer.setBackground(ContextCompat.getDrawable(context, R.drawable.platform_train_canceled));
            holder.vStatusText.setText(R.string.status_cancelled);
            holder.vStatusContainer.setVisibility(View.VISIBLE);
        } else if (route.isPartiallyCanceled()){
            holder.vPlatformContainer.setBackground(ContextCompat.getDrawable(context, R.drawable.platform_train));
            holder.vStatusText.setText(R.string.status_partially_cancelled);
            holder.vStatusContainer.setVisibility(View.VISIBLE);
        } else {
            holder.vStatusContainer.setVisibility(View.GONE);
            holder.vPlatformContainer.setBackground(ContextCompat.getDrawable(context, R.drawable.platform_train));
        }

        if (!route.isDeparturePlatformNormal()) {
            Drawable drawable = holder.vPlatformContainer.getBackground();
            drawable.mutate();
            drawable.setColorFilter(ContextCompat.getColor(context, R.color.colorDelay), PorterDuff.Mode.SRC_ATOP);
        }

        if (route.getAlerts() != null && route.getAlerts().length > 0) {
            holder.vAlertsText.setVisibility(View.VISIBLE);

            StringBuilder text = new StringBuilder();
            int n = route.getAlerts().length;
            for (int i = 0; i < n; i++) {
                text.append(route.getAlerts()[i].getHeader());
                if (i < n - 1) {
                    text.append("\n");
                }
            }

            holder.vAlertsText.setText(text.toString());
        } else {
            holder.vAlertsText.setVisibility(View.GONE);
        }

        RouteDetailCardAdapter adapter = new RouteDetailCardAdapter(context, route, true);

        // Launch intents to view details / click through
        adapter.setOnItemClickListener(new OnRecyclerItemClickListener<Object>() {
            @Override
            public void onRecyclerItemClick(RecyclerView.Adapter sender, Object object) {
                Intent i = null;
                if (object instanceof Bundle) {
                    i = TrainActivity.createIntent(context,
                            (TrainStub) ((Bundle) object).getSerializable("train"),
                            (Station) ((Bundle) object).getSerializable("from"),
                            (Station) ((Bundle) object).getSerializable("to"),
                            (DateTime) ((Bundle) object).getSerializable("date"));

                } else if (object instanceof Transfer) {
                    i = LiveboardActivity.createIntent(context, ((Transfer) object).getStation());
                }
                context.startActivity(i);
            }
        });
        holder.vRecyclerView.setAdapter(adapter);
        holder.vRecyclerView.setItemAnimator(new DefaultItemAnimator());
        holder.vRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        holder.vRecyclerView.setNestedScrollingEnabled(false);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mOnClickListener != null) {
                    mOnClickListener.onRecyclerItemClick(RouteCardAdapter.this, route);
                }

                if (holder.vDetailContainer.getVisibility() == View.GONE) {
                    holder.vDetailContainer.setVisibility(View.VISIBLE);
                } else {
                    holder.vDetailContainer.setVisibility(View.GONE);
                }
            }
        });

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mOnLongClickListener != null) {
                    mOnLongClickListener.onRecyclerItemLongClick(RouteCardAdapter.this, route);
                }
                return false;
            }
        });
    }

    @Override
    public int getListItemCount() {
        if (routes == null | displayList == null) {
            return 0;
        }
        return displayList.length;
    }

    private class RouteViewHolder extends RecyclerView.ViewHolder {

        protected final TextView vDepartureTime;
        protected final TextView vDepartureDelay;
        protected final TextView vArrivalTime;
        protected final TextView vArrivalDelay;
        protected final TextView vDirection;
        protected final TextView vDuration;
        protected final ImageView vDurationIcon;
        protected final TextView vTrainCount;
        protected final TextView vPlatform;
        protected final LinearLayout vPlatformContainer;
        protected final RecyclerView vRecyclerView;
        protected final LinearLayout vHeaderContainer;
        protected final LinearLayout vDetailContainer;
        protected final TextView vStatusText;
        protected final LinearLayout vStatusContainer;
        protected final TextView vAlertsText;

        RouteViewHolder(View view) {
            super(view);

            vDepartureTime = view.findViewById(R.id.text_departure_time);
            vDepartureDelay = view.findViewById(R.id.text_departure_delay);

            vArrivalTime = view.findViewById(R.id.text_arrival_time);
            vArrivalDelay = view.findViewById(R.id.text_arrival_delay);

            vDirection = view.findViewById(R.id.text_destination);
            vDuration = view.findViewById(R.id.text_duration);
            vDurationIcon = view.findViewById(R.id.image_duration);
            vTrainCount = view.findViewById(R.id.text_train_count);

            vPlatform = view.findViewById(R.id.text_platform);
            vPlatformContainer = view.findViewById(R.id.layout_platform_container);

            vRecyclerView = view.findViewById(R.id.recyclerview_primary);
            vHeaderContainer = view.findViewById(R.id.cardview_collapsed);
            vDetailContainer = view.findViewById(R.id.cardview_expanded);

            vStatusContainer = view.findViewById(R.id.layout_train_status_container);
            vStatusText = view.findViewById(R.id.text_train_status);
            vAlertsText = view.findViewById(R.id.alert_message);
        }

    }

    private class DateSeparator {

        DateTime separatorDate;
    }
}

