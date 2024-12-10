// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import androidx.annotation.NonNull;
import io.flutter.embedding.engine.systemchannels.PlatformChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provides various utilities for camera. */
public final class CameraUtils {

  private CameraUtils() {}

  /**
   * Gets the {@link CameraManager} singleton.
   *
   * @param context The context to get the {@link CameraManager} singleton from.
   * @return The {@link CameraManager} singleton.
   */
  static CameraManager getCameraManager(Context context) {
    return (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
  }

  /**
   * Serializes the {@link PlatformChannel.DeviceOrientation} to a string value.
   *
   * @param orientation The orientation to serialize.
   * @return The serialized orientation.
   * @throws UnsupportedOperationException when the provided orientation not have a corresponding
   *     string value.
   */
  static String serializeDeviceOrientation(PlatformChannel.DeviceOrientation orientation) {
    if (orientation == null)
      throw new UnsupportedOperationException("Could not serialize null device orientation.");
    switch (orientation) {
      case PORTRAIT_UP:
        return "portraitUp";
      case PORTRAIT_DOWN:
        return "portraitDown";
      case LANDSCAPE_LEFT:
        return "landscapeLeft";
      case LANDSCAPE_RIGHT:
        return "landscapeRight";
      default:
        throw new UnsupportedOperationException(
            "Could not serialize device orientation: " + orientation.toString());
    }
  }

  /**
   * Deserializes a string value to its corresponding {@link PlatformChannel.DeviceOrientation}
   * value.
   *
   * @param orientation The string value to deserialize.
   * @return The deserialized orientation.
   * @throws UnsupportedOperationException when the provided string value does not have a
   *     corresponding {@link PlatformChannel.DeviceOrientation}.
   */
  static PlatformChannel.DeviceOrientation deserializeDeviceOrientation(String orientation) {
    if (orientation == null)
      throw new UnsupportedOperationException("Could not deserialize null device orientation.");
    switch (orientation) {
      case "portraitUp":
        return PlatformChannel.DeviceOrientation.PORTRAIT_UP;
      case "portraitDown":
        return PlatformChannel.DeviceOrientation.PORTRAIT_DOWN;
      case "landscapeLeft":
        return PlatformChannel.DeviceOrientation.LANDSCAPE_LEFT;
      case "landscapeRight":
        return PlatformChannel.DeviceOrientation.LANDSCAPE_RIGHT;
      default:
        throw new UnsupportedOperationException(
            "Could not deserialize device orientation: " + orientation);
    }
  }

  /**
   * Gets all the available cameras for the device.
   *
   * @param activity The current Android activity.
   * @return A map of all the available cameras, with their name as their key.
   * @throws CameraAccessException when the camera could not be accessed.
   */
  @NonNull
  public static List<Map<String, Object>> getAvailableCameras(@NonNull Activity activity)
          throws CameraAccessException {
    CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    if (cameraManager == null) {
      throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "Camera service not available");
    }

    String[] cameraIds = cameraManager.getCameraIdList();
    List<Map<String, Object>> cameras = new ArrayList<>();

    for (String cameraId : cameraIds) {
      Log.d("CameraUtils", "camera loop - current: " + cameraId);
      try {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

        // Initialize a map to hold camera details
        Map<String, Object> details = new HashMap<>();
        details.put("cameraId", cameraId);
        details.put("name", cameraId);


        // Get sensor orientation
        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensorOrientation != null) {
          details.put("sensorOrientation", sensorOrientation);
        }

        // Get lens facing information
        Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (lensFacing != null) {
          switch (lensFacing) {
            case CameraMetadata.LENS_FACING_FRONT:
              details.put("lensFacing", "front");
              break;
            case CameraMetadata.LENS_FACING_BACK:
              details.put("lensFacing", "back");
              break;
            case CameraMetadata.LENS_FACING_EXTERNAL:
              details.put("lensFacing", "external");
              break;
            default:
              details.put("lensFacing", "unknown");
              break;
          }
        }

        // Get available focal lengths to help identify camera type
        float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focalLengths != null && focalLengths.length > 0) {
          details.put("focalLength", focalLengths[0]);
        }

        // Optionally, get other characteristics as needed
        // For example, to identify the camera's field of view or other properties

        cameras.add(details);
      } catch (CameraAccessException e) {
        // Handle exception or log it
        e.printStackTrace();
      } catch (Exception e) {
        // Catch any other exceptions to prevent one bad camera from stopping the entire process
        e.printStackTrace();
      }
    }

    return cameras;
  }
}
