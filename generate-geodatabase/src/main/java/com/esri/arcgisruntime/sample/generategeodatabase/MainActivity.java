/* Copyright 2017 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.esri.arcgisruntime.sample.generategeodatabase;

import java.io.File;
import java.util.concurrent.ExecutionException;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.esri.arcgisruntime.concurrent.Job;
import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.data.Geodatabase;
import com.esri.arcgisruntime.data.GeodatabaseFeatureTable;
import com.esri.arcgisruntime.geometry.Envelope;
import com.esri.arcgisruntime.layers.ArcGISTiledLayer;
import com.esri.arcgisruntime.layers.FeatureLayer;
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.esri.arcgisruntime.symbology.SimpleLineSymbol;
import com.esri.arcgisruntime.tasks.geodatabase.GenerateGeodatabaseJob;
import com.esri.arcgisruntime.tasks.geodatabase.GenerateGeodatabaseParameters;
import com.esri.arcgisruntime.tasks.geodatabase.GeodatabaseSyncTask;

public class MainActivity extends AppCompatActivity {

  private final String TAG = MainActivity.class.getSimpleName();

  MapView mMapView;
  RelativeLayout mProgressLayout;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // define permission to request
    String[] reqPermission = new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE };
    int requestCode = 2;
    // For API level 23+ request permission at runtime
    if (ContextCompat.checkSelfPermission(MainActivity.this, reqPermission[0]) != PackageManager.PERMISSION_GRANTED) {
      // request permission
      ActivityCompat.requestPermissions(MainActivity.this, reqPermission, requestCode);
    }

    // use world street map tiled layer
    ArcGISTiledLayer tiledLayer = new ArcGISTiledLayer(getString(R.string.world_street_map));

    // create a map view and add a map
    mMapView = (MapView) findViewById(R.id.mapView);
    final ArcGISMap map = new ArcGISMap(new Basemap(tiledLayer));
    mMapView.setMap(map);

    // create a graphics overlay and symbol to mark the extent
    final GraphicsOverlay graphicsOverlay = new GraphicsOverlay();
    mMapView.getGraphicsOverlays().add(graphicsOverlay);
    final SimpleLineSymbol boundarySymbol = new SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, 0xFFFF0000, 5);

    // inflate button and progress layout
    final Button genGeodatabaseButton = (Button) findViewById(R.id.genGeodatabaseButton);
    mProgressLayout = (RelativeLayout) findViewById(R.id.progressLayout);
    final ProgressBar progressBar = (ProgressBar) findViewById(R.id.taskProgressBar);
    progressBar.setProgress(0);

    // create a geodatabase sync task
    String featureServiceURL = getString(R.string.wildfire_sync);
    final GeodatabaseSyncTask geodatabaseSyncTask = new GeodatabaseSyncTask(featureServiceURL);
    geodatabaseSyncTask.loadAsync();
    geodatabaseSyncTask.addDoneLoadingListener(new Runnable() {
      @Override public void run() {

        // generate the geodatabase on button click
        genGeodatabaseButton.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) {
            // show the progress layout
            mProgressLayout.setVisibility(View.VISIBLE);

            // clear any previous operational layers and graphics if button clicked more than once
            map.getOperationalLayers().clear();
            graphicsOverlay.getGraphics().clear();

            // show the extent used as a graphic
            Envelope extent = mMapView.getVisibleArea().getExtent();
            Graphic boundary = new Graphic(extent, boundarySymbol);
            graphicsOverlay.getGraphics().add(boundary);

            // create generate geodatabase parameters for the current extent
            final ListenableFuture<GenerateGeodatabaseParameters> defaultParameters = geodatabaseSyncTask
                .createDefaultGenerateGeodatabaseParametersAsync(extent);
            defaultParameters.addDoneListener(new Runnable() {
              @Override public void run() {
                try {
                  // set parameters
                  GenerateGeodatabaseParameters parameters = defaultParameters.get();
                  parameters.setReturnAttachments(false);

                  // temporary file for geodatabase
                  File file = new File(Environment.getExternalStorageDirectory(),
                      getString(R.string.config_data_sdcard_offline_dir));
                  if (!file.exists()) {
                    file.mkdirs();
                  }

                  // create and start the job
                  final GenerateGeodatabaseJob generateGeodatabaseJob = geodatabaseSyncTask
                      .generateGeodatabaseAsync(parameters,
                          Environment.getExternalStorageDirectory() + getString(R.string.config_data_sdcard_offline_dir)
                              + getString(R.string.file_name));
                  generateGeodatabaseJob.start();

                  // show progress
                  generateGeodatabaseJob.addProgressChangedListener(new Runnable() {
                    @Override public void run() {
                      progressBar.setProgress(generateGeodatabaseJob.getProgress());
                    }
                  });

                  // get geodatabase when done
                  generateGeodatabaseJob.addJobDoneListener(new Runnable() {
                    @Override public void run() {
                      mProgressLayout.setVisibility(View.INVISIBLE);
                      if (generateGeodatabaseJob.getStatus() == Job.Status.SUCCEEDED) {
                        final Geodatabase geodatabase = generateGeodatabaseJob.getResult();

                        //displayMessage("Geodatabase successfully generated", "Unregistering geodatabase since we're
                        // not syncing it here");

                        geodatabase.loadAsync();
                        geodatabase.addDoneLoadingListener(new Runnable() {
                          @Override public void run() {
                            if (geodatabase.getLoadStatus() == LoadStatus.LOADED) {
                              for (GeodatabaseFeatureTable geodatabaseFeatureTable : geodatabase
                                  .getGeodatabaseFeatureTables()) {
                                geodatabaseFeatureTable.loadAsync();
                                map.getOperationalLayers().add(new FeatureLayer(geodatabaseFeatureTable));
                              }
                            } else {
                              Log.e(TAG, "Error loading geodatabase: " + geodatabase.getLoadError().getMessage());
                            }
                          }
                        });
                        // unregister since we're not syncing
                        geodatabaseSyncTask.unregisterGeodatabaseAsync(geodatabase);
                      } else if (generateGeodatabaseJob.getError() != null) {
                        Log.e(TAG, "Error generating geodatabase: " + generateGeodatabaseJob.getError().getMessage());
                      } else {
                        Log.e(TAG, "Unknown Error generating geodatabase");
                      }
                    }
                  });
                } catch (InterruptedException | ExecutionException e) {
                  Log.e(TAG, "Error generating geodatabase parameters : " + e.getMessage());
                }
              }
            });

          }
        });
      }
    });
  }

  @Override
  protected void onPause() {
    super.onPause();
    mMapView.pause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    mMapView.resume();
  }
}
