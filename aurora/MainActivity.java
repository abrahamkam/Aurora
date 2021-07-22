package com.example.aurora;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.OnSuccessListener;

import java.util.Arrays;
//Read readme for more information about the program. Main function is to show your local aurora probability and the magnetic field sensor reading in one dimension.
public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private boolean requestingLocationUpdates = false;
    private LocationCallback locationCallback;
    private FusedLocationProviderClient fusedLocationClient;
    private GoogleMap map;
    private Location mCurrentLocation;
    private LocationRequest locationRequest;

    private TextView localProbabilityDisplay;
    private TextView localMagneticFieldDisplay;

    private ProbabilityNetworkService probabilityNetworkService;
    boolean bound = false;
    private Double azimuthValue = 0.0;

    //On app creation, a permission check is performed. If it fails, attempts are made to receive permission until it is granted. Once granted, a setup method is called
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
        } else {
            setup();
        }
    }

    //Method that requests permission to access fine location, for periodic location retrieval using the map.
    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    //Callback method that initiates setup once permission has been granted, otherwise does nothing
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (!Arrays.asList(grantResults).contains(PackageManager.PERMISSION_DENIED)) {
                setup();
            }
        }
    }


    //Sets up the map, view objects, and provides an initial location. Also creates and binds to the network service
    private void setup() {


        localProbabilityDisplay = findViewById(R.id.probability);
        localMagneticFieldDisplay = findViewById(R.id.magneticField);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);



        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location location) {
                        if (location != null) {
                            map.moveCamera(CameraUpdateFactory.newLatLng(new LatLng(location.getLatitude(), location.getLongitude())));
                            mCurrentLocation = location;

                        } else {

                        }
                    }
                });

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        locationCallback = new LocationCallback() {
            @RequiresApi(api = Build.VERSION_CODES.M)
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    mCurrentLocation = location;
                    if (probabilityNetworkService != null) {
                        updateLocalFields(location);
                        azimuthValue = probabilityNetworkService.getAzimuth();

                    }
                }

            }
        };

        createLocationRequest();
        Intent probabilityNetworkServiceIntent = new Intent(this, ProbabilityNetworkService.class);
        bindService(probabilityNetworkServiceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    //Updates the textviews that display sensor values and the aurora probability textview
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void updateLocalFields(Location location) {

        if (probabilityNetworkService.checkConnected()) {
            int probability = probabilityNetworkService.findProbability((int) Math.round(location.getLongitude()), (int) Math.round(location.getLatitude()));
            int magField = probabilityNetworkService.magReading();
            //if the service returns -1, it has failed to retrieve the reading and a standby message is displayed. If not, the reading is displayed.
            if (probability != -1) {
                localProbabilityDisplay.setText("Local aurora probability: " + probability + "%");
            } else {
                localProbabilityDisplay.setText("Loading local probability...");
            }
            if (magField != -1) {
                localMagneticFieldDisplay.setText("Local magnetic field strength north: " + magField + "μ");
            } else {
                localMagneticFieldDisplay.setText("Loading local magnetic field strength...");
            }
        }
    }

    //Method for starting the automatic user location requests
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest,
                    locationCallback,
                    Looper.getMainLooper());
            requestingLocationUpdates = true;
            return;
        }

    }

    //callback for the reorient button, which refocuses back to the last retrieved location and reorients the view according to the latest compass read
    public void reorient(View view) {
        CameraPosition position = new CameraPosition(new LatLng(mCurrentLocation.getLatitude(), mCurrentLocation.getLongitude()), map.getCameraPosition().zoom, map.getCameraPosition().tilt, azimuthValue.floatValue());
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position));
    }

    //method that returns a serviceconnection object linking to the network service
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            ProbabilityNetworkService.NetworkBinder binder = (ProbabilityNetworkService.NetworkBinder) service;
            probabilityNetworkService = binder.getService();
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            bound = false;
        }
    };

    //Sets up a location request and begins map location updates, requesting the location on a set time interval
    protected void createLocationRequest() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(50000);
        locationRequest.setFastestInterval(5000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        this.locationRequest = locationRequest;
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);
        startLocationUpdates();
    }

    //Method called when the app is resumed after a break. Calls a setup method if the correct permissions are available, otherwise asks for permission
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            resumeSetup();
        }
    }

    //restarts location updates and gets the current location if permissions are granted.
    private void resumeSetup() {


        if (requestingLocationUpdates) {
            startLocationUpdates();
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                        @Override
                        public void onSuccess(Location location) {
                            //moves the camera to the current location
                            if (location != null) {
                                map.moveCamera(CameraUpdateFactory.newLatLng(new LatLng(location.getLatitude(), location.getLongitude())));
                                mCurrentLocation = location;
                            } else {

                            }
                        }
                    });
        }
        if (requestingLocationUpdates) {
            startLocationUpdates();
        }
    }

    //Callback function for when the map service reports that the map is ready. Assigns the map to a global variable and enables zoom controls.
    @Override
    public void onMapReady(GoogleMap googleMap) {

        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(true);
        //if location permissions are granted, enables UI element for moving camera to your current location
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        map.setMyLocationEnabled(true);


    }

}