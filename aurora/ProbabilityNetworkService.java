package com.example.aurora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Vibrator;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

//Service for accessing network-reliant functions and sensor data.

public class ProbabilityNetworkService extends Service implements SensorEventListener {

    private final IBinder binder = new NetworkBinder();
    private ConnectivityManager cm;
    private String url = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json";
    private JSONObject object;
    private RequestQueue queue;

    private SensorManager sensorManager;
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    private int latestProbability = -1;
    private int magField = 0;
    private Double azimuthValue = 0.0;

    //The class that defines the binder to be returned when the service is bound by an activity.
    public class NetworkBinder extends Binder {
        ProbabilityNetworkService getService() {
            return ProbabilityNetworkService.this;
        }
    }



    //returns a binder when an activity requests binding to the service.
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }


    //The service's handler, allowing message and thread handling to occur.
    private final class ServiceHandler extends Handler {
        Context context;

        public ServiceHandler(Looper looper, Context context) {
            super(looper);
            this.context = context;
        }

        @Override
        public void handleMessage(Message msg) {
        }
    }

    //onCreate method sets up connectivity/sensor managers, sensor listeners and sensor objects.
    @Override
    public void onCreate() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        queue = Volley.newRequestQueue(this);
        requestProbabilityArray();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }
        Sensor magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magneticField != null) {
            sensorManager.registerListener(this, magneticField,
                    SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }

        createNotificationChannel();

    }

    //The on start command is given responsibility to both handle the app's widget and periodic mobile alerts.
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {


        RemoteViews view = new RemoteViews(getPackageName(), R.layout.appwidget);
        if (checkConnected()) {
            view.setTextViewText(R.id.widgetText, "Latest local aurora probability: "+latestProbability+"%");
        } else {
            view.setTextViewText(R.id.widgetText, "Disconnected, reconnect for aurora probability");
        }

        ComponentName theWidget = new ComponentName(this, AuroraWidgetProvider.class);
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        manager.updateAppWidget(theWidget, view);
        //The alert is triggered if the probability is more than 10%
        if(latestProbability > 10){
            alert(latestProbability);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    //Method for handling sensor accuracy changes. In this case, nothing is needed to be done, but implementing this method is a requirement. 
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    //callback method responsible for handling when the sensor values update
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading,
                    0, accelerometerReading.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading,
                    0, magnetometerReading.length);
            magField = (int) event.values[0];
        }
        updateOrientationAngles();
    }

    //Creates a notification channel with LED functionality for probability notifications
    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "chan";
            String description = "channel";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel("CHAN", name, importance);
            channel.setDescription(description);
            channel.shouldShowLights();

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    //Turns sensor readings from the positional sensors into an azimuth value that can be used to orient the map according to angle to north
    private void updateOrientationAngles() {
        SensorManager.getRotationMatrix(rotationMatrix, null,
                accelerometerReading, magnetometerReading);


        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        azimuthValue = orientationAngles[0] / Math.PI * 180;
        if (azimuthValue < 0) {
            azimuthValue = 180 + (azimuthValue + 180);
        }
    }


    //returns the processed azimuth value
    public double getAzimuth(){
        return azimuthValue;
    }

    //checks whether the phone is connected to the network. Used to prevent network requests without network access
    @RequiresApi(api = Build.VERSION_CODES.M)
    public boolean checkConnected() {
        if (cm.getActiveNetwork() != null) {
            return true;
        }
        return false;

    }


    //requests a JSON object from the NOAA website containing an array of probabilities of an aurora at all longitudes and latitudes on earth
    private void requestProbabilityArray() {

        object = new JSONObject();
        try {
            object.accumulate("token", "VMWJBjJqqYcMtvEvYcxtovtoADErmeul");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest
                (Request.Method.GET, url, object, new Response.Listener<JSONObject>() {

                    @Override
                    public void onResponse(JSONObject response) {
                        object = response;
                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {


                    }
                });
        queue.add(jsonObjectRequest);
    }

    //Given a longitude and latitude, this method finds the probability of aurora at that location in the retrieved JSON array
    public int findProbability(int longitude, int latitude) {
        if (object != null) {
            try {
                if (longitude < 0)
                    longitude = 180 - longitude;
                latestProbability =  (int) ((JSONArray) object.getJSONArray("coordinates").get(longitude * 181 + 90 + latitude)).get(2);
                return latestProbability;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        return -1;
    }

    //returns a reading of the magnetic field sensor
    public int magReading(){
        return magField;
    }

    //Method for sending a vibration LED alert
    private void alert(int probability) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "CHAN")
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentTitle("Aurora alert")
                .setContentText("Aurora probability at: " + probability + "%")
                .setLights(0xff00ff00, 300, 100)
                .setDefaults(Notification.DEFAULT_VIBRATE | Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(2, builder.build());
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        vibrator.vibrate(500);

    }


}

