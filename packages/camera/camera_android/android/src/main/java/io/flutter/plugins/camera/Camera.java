// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.CamcorderProfile;
import android.media.EncoderProfiles;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import io.flutter.BuildConfig;
import io.flutter.embedding.engine.systemchannels.PlatformChannel;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugins.camera.features.CameraFeature;
import io.flutter.plugins.camera.features.CameraFeatureFactory;
import io.flutter.plugins.camera.features.CameraFeatures;
import io.flutter.plugins.camera.features.Point;
import io.flutter.plugins.camera.features.autofocus.AutoFocusFeature;
import io.flutter.plugins.camera.features.autofocus.FocusMode;
import io.flutter.plugins.camera.features.exposurelock.ExposureLockFeature;
import io.flutter.plugins.camera.features.exposurelock.ExposureMode;
import io.flutter.plugins.camera.features.exposureoffset.ExposureOffsetFeature;
import io.flutter.plugins.camera.features.exposurepoint.ExposurePointFeature;
import io.flutter.plugins.camera.features.flash.FlashFeature;
import io.flutter.plugins.camera.features.flash.FlashMode;
import io.flutter.plugins.camera.features.focuspoint.FocusPointFeature;
import io.flutter.plugins.camera.features.fpsrange.FpsRangeFeature;
import io.flutter.plugins.camera.features.resolution.ResolutionFeature;
import io.flutter.plugins.camera.features.resolution.ResolutionPreset;
import io.flutter.plugins.camera.features.sensororientation.DeviceOrientationManager;
import io.flutter.plugins.camera.features.zoomlevel.ZoomLevelFeature;
import io.flutter.plugins.camera.media.ImageStreamReader;
import io.flutter.plugins.camera.media.MediaRecorderBuilder;
import io.flutter.plugins.camera.types.CameraCaptureProperties;
import io.flutter.plugins.camera.types.CaptureTimeoutsWrapper;
import io.flutter.view.TextureRegistry;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

@FunctionalInterface
interface ErrorCallback {
  void onError(String errorCode, String errorMessage);
}

class Camera
    implements CameraCaptureCallback.CameraCaptureStateListener,
        ImageReader.OnImageAvailableListener {
  private static final String TAG = "Camera";

  private static final HashMap<String, Integer> supportedImageFormats;

  // Current supported outputs.
  static {
    supportedImageFormats = new HashMap<>();
    supportedImageFormats.put("yuv420", ImageFormat.YUV_420_888);
    supportedImageFormats.put("jpeg", ImageFormat.JPEG);
    supportedImageFormats.put("nv21", ImageFormat.NV21);
  }

  /**
   * Holds all of the camera features/settings and will be used to update the request builder when
   * one changes.
   */
  CameraFeatures cameraFeatures;

  private String imageFormatGroup;

  /**
   * Takes an input/output surface and orients the recording correctly. This is needed because
   * switching cameras while recording causes the wrong orientation.
   */
  @VisibleForTesting VideoRenderer videoRenderer;

  /**
   * Whether or not the camera aligns with the initial way the camera was facing if the camera was
   * flipped.
   */
  @VisibleForTesting int initialCameraFacing;

  @VisibleForTesting final TextureRegistry.SurfaceProducer surfaceProducer;
  private final VideoCaptureSettings videoCaptureSettings;
  private final Context applicationContext;
  final DartMessenger dartMessenger;
  private CameraProperties cameraProperties;
  private final CameraFeatureFactory cameraFeatureFactory;
  private final Activity activity;
  /** A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture. */
  private final CameraCaptureCallback cameraCaptureCallback;
  /** A {@link Handler} for running tasks in the background. */
  Handler backgroundHandler;

  /** An additional thread for running tasks that shouldn't block the UI. */
  private HandlerThread backgroundHandlerThread;

  CameraDeviceWrapper cameraDevice;
  CameraCaptureSession captureSession;
  @VisibleForTesting ImageReader pictureImageReader;
  ImageStreamReader imageStreamReader;
  /** {@link CaptureRequest.Builder} for the camera preview */
  CaptureRequest.Builder previewRequestBuilder;

  @VisibleForTesting MediaRecorder mediaRecorder;
  /** True when recording video. */
  boolean recordingVideo;

  /**
   * The frame rate a recording in a constrained high-speed session runs at, or null when the app
   * asked for nothing above the regular rates or the camera has no such session at the
   * recording's size. See {@link RecordingRate}.
   */
  @VisibleForTesting @Nullable Integer highSpeedFps;

  /**
   * The frame rate the media recorder is given for a regular recording, or null to leave it to
   * the recording profile. It is the rate the app asked for unless that is above anything a
   * regular session records.
   */
  @Nullable private Integer regularRecordingFps;

  /** True while the recording in progress runs in a constrained high-speed session. */
  @VisibleForTesting boolean highSpeedRecording;

  /** True once a high-speed session has failed to start on this camera. It is not tried again. */
  private boolean highSpeedFailed;

  /**
   * How long a high-speed recording waits for the exposure to settle before it starts regardless.
   * Settling took about half a second on a Pixel 9 Pro Fold.
   */
  @VisibleForTesting static final long HIGH_SPEED_SETTLE_MS = 1000;

  /**
   * The widest zoom a high-speed recording is made at. On a camera made of several lenses a zoom
   * under 1x is its wide lens, and a high-speed session stays on the main one (see {@link
   * #recordsAtHighSpeed}).
   */
  @VisibleForTesting static final float HIGH_SPEED_WIDEST_ZOOM = 1.0f;

  /**
   * What starts the recorder of a high-speed recording, held until the exposure has settled; null
   * at every other time. See {@link #onHighSpeedExposure}.
   */
  @Nullable private Runnable highSpeedStart;

  /**
   * The app's call to start a high-speed recording, answered when the recorder starts and not
   * before, so the app times the take from its first frame.
   */
  @Nullable private Result pendingStartResult;

  /**
   * A high-speed session's repeating callback: the usual one, which also watches the exposure
   * until the recorder has started.
   */
  private final CameraCaptureSession.CaptureCallback highSpeedCaptureCallback =
      new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull CaptureResult partialResult) {
          cameraCaptureCallback.onCaptureProgressed(session, request, partialResult);
        }

        @Override
        public void onCaptureCompleted(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull TotalCaptureResult result) {
          cameraCaptureCallback.onCaptureCompleted(session, request, result);
          if (highSpeedStart != null) {
            onHighSpeedExposure(result.get(CaptureResult.CONTROL_AE_STATE));
          }
        }
      };
  /** True when the preview is paused. */
  @VisibleForTesting boolean pausedPreview;

  private File captureFile;

  /** Holds the current capture timeouts */
  private CaptureTimeoutsWrapper captureTimeouts;
  /** Holds the last known capture properties */
  private CameraCaptureProperties captureProps;

  MethodChannel.Result flutterResult;

  /** A CameraDeviceWrapper implementation that forwards calls to a CameraDevice. */
  private class DefaultCameraDeviceWrapper implements CameraDeviceWrapper {
    private final CameraDevice cameraDevice;

    DefaultCameraDeviceWrapper(CameraDevice cameraDevice) {
      this.cameraDevice = cameraDevice;
    }

    @NonNull
    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
        throws CameraAccessException {
      return cameraDevice.createCaptureRequest(templateType);
    }

    @TargetApi(VERSION_CODES.P)
    @Override
    public void createCaptureSession(SessionConfiguration config) throws CameraAccessException {
      cameraDevice.createCaptureSession(config);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void createCaptureSession(
        @NonNull List<Surface> outputs,
        @NonNull CameraCaptureSession.StateCallback callback,
        @Nullable Handler handler)
        throws CameraAccessException {
      cameraDevice.createCaptureSession(outputs, callback, backgroundHandler);
    }

    @Override
    public void close() {
      cameraDevice.close();
    }
  }

  public static class VideoCaptureSettings {
    @NonNull public final ResolutionPreset resolutionPreset;
    public final boolean enableAudio;
    @Nullable public final Integer fps;
    @Nullable public final Integer videoBitrate;
    @Nullable public final Integer audioBitrate;

    public VideoCaptureSettings(
        @NonNull ResolutionPreset resolutionPreset,
        boolean enableAudio,
        @Nullable Integer fps,
        @Nullable Integer videoBitrate,
        @Nullable Integer audioBitrate) {
      this.resolutionPreset = resolutionPreset;
      this.enableAudio = enableAudio;
      this.fps = fps;
      this.videoBitrate = videoBitrate;
      this.audioBitrate = audioBitrate;
    }

    public VideoCaptureSettings(@NonNull ResolutionPreset resolutionPreset, boolean enableAudio) {
      this(resolutionPreset, enableAudio, null, null, null);
    }
  }

  public Camera(
      final Activity activity,
      final TextureRegistry.SurfaceProducer surfaceProducer,
      final CameraFeatureFactory cameraFeatureFactory,
      final DartMessenger dartMessenger,
      final CameraProperties cameraProperties,
      final VideoCaptureSettings videoCaptureSettings) {
    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }
    this.activity = activity;
    this.surfaceProducer = surfaceProducer;
    this.dartMessenger = dartMessenger;
    this.applicationContext = activity.getApplicationContext();
    this.cameraProperties = cameraProperties;
    this.cameraFeatureFactory = cameraFeatureFactory;
    this.videoCaptureSettings = videoCaptureSettings;
    this.cameraFeatures =
        CameraFeatures.init(
            cameraFeatureFactory,
            cameraProperties,
            activity,
            dartMessenger,
            videoCaptureSettings.resolutionPreset);

    Integer recordingFps = null;

    if (videoCaptureSettings.fps != null && videoCaptureSettings.fps.intValue() > 0) {
      // A rate above the regular ones records in a high-speed session where the camera has one,
      // and at its highest regular rate where it has not. The preview runs at the regular rate
      // either way.
      final Size captureSize = cameraFeatures.getResolution().getCaptureSize();
      final RecordingRate rate =
          RecordingRate.choose(
              videoCaptureSettings.fps,
              cameraProperties.getControlAutoExposureAvailableTargetFpsRanges(),
              captureSize == null || !SdkCapabilityChecker.supportsSessionConfiguration()
                  ? null
                  : cameraProperties.getHighSpeedVideoFpsRangesFor(captureSize));
      recordingFps = rate.regularFps;
      regularRecordingFps = rate.regularFps;
      highSpeedFps = rate.highSpeedFps;
      Log.i(
          TAG,
          "Asked for "
              + videoCaptureSettings.fps
              + " frames a second: regular sessions run at "
              + rate.regularFps
              + (rate.highSpeedFps == null
                  ? ", no high-speed session"
                  : ", a high-speed session records at " + rate.highSpeedFps));
    } else {
      if (SdkCapabilityChecker.supportsEncoderProfiles()) {
        EncoderProfiles encoderProfiles = getRecordingProfile();
        if (encoderProfiles != null && encoderProfiles.getVideoProfiles().size() > 0) {
          recordingFps = encoderProfiles.getVideoProfiles().get(0).getFrameRate();
        }
      } else {
        CamcorderProfile camcorderProfile = getRecordingProfileLegacy();
        recordingFps = null != camcorderProfile ? camcorderProfile.videoFrameRate : null;
      }
    }

    if (recordingFps != null && recordingFps.intValue() > 0) {
      final FpsRangeFeature fpsRange = new FpsRangeFeature(cameraProperties);
      fpsRange.setValue(new Range<Integer>(recordingFps, recordingFps));
      this.cameraFeatures.setFpsRange(fpsRange);
    }

    // Create capture callback.
    captureTimeouts = new CaptureTimeoutsWrapper(3000, 3000);
    captureProps = new CameraCaptureProperties();
    cameraCaptureCallback = CameraCaptureCallback.create(this, captureTimeouts, captureProps);

    startBackgroundThread();
  }

  @Override
  public void onConverged() {
    takePictureAfterPrecapture();
  }

  @Override
  public void onPrecapture() {
    runPrecaptureSequence();
  }

  /**
   * Updates the builder settings with all of the available features.
   *
   * @param requestBuilder request builder to update.
   */
  void updateBuilderSettings(CaptureRequest.Builder requestBuilder) {
    for (CameraFeature<?> feature : cameraFeatures.getAllFeatures()) {
      if (BuildConfig.DEBUG) {
        Log.d(TAG, "Updating builder with feature: " + feature.getDebugName());
      }
      feature.updateBuilder(requestBuilder);
    }
    if (highSpeedRecording && highSpeedFps != null && requestBuilder == previewRequestBuilder) {
      // A high-speed session takes only its own frame rate ranges, and only a fixed one records
      // every frame at the rate. The feature keeps the regular range for the preview that follows.
      requestBuilder.set(
          CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
          new Range<Integer>(highSpeedFps, highSpeedFps));
    }
  }

  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    Log.i(TAG, "prepareMediaRecorder");

    if (mediaRecorder != null) {
      mediaRecorder.release();
    }
    closeRenderer();

    final PlatformChannel.DeviceOrientation lockedOrientation =
        cameraFeatures.getSensorOrientation().getLockedCaptureOrientation();

    MediaRecorderBuilder mediaRecorderBuilder;

    // TODO(camsim99): Revert changes that allow legacy code to be used when recordingProfile is
    // null once this has largely been fixed on the Android side.
    // https://github.com/flutter/flutter/issues/119668
    final MediaRecorderBuilder.RecordingParameters parameters =
        new MediaRecorderBuilder.RecordingParameters(
            outputFilePath,
            highSpeedRecording ? highSpeedFps : regularRecordingFps,
            videoCaptureSettings.videoBitrate,
            videoCaptureSettings.audioBitrate,
            highSpeedRecording);

    if (SdkCapabilityChecker.supportsEncoderProfiles() && getRecordingProfile() != null) {
      mediaRecorderBuilder = new MediaRecorderBuilder(getRecordingProfile(), parameters);
    } else {
      mediaRecorderBuilder = new MediaRecorderBuilder(getRecordingProfileLegacy(), parameters);
    }

    mediaRecorder =
        mediaRecorderBuilder
            .setEnableAudio(videoCaptureSettings.enableAudio)
            .setMediaOrientation(
                lockedOrientation == null
                    ? getDeviceOrientationManager().getVideoOrientation()
                    : getDeviceOrientationManager().getVideoOrientation(lockedOrientation))
            .build();
  }

  @SuppressLint("MissingPermission")
  public void open(String imageFormatGroup) throws CameraAccessException {
    this.imageFormatGroup = imageFormatGroup;
    final ResolutionFeature resolutionFeature = cameraFeatures.getResolution();

    if (!resolutionFeature.checkIsSupported()) {
      // Tell the user that the camera they are trying to open is not supported,
      // as its {@link android.media.CamcorderProfile} cannot be fetched due to the name
      // not being a valid parsable integer.
      dartMessenger.sendCameraErrorEvent(
          "Camera with name \""
              + cameraProperties.getCameraName()
              + "\" is not supported by this plugin.");
      return;
    }

    // Always capture using JPEG format.
    pictureImageReader =
        ImageReader.newInstance(
            resolutionFeature.getCaptureSize().getWidth(),
            resolutionFeature.getCaptureSize().getHeight(),
            ImageFormat.JPEG,
            1);

    // For image streaming, use the provided image format or fall back to YUV420.
    Integer imageFormat = supportedImageFormats.get(imageFormatGroup);
    if (imageFormat == null) {
      Log.w(TAG, "The selected imageFormatGroup is not supported by Android. Defaulting to yuv420");
      imageFormat = ImageFormat.YUV_420_888;
    }
    imageStreamReader =
        new ImageStreamReader(
            resolutionFeature.getPreviewSize().getWidth(),
            resolutionFeature.getPreviewSize().getHeight(),
            imageFormat,
            1);

    // Open the camera.
    CameraManager cameraManager = CameraUtils.getCameraManager(activity);
    cameraManager.openCamera(
        cameraProperties.getCameraName(),
        new CameraDevice.StateCallback() {
          @Override
          public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = new DefaultCameraDeviceWrapper(device);
            try {
              startPreview();
              if (!recordingVideo) { // only send initialization if we werent already recording and
                // switching cameras
                dartMessenger.sendCameraInitializedEvent(
                    resolutionFeature.getPreviewSize().getWidth(),
                    resolutionFeature.getPreviewSize().getHeight(),
                    cameraFeatures.getExposureLock().getValue(),
                    cameraFeatures.getAutoFocus().getValue(),
                    cameraFeatures.getExposurePoint().checkIsSupported(),
                    cameraFeatures.getFocusPoint().checkIsSupported());
              }
            } catch (Exception e) {
              if (BuildConfig.DEBUG) {
                Log.i(TAG, "open | onOpened error: " + e.getMessage());
              }
              dartMessenger.sendCameraErrorEvent(e.getMessage());
              close();
            }
          }

          @Override
          public void onClosed(@NonNull CameraDevice camera) {
            Log.i(TAG, "open | onClosed");

            // Prevents calls to methods that would otherwise result in IllegalStateException
            // exceptions.
            cameraDevice = null;
            closeCaptureSession();
            dartMessenger.sendCameraClosingEvent();
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG, "open | onDisconnected");

            close();
            dartMessenger.sendCameraErrorEvent("The camera was disconnected.");
          }

          @Override
          public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
            Log.i(TAG, "open | onError");

            close();
            String errorDescription;
            switch (errorCode) {
              case ERROR_CAMERA_IN_USE:
                errorDescription = "The camera device is in use already.";
                break;
              case ERROR_MAX_CAMERAS_IN_USE:
                errorDescription = "Max cameras in use";
                break;
              case ERROR_CAMERA_DISABLED:
                errorDescription = "The camera device could not be opened due to a device policy.";
                break;
              case ERROR_CAMERA_DEVICE:
                errorDescription = "The camera device has encountered a fatal error";
                break;
              case ERROR_CAMERA_SERVICE:
                errorDescription = "The camera service has encountered a fatal error.";
                break;
              default:
                errorDescription = "Unknown camera error";
            }
            dartMessenger.sendCameraErrorEvent(errorDescription);
          }
        },
        backgroundHandler);
  }

  @VisibleForTesting
  void createCaptureSession(int templateType, Surface... surfaces) throws CameraAccessException {
    createCaptureSession(templateType, null, surfaces);
  }

  private void createCaptureSession(
      int templateType, Runnable onSuccessCallback, Surface... surfaces)
      throws CameraAccessException {
    // Close any existing capture session.
    captureSession = null;

    // Create a new capture builder.
    previewRequestBuilder = cameraDevice.createCaptureRequest(templateType);

    // Build Flutter surface to render to.
    ResolutionFeature resolutionFeature = cameraFeatures.getResolution();
    surfaceProducer.setSize(
        resolutionFeature.getPreviewSize().getWidth(),
        resolutionFeature.getPreviewSize().getHeight());
    Surface flutterSurface = surfaceProducer.getSurface();
    previewRequestBuilder.addTarget(flutterSurface);

    List<Surface> remainingSurfaces = Arrays.asList(surfaces);
    if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
      // If it is not preview mode, add all surfaces as targets
      // except the surface used for still capture as this should
      // not be part of a repeating request.
      Surface pictureImageReaderSurface = pictureImageReader.getSurface();
      for (Surface surface : remainingSurfaces) {
        if (surface == pictureImageReaderSurface) {
          continue;
        }
        previewRequestBuilder.addTarget(surface);
      }
    }

    // Update camera regions.
    Size cameraBoundaries =
        CameraRegionUtils.getCameraBoundaries(cameraProperties, previewRequestBuilder);
    cameraFeatures.getExposurePoint().setCameraBoundaries(cameraBoundaries);
    cameraFeatures.getFocusPoint().setCameraBoundaries(cameraBoundaries);

    // Prepare the callback.
    CameraCaptureSession.StateCallback callback =
        new CameraCaptureSession.StateCallback() {
          boolean captureSessionClosed = false;

          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.i(TAG, "CameraCaptureSession onConfigured");
            // Camera was already closed.
            if (cameraDevice == null || captureSessionClosed) {
              dartMessenger.sendCameraErrorEvent("The camera was closed during configuration.");
              return;
            }
            captureSession = session;

            Log.i(TAG, "Updating builder settings");
            updateBuilderSettings(previewRequestBuilder);

            refreshPreviewCaptureSession(
                onSuccessCallback, (code, message) -> dartMessenger.sendCameraErrorEvent(message));
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(TAG, "CameraCaptureSession onConfigureFailed");
            dartMessenger.sendCameraErrorEvent("Failed to configure camera session.");
          }

          @Override
          public void onClosed(@NonNull CameraCaptureSession session) {
            Log.i(TAG, "CameraCaptureSession onClosed");
            captureSessionClosed = true;
          }
        };

    // Start the session.
    if (SdkCapabilityChecker.supportsSessionConfiguration()) {
      // Collect all surfaces to render to.
      List<OutputConfiguration> configs = new ArrayList<>();
      configs.add(new OutputConfiguration(flutterSurface));
      for (Surface surface : remainingSurfaces) {
        configs.add(new OutputConfiguration(surface));
      }
      createCaptureSessionWithSessionConfig(configs, callback);
    } else {
      // Collect all surfaces to render to.
      List<Surface> surfaceList = new ArrayList<>();
      surfaceList.add(flutterSurface);
      surfaceList.addAll(remainingSurfaces);
      createCaptureSession(surfaceList, callback);
    }
  }

  @TargetApi(VERSION_CODES.P)
  private void createCaptureSessionWithSessionConfig(
      List<OutputConfiguration> outputConfigs, CameraCaptureSession.StateCallback callback)
      throws CameraAccessException {
    cameraDevice.createCaptureSession(
        new SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            Executors.newSingleThreadExecutor(),
            callback));
  }

  @SuppressWarnings("deprecation")
  private void createCaptureSession(
      List<Surface> surfaces, CameraCaptureSession.StateCallback callback)
      throws CameraAccessException {
    cameraDevice.createCaptureSession(surfaces, callback, backgroundHandler);
  }

  // Send a repeating request to refresh  capture session.
  void refreshPreviewCaptureSession(
      @Nullable Runnable onSuccessCallback, @NonNull ErrorCallback onErrorCallback) {
    Log.i(TAG, "refreshPreviewCaptureSession");

    if (captureSession == null) {
      Log.i(
          TAG,
          "refreshPreviewCaptureSession: captureSession not yet initialized, "
              + "skipping preview capture session refresh.");
      return;
    }

    try {
      if (!pausedPreview) {
        setRepeating(previewRequestBuilder.build(), cameraCaptureCallback);
      }

      if (onSuccessCallback != null) {
        onSuccessCallback.run();
      }

    } catch (IllegalStateException e) {
      onErrorCallback.onError("cameraAccess", "Camera is closed: " + e.getMessage());
    } catch (CameraAccessException e) {
      onErrorCallback.onError("cameraAccess", e.getMessage());
    } catch (IllegalArgumentException e) {
      // A high-speed session refuses a request it cannot run at its rate.
      onErrorCallback.onError("cameraAccess", e.getMessage());
    }
  }

  /**
   * Repeats a request for as long as the session runs. A constrained high-speed session takes no
   * single request: it is given the burst the session makes of one, a request for every frame in
   * the time of one preview frame.
   */
  private void setRepeating(
      @NonNull CaptureRequest request, @Nullable CameraCaptureSession.CaptureCallback callback)
      throws CameraAccessException {
    if (captureSession instanceof CameraConstrainedHighSpeedCaptureSession) {
      captureSession.setRepeatingBurst(
          ((CameraConstrainedHighSpeedCaptureSession) captureSession)
              .createHighSpeedRequestList(request),
          callback == cameraCaptureCallback ? highSpeedCaptureCallback : callback,
          backgroundHandler);
    } else {
      captureSession.setRepeatingRequest(request, callback, backgroundHandler);
    }
  }

  /** Takes what is waiting to start the high-speed recording, so that it runs once. */
  @Nullable
  private synchronized Runnable takeHighSpeedStart() {
    final Runnable start = highSpeedStart;
    highSpeedStart = null;
    return start;
  }

  /**
   * Starts a waiting high-speed recording once the exposure has settled.
   *
   * <p>The preview before it ran in a regular session, whose frames can be exposed for twice as
   * long as a 120 frames a second one allows. The high-speed session starts from that exposure cut
   * short, a stop too dark in a dim room, and the device brightens it over the next half second
   * (measured on a Pixel 9 Pro Fold: a first frame at 80% of the settled brightness). Recorded,
   * that is a fade-up at the head of every take. So the session first runs on the preview alone,
   * for at most {@link #HIGH_SPEED_SETTLE_MS}, and the recorder joins once the exposure holds.
   *
   * @param aeState the {@link CaptureResult#CONTROL_AE_STATE} of a frame of the session; null on
   *     a device that does not report one, which counts as settled.
   */
  @VisibleForTesting
  void onHighSpeedExposure(@Nullable Integer aeState) {
    final boolean settled =
        aeState == null
            || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
            || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED;
    if (settled) {
      final Runnable start = takeHighSpeedStart();
      if (start != null) {
        start.run();
      }
    }
  }

  /** Answers the app's call to start a high-speed recording, once. */
  private synchronized void answerStart(@Nullable String error) {
    final Result result = pendingStartResult;
    pendingStartResult = null;
    if (result == null) {
      return;
    }
    if (error == null) {
      dartMessenger.finish(result, null);
    } else {
      dartMessenger.error(result, "videoRecordingFailed", error, null);
    }
  }

  /** Sends a request once, as a burst where the session is a constrained high-speed one. */
  private void captureOnce(
      @NonNull CaptureRequest request, @Nullable CameraCaptureSession.CaptureCallback callback)
      throws CameraAccessException {
    if (captureSession instanceof CameraConstrainedHighSpeedCaptureSession) {
      captureSession.captureBurst(
          ((CameraConstrainedHighSpeedCaptureSession) captureSession)
              .createHighSpeedRequestList(request),
          callback,
          backgroundHandler);
    } else {
      captureSession.capture(request, callback, backgroundHandler);
    }
  }

  /**
   * Whether the next recording runs in a constrained high-speed session.
   *
   * <p>Not while images are streamed: the session takes two surfaces, the preview and the
   * recorder. And not zoomed out past 1x: on a camera made of several lenses that is its wide
   * lens, and a high-speed session stays on the main one, so the recording would not be framed
   * as the preview was. (Seen on a Pixel 9 Pro Fold, where the wide lens cannot be asked for by
   * itself in a high-speed session either.) Both record at the regular rate.
   */
  @VisibleForTesting
  boolean recordsAtHighSpeed(boolean streamingImages) {
    if (highSpeedFps == null || highSpeedFailed || streamingImages) {
      return false;
    }
    if (!SdkCapabilityChecker.supportsSessionConfiguration()) {
      return false;
    }
    final Float zoom = cameraFeatures.getZoomLevel().getValue();
    return zoom == null || zoom >= HIGH_SPEED_WIDEST_ZOOM;
  }

  /**
   * The widest zoom at which a recording runs at the rate the app asked for, when that rate is
   * above the regular ones; null when no zoom gives it and every recording runs at the regular
   * rate.
   *
   * <p>For an app that would sooner have the rate than the widest picture: it starts its zoom
   * here, and at the camera's widest when this is null. The answer is 1x on a camera whose
   * widest zoom is its wide lens, and the camera's own widest zoom when that is tighter. A
   * camera whose wide lens records at the rate would answer with less than 1x: this does not
   * look for one, because the only phone it has been tried on (a Pixel 9 Pro Fold) refuses a
   * high-speed session on its wide lens though it lists the rate for it.
   *
   * <p>Null too once a high-speed session has failed to start on this camera: it is not tried
   * again until the camera is reopened.
   */
  @Nullable
  public Float getHighSpeedMinZoomLevel() {
    if (highSpeedFps == null
        || highSpeedFailed
        || !SdkCapabilityChecker.supportsSessionConfiguration()) {
      return null;
    }
    return Math.max(HIGH_SPEED_WIDEST_ZOOM, cameraFeatures.getZoomLevel().getMinimumZoomLevel());
  }

  /**
   * Starts a recording in a constrained high-speed session, the only kind that records above the
   * regular frame rates.
   *
   * <p>The session takes two surfaces of one size, so the preview runs at the recording's size
   * while it lasts, and there is no surface for a still: {@link #takePicture} refuses until the
   * recording stops. The device also takes over exposure, white balance and focus modes; zoom,
   * exposure offset and the torch still apply.
   *
   * <p>The recorder starts once the exposure has settled (see {@link #onHighSpeedExposure}), and
   * the app's call is answered then, so a take is timed from its first frame.
   *
   * <p>A session that will not start is not an error to the app: the recording starts at the
   * regular rate instead, into the same file (see {@link #recordRegularInstead}).
   */
  @TargetApi(VERSION_CODES.P)
  private void startHighSpeedCapture() throws CameraAccessException {
    // Close any existing capture session.
    captureSession = null;

    previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

    final Size size = cameraFeatures.getResolution().getCaptureSize();
    surfaceProducer.setSize(size.getWidth(), size.getHeight());
    final Surface flutterSurface = surfaceProducer.getSurface();
    final Surface recorderSurface = mediaRecorder.getSurface();
    // Only the preview at first. The recorder's surface joins when the recorder starts: frames
    // sent to a recorder that is not running are never taken off its queue, and the camera
    // stalls behind them (the stop after such a start timed out on a Pixel 9 Pro Fold).
    previewRequestBuilder.addTarget(flutterSurface);

    // Update camera regions.
    Size cameraBoundaries =
        CameraRegionUtils.getCameraBoundaries(cameraProperties, previewRequestBuilder);
    cameraFeatures.getExposurePoint().setCameraBoundaries(cameraBoundaries);
    cameraFeatures.getFocusPoint().setCameraBoundaries(cameraBoundaries);

    CameraCaptureSession.StateCallback callback =
        new CameraCaptureSession.StateCallback() {
          boolean captureSessionClosed = false;

          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.i(TAG, "High-speed CameraCaptureSession onConfigured");
            // Camera was already closed.
            if (cameraDevice == null || captureSessionClosed) {
              dartMessenger.sendCameraErrorEvent("The camera was closed during configuration.");
              return;
            }
            captureSession = session;
            updateBuilderSettings(previewRequestBuilder);

            // The session runs on the preview alone until the exposure has settled (see
            // onHighSpeedExposure), or for a wait that a session delivering no frames cannot
            // outlast. Then the recorder starts and its surface joins the request.
            final long configuredAt = System.nanoTime();
            synchronized (Camera.this) {
              highSpeedStart =
                  () -> {
                    try {
                      mediaRecorder.start();
                      previewRequestBuilder.addTarget(recorderSurface);
                      if (!pausedPreview) {
                        setRepeating(previewRequestBuilder.build(), cameraCaptureCallback);
                      }
                    } catch (CameraAccessException | RuntimeException e) {
                      recordRegularInstead(e.getMessage());
                      return;
                    }
                    Log.i(
                        TAG,
                        "Recording at "
                            + highSpeedFps
                            + " frames a second in a high-speed session, "
                            + size.getWidth()
                            + "x"
                            + size.getHeight()
                            + ", after "
                            + (System.nanoTime() - configuredAt) / 1000000
                            + " ms for the exposure to settle");
                    answerStart(null);
                  };
            }
            refreshPreviewCaptureSession(
                () ->
                    backgroundHandler.postDelayed(
                        () -> {
                          final Runnable start = takeHighSpeedStart();
                          if (start != null) {
                            start.run();
                          }
                        },
                        HIGH_SPEED_SETTLE_MS),
                (code, message) -> recordRegularInstead(message));
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(TAG, "High-speed CameraCaptureSession onConfigureFailed");
            recordRegularInstead("the session could not be configured");
          }

          @Override
          public void onClosed(@NonNull CameraCaptureSession session) {
            Log.i(TAG, "High-speed CameraCaptureSession onClosed");
            captureSessionClosed = true;
          }
        };

    List<OutputConfiguration> configs = new ArrayList<>();
    configs.add(new OutputConfiguration(flutterSurface));
    configs.add(new OutputConfiguration(recorderSurface));
    try {
      cameraDevice.createCaptureSession(
          new SessionConfiguration(
              SessionConfiguration.SESSION_HIGH_SPEED,
              configs,
              Executors.newSingleThreadExecutor(),
              callback));
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      // The surfaces are checked as the session is made: a size or a kind it does not take.
      recordRegularInstead(e.getMessage());
    }
  }

  /**
   * Starts the recording in progress at the regular rate, after a high-speed session would not
   * start. The app asked for a recording and gets one, into the file it was promised; a
   * high-speed session is not tried again on this camera.
   */
  private void recordRegularInstead(@Nullable String reason) {
    Log.w(
        TAG,
        "No high-speed recording ("
            + reason
            + "); recording at "
            + regularRecordingFps
            + " frames a second instead");
    highSpeedFailed = true;
    highSpeedRecording = false;
    takeHighSpeedStart();
    if (!recordingVideo || captureFile == null || cameraDevice == null) {
      answerStart("The recording was stopped before it started.");
      return;
    }
    try {
      prepareMediaRecorder(captureFile.getAbsolutePath());
      startCapture(true, false);
      answerStart(null);
    } catch (IOException | CameraAccessException | RuntimeException e) {
      recordingVideo = false;
      captureFile = null;
      answerStart(e.getMessage());
    }
  }

  private void startCapture(boolean record, boolean stream) throws CameraAccessException {
    List<Surface> surfaces = new ArrayList<>();
    Runnable successCallback = null;
    if (record) {
      surfaces.add(mediaRecorder.getSurface());
      successCallback = () -> mediaRecorder.start();
    }
    if (stream && imageStreamReader != null) {
      surfaces.add(imageStreamReader.getSurface());
    }

    // Add pictureImageReader surface to allow for still capture
    // during recording/image streaming.
    surfaces.add(pictureImageReader.getSurface());

    createCaptureSession(
        CameraDevice.TEMPLATE_RECORD, successCallback, surfaces.toArray(new Surface[0]));
  }

  public void takePicture(@NonNull final Result result) {
    // A high-speed session has no surface for a still.
    if (highSpeedRecording) {
      result.error(
          "captureUnavailable",
          "A picture cannot be taken while recording at a high frame rate",
          null);
      return;
    }

    // Only take one picture at a time.
    if (cameraCaptureCallback.getCameraState() != CameraState.STATE_PREVIEW) {
      result.error("captureAlreadyActive", "Picture is currently already being captured", null);
      return;
    }

    flutterResult = result;

    // Create temporary file.
    final File outputDir = applicationContext.getCacheDir();
    try {
      captureFile = File.createTempFile("CAP", ".jpg", outputDir);
      captureTimeouts.reset();
    } catch (IOException | SecurityException e) {
      dartMessenger.error(flutterResult, "cannotCreateFile", e.getMessage(), null);
      return;
    }

    // Listen for picture being taken.
    pictureImageReader.setOnImageAvailableListener(this, backgroundHandler);

    final AutoFocusFeature autoFocusFeature = cameraFeatures.getAutoFocus();
    final boolean isAutoFocusSupported = autoFocusFeature.checkIsSupported();
    if (isAutoFocusSupported && autoFocusFeature.getValue() == FocusMode.auto) {
      runPictureAutoFocus();
    } else {
      runPrecaptureSequence();
    }
  }

  /**
   * Run the precapture sequence for capturing a still image. This method should be called when a
   * response is received in {@link #cameraCaptureCallback} from lockFocus().
   */
  private void runPrecaptureSequence() {
    Log.i(TAG, "runPrecaptureSequence");
    try {
      // First set precapture state to idle or else it can hang in STATE_WAITING_PRECAPTURE_START.
      previewRequestBuilder.set(
          CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
          CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
      captureOnce(previewRequestBuilder.build(), cameraCaptureCallback);

      // Repeating request to refresh preview session.
      refreshPreviewCaptureSession(
          null,
          (code, message) -> dartMessenger.error(flutterResult, "cameraAccess", message, null));

      // Start precapture.
      cameraCaptureCallback.setCameraState(CameraState.STATE_WAITING_PRECAPTURE_START);

      previewRequestBuilder.set(
          CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
          CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

      // Trigger one capture to start AE sequence.
      captureOnce(previewRequestBuilder.build(), cameraCaptureCallback);

    } catch (CameraAccessException e) {
      e.printStackTrace();
    }
  }

  /**
   * Capture a still picture. This method should be called when a response is received {@link
   * #cameraCaptureCallback} from both lockFocus().
   */
  private void takePictureAfterPrecapture() {
    Log.i(TAG, "captureStillPicture");
    cameraCaptureCallback.setCameraState(CameraState.STATE_CAPTURING);

    if (cameraDevice == null) {
      return;
    }
    // This is the CaptureRequest.Builder that is used to take a picture.
    CaptureRequest.Builder stillBuilder;
    try {
      stillBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
    } catch (CameraAccessException e) {
      dartMessenger.error(flutterResult, "cameraAccess", e.getMessage(), null);
      return;
    }
    stillBuilder.addTarget(pictureImageReader.getSurface());

    // Zoom.
    stillBuilder.set(
        CaptureRequest.SCALER_CROP_REGION,
        previewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION));

    // Have all features update the builder.
    updateBuilderSettings(stillBuilder);

    // Orientation.
    final PlatformChannel.DeviceOrientation lockedOrientation =
        cameraFeatures.getSensorOrientation().getLockedCaptureOrientation();
    stillBuilder.set(
        CaptureRequest.JPEG_ORIENTATION,
        lockedOrientation == null
            ? getDeviceOrientationManager().getPhotoOrientation()
            : getDeviceOrientationManager().getPhotoOrientation(lockedOrientation));

    CameraCaptureSession.CaptureCallback captureCallback =
        new CameraCaptureSession.CaptureCallback() {
          @Override
          public void onCaptureCompleted(
              @NonNull CameraCaptureSession session,
              @NonNull CaptureRequest request,
              @NonNull TotalCaptureResult result) {
            unlockAutoFocus();
          }
        };

    try {
      Log.i(TAG, "sending capture request");
      captureSession.capture(stillBuilder.build(), captureCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      dartMessenger.error(flutterResult, "cameraAccess", e.getMessage(), null);
    }
  }

  @SuppressWarnings("deprecation")
  private Display getDefaultDisplay() {
    return activity.getWindowManager().getDefaultDisplay();
  }

  /** Starts a background thread and its {@link Handler}. */
  public void startBackgroundThread() {
    if (backgroundHandlerThread != null) {
      return;
    }

    backgroundHandlerThread = HandlerThreadFactory.create("CameraBackground");
    try {
      backgroundHandlerThread.start();
    } catch (IllegalThreadStateException e) {
      // Ignore exception in case the thread has already started.
    }
    backgroundHandler = HandlerFactory.create(backgroundHandlerThread.getLooper());
  }

  /** Stops the background thread and its {@link Handler}. */
  public void stopBackgroundThread() {
    if (backgroundHandlerThread != null) {
      backgroundHandlerThread.quitSafely();
    }
    backgroundHandlerThread = null;
    backgroundHandler = null;
  }

  /** Start capturing a picture, doing autofocus first. */
  private void runPictureAutoFocus() {
    Log.i(TAG, "runPictureAutoFocus");

    cameraCaptureCallback.setCameraState(CameraState.STATE_WAITING_FOCUS);
    lockAutoFocus();
  }

  private void lockAutoFocus() {
    Log.i(TAG, "lockAutoFocus");
    if (captureSession == null) {
      Log.i(TAG, "[unlockAutoFocus] captureSession null, returning");
      return;
    }

    // Trigger AF to start.
    previewRequestBuilder.set(
        CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);

    try {
      captureOnce(previewRequestBuilder.build(), null);
    } catch (CameraAccessException e) {
      dartMessenger.sendCameraErrorEvent(e.getMessage());
    }
  }

  /** Cancel and reset auto focus state and refresh the preview session. */
  void unlockAutoFocus() {
    Log.i(TAG, "unlockAutoFocus");
    if (captureSession == null) {
      Log.i(TAG, "[unlockAutoFocus] captureSession null, returning");
      return;
    }
    try {
      // Cancel existing AF state.
      previewRequestBuilder.set(
          CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
      captureOnce(previewRequestBuilder.build(), null);

      // Set AF state to idle again.
      previewRequestBuilder.set(
          CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);

      captureOnce(previewRequestBuilder.build(), null);
    } catch (CameraAccessException e) {
      dartMessenger.sendCameraErrorEvent(e.getMessage());
      return;
    }

    refreshPreviewCaptureSession(
        null,
        (errorCode, errorMessage) ->
            dartMessenger.error(flutterResult, errorCode, errorMessage, null));
  }

  public void startVideoRecording(
      @NonNull Result result, @Nullable EventChannel imageStreamChannel) {
    highSpeedRecording = recordsAtHighSpeed(imageStreamChannel != null);
    prepareRecording(result);

    if (imageStreamChannel != null) {
      setStreamHandler(imageStreamChannel);
    }
    initialCameraFacing = cameraProperties.getLensFacing();
    recordingVideo = true;
    try {
      if (highSpeedRecording) {
        // Answered when the recorder starts: see pendingStartResult.
        synchronized (this) {
          pendingStartResult = result;
        }
        startHighSpeedCapture();
      } else {
        startCapture(true, imageStreamChannel != null);
        result.success(null);
      }
    } catch (CameraAccessException e) {
      recordingVideo = false;
      highSpeedRecording = false;
      captureFile = null;
      synchronized (this) {
        pendingStartResult = null;
      }
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  private void closeRenderer() {
    if (videoRenderer != null) {
      videoRenderer.close();
      videoRenderer = null;
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }
    // Re-create autofocus feature so it's using continuous capture focus mode now.
    cameraFeatures.setAutoFocus(
        cameraFeatureFactory.createAutoFocusFeature(cameraProperties, false));
    recordingVideo = false;
    // The preview that follows runs in a regular session again.
    highSpeedRecording = false;
    // A recording stopped while it still waited for the exposure never started its recorder.
    takeHighSpeedStart();
    answerStart(null);
    try {
      closeRenderer();
      captureSession.abortCaptures();
      mediaRecorder.stop();
    } catch (CameraAccessException | IllegalStateException e) {
      // Ignore exceptions and try to continue (changes are camera session already aborted capture).
    }
    mediaRecorder.reset();
    try {
      startPreview();
    } catch (CameraAccessException | IllegalStateException | InterruptedException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }
    result.success(captureFile.getAbsolutePath());
    captureFile = null;
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (SdkCapabilityChecker.supportsVideoPause()) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (SdkCapabilityChecker.supportsVideoPause()) {
        mediaRecorder.resume();
      } else {
        result.error(
            "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  /**
   * Method handler for setting new flash modes.
   *
   * @param result Flutter result.
   * @param newMode new mode.
   */
  public void setFlashMode(@NonNull final Result result, @NonNull FlashMode newMode) {
    // Save the new flash mode setting.
    final FlashFeature flashFeature = cameraFeatures.getFlash();
    flashFeature.setValue(newMode);
    flashFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) -> result.error("setFlashModeFailed", "Could not set flash mode.", null));
  }

  /**
   * Method handler for setting new exposure modes.
   *
   * @param result Flutter result.
   * @param newMode new mode.
   */
  public void setExposureMode(@NonNull final Result result, @NonNull ExposureMode newMode) {
    final ExposureLockFeature exposureLockFeature = cameraFeatures.getExposureLock();
    exposureLockFeature.setValue(newMode);
    exposureLockFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) ->
            result.error("setExposureModeFailed", "Could not set exposure mode.", null));
  }

  /**
   * Sets new exposure point from dart.
   *
   * @param result Flutter result.
   * @param point The exposure point.
   */
  public void setExposurePoint(@NonNull final Result result, @Nullable Point point) {
    final ExposurePointFeature exposurePointFeature = cameraFeatures.getExposurePoint();
    exposurePointFeature.setValue(point);
    exposurePointFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) ->
            result.error("setExposurePointFailed", "Could not set exposure point.", null));
  }

  /** Return the max exposure offset value supported by the camera to dart. */
  public double getMaxExposureOffset() {
    return cameraFeatures.getExposureOffset().getMaxExposureOffset();
  }

  /** Return the min exposure offset value supported by the camera to dart. */
  public double getMinExposureOffset() {
    return cameraFeatures.getExposureOffset().getMinExposureOffset();
  }

  /** Return the exposure offset step size to dart. */
  public double getExposureOffsetStepSize() {
    return cameraFeatures.getExposureOffset().getExposureOffsetStepSize();
  }

  /**
   * Sets new focus mode from dart.
   *
   * @param result Flutter result.
   * @param newMode New mode.
   */
  public void setFocusMode(final Result result, @NonNull FocusMode newMode) {
    final AutoFocusFeature autoFocusFeature = cameraFeatures.getAutoFocus();
    autoFocusFeature.setValue(newMode);
    autoFocusFeature.updateBuilder(previewRequestBuilder);

    /*
     * For focus mode an extra step of actually locking/unlocking the
     * focus has to be done, in order to ensure it goes into the correct state.
     */
    if (!pausedPreview) {
      switch (newMode) {
        case locked:
          // Perform a single focus trigger.
          if (captureSession == null) {
            Log.i(TAG, "[unlockAutoFocus] captureSession null, returning");
            return;
          }
          lockAutoFocus();

          // Set AF state to idle again.
          previewRequestBuilder.set(
              CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);

          try {
            setRepeating(previewRequestBuilder.build(), null);
          } catch (CameraAccessException | IllegalArgumentException e) {
            if (result != null) {
              result.error(
                  "setFocusModeFailed", "Error setting focus mode: " + e.getMessage(), null);
            }
            return;
          }
          break;
        case auto:
          // Cancel current AF trigger and set AF to idle again.
          unlockAutoFocus();
          break;
      }
    }

    if (result != null) {
      result.success(null);
    }
  }

  /**
   * Sets new focus point from dart.
   *
   * @param result Flutter result.
   * @param point the new coordinates.
   */
  public void setFocusPoint(@NonNull final Result result, @Nullable Point point) {
    final FocusPointFeature focusPointFeature = cameraFeatures.getFocusPoint();
    focusPointFeature.setValue(point);
    focusPointFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) -> result.error("setFocusPointFailed", "Could not set focus point.", null));

    this.setFocusMode(null, cameraFeatures.getAutoFocus().getValue());
  }

  /**
   * Sets a new exposure offset from dart. From dart the offset comes as a double, like +1.3 or
   * -1.3.
   *
   * @param result flutter result.
   * @param offset new value.
   */
  public void setExposureOffset(@NonNull final Result result, double offset) {
    final ExposureOffsetFeature exposureOffsetFeature = cameraFeatures.getExposureOffset();
    exposureOffsetFeature.setValue(offset);
    exposureOffsetFeature.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(exposureOffsetFeature.getValue()),
        (code, message) ->
            result.error("setExposureOffsetFailed", "Could not set exposure offset.", null));
  }

  public float getMaxZoomLevel() {
    return cameraFeatures.getZoomLevel().getMaximumZoomLevel();
  }

  public float getMinZoomLevel() {
    return cameraFeatures.getZoomLevel().getMinimumZoomLevel();
  }

  /** Shortcut to get current recording profile. Legacy method provides support for SDK < 31. */
  CamcorderProfile getRecordingProfileLegacy() {
    return cameraFeatures.getResolution().getRecordingProfileLegacy();
  }

  EncoderProfiles getRecordingProfile() {
    return cameraFeatures.getResolution().getRecordingProfile();
  }

  /** Shortut to get deviceOrientationListener. */
  DeviceOrientationManager getDeviceOrientationManager() {
    return cameraFeatures.getSensorOrientation().getDeviceOrientationManager();
  }

  /**
   * Sets zoom level from dart.
   *
   * @param result Flutter result.
   * @param zoom new value.
   */
  public void setZoomLevel(@NonNull final Result result, float zoom) throws CameraAccessException {
    final ZoomLevelFeature zoomLevel = cameraFeatures.getZoomLevel();
    float maxZoom = zoomLevel.getMaximumZoomLevel();
    float minZoom = zoomLevel.getMinimumZoomLevel();

    if (zoom > maxZoom || zoom < minZoom) {
      String errorMessage =
          String.format(
              Locale.ENGLISH,
              "Zoom level out of bounds (zoom level should be between %f and %f).",
              minZoom,
              maxZoom);
      result.error("ZOOM_ERROR", errorMessage, null);
      return;
    }

    zoomLevel.setValue(zoom);
    zoomLevel.updateBuilder(previewRequestBuilder);

    refreshPreviewCaptureSession(
        () -> result.success(null),
        (code, message) -> result.error("setZoomLevelFailed", "Could not set zoom level.", null));
  }

  /**
   * Lock capture orientation from dart.
   *
   * @param orientation new orientation.
   */
  public void lockCaptureOrientation(PlatformChannel.DeviceOrientation orientation) {
    cameraFeatures.getSensorOrientation().lockCaptureOrientation(orientation);
  }

  /** Unlock capture orientation from dart. */
  public void unlockCaptureOrientation() {
    cameraFeatures.getSensorOrientation().unlockCaptureOrientation();
  }

  /** Pause the preview from dart. */
  public void pausePreview() throws CameraAccessException {
    if (!this.pausedPreview) {
      this.pausedPreview = true;

      if (this.captureSession != null) {
        this.captureSession.stopRepeating();
      }
    }
  }

  /** Resume the preview from dart. */
  public void resumePreview() {
    this.pausedPreview = false;
    this.refreshPreviewCaptureSession(
        null, (code, message) -> dartMessenger.sendCameraErrorEvent(message));
  }

  public void startPreview() throws CameraAccessException, InterruptedException {
    // If recording is already in progress, the camera is being flipped, so send it through the
    // VideoRenderer to keep the correct orientation.
    if (recordingVideo) {
      startPreviewWithVideoRendererStream();
    } else {
      startRegularPreview();
    }
  }

  private void startRegularPreview() throws CameraAccessException {
    if (pictureImageReader == null || pictureImageReader.getSurface() == null) return;
    Log.i(TAG, "startPreview");
    createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());
  }

  private void startPreviewWithVideoRendererStream()
      throws CameraAccessException, InterruptedException {
    if (videoRenderer == null) return;

    // get rotation for rendered video
    final PlatformChannel.DeviceOrientation lockedOrientation =
        cameraFeatures.getSensorOrientation().getLockedCaptureOrientation();
    DeviceOrientationManager orientationManager =
        cameraFeatures.getSensorOrientation().getDeviceOrientationManager();

    int rotation = 0;
    if (orientationManager != null) {
      rotation =
          lockedOrientation == null
              ? orientationManager.getVideoOrientation()
              : orientationManager.getVideoOrientation(lockedOrientation);
    }

    if (cameraProperties.getLensFacing() != initialCameraFacing) {
      // If the new camera is facing the opposite way than the initial recording,
      // the rotation should be flipped 180 degrees.
      rotation = (rotation + 180) % 360;
    }
    videoRenderer.setRotation(rotation);

    createCaptureSession(CameraDevice.TEMPLATE_RECORD, videoRenderer.getInputSurface());
  }

  public void startPreviewWithImageStream(EventChannel imageStreamChannel)
      throws CameraAccessException {
    setStreamHandler(imageStreamChannel);

    startCapture(false, true);
    Log.i(TAG, "startPreviewWithImageStream");
  }

  /**
   * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
   * still image is ready to be saved.
   */
  @Override
  public void onImageAvailable(ImageReader reader) {
    Log.i(TAG, "onImageAvailable");

    // Use acquireNextImage since image reader is only for one image.
    Image image = reader.acquireNextImage();
    if (image == null) {
      return;
    }

    backgroundHandler.post(
        new ImageSaver(
            image,
            captureFile,
            new ImageSaver.Callback() {
              @Override
              public void onComplete(String absolutePath) {
                dartMessenger.finish(flutterResult, absolutePath);
              }

              @Override
              public void onError(String errorCode, String errorMessage) {
                dartMessenger.error(flutterResult, errorCode, errorMessage, null);
              }
            }));
    cameraCaptureCallback.setCameraState(CameraState.STATE_PREVIEW);
  }

  @VisibleForTesting
  void prepareRecording(@NonNull Result result) {
    final File outputDir = applicationContext.getCacheDir();
    try {
      captureFile = File.createTempFile("REC", ".mp4", outputDir);
    } catch (IOException | SecurityException e) {
      result.error("cannotCreateFile", e.getMessage(), null);
      return;
    }
    try {
      prepareMediaRecorder(captureFile.getAbsolutePath());
    } catch (IOException e) {
      recordingVideo = false;
      captureFile = null;
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }
    // Re-create autofocus feature so it's using video focus mode now.
    cameraFeatures.setAutoFocus(
        cameraFeatureFactory.createAutoFocusFeature(cameraProperties, true));
  }

  private void setStreamHandler(EventChannel imageStreamChannel) {
    imageStreamChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
            setImageStreamImageAvailableListener(imageStreamSink);
          }

          @Override
          public void onCancel(Object o) {
            if (imageStreamReader == null) {
              return;
            }

            imageStreamReader.removeListener(backgroundHandler);
          }
        });
  }

  void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
    if (imageStreamReader == null) {
      return;
    }

    imageStreamReader.subscribeListener(this.captureProps, imageStreamSink, backgroundHandler);
  }

  void closeCaptureSession() {
    if (captureSession != null) {
      Log.i(TAG, "closeCaptureSession");

      captureSession.close();
      captureSession = null;
    }
  }

  public void close() {
    Log.i(TAG, "close");

    // A high-speed recording still waiting to start is not left unanswered.
    if (takeHighSpeedStart() != null) {
      answerStart("The camera was closed before the recording started.");
    }
    highSpeedRecording = false;

    stopAndReleaseCamera();

    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }

    stopBackgroundThread();
  }

  private void stopAndReleaseCamera() {
    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;

      // Closing the CameraDevice without closing the CameraCaptureSession is recommended
      // for quickly closing the camera:
      // https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession#close()
      captureSession = null;
    } else {
      closeCaptureSession();
    }
  }

  private void prepareVideoRenderer() {
    if (videoRenderer != null) return;
    final ResolutionFeature resolutionFeature = cameraFeatures.getResolution();

    // handle videoRenderer errors
    Thread.UncaughtExceptionHandler videoRendererUncaughtExceptionHandler =
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread thread, Throwable ex) {
            dartMessenger.sendCameraErrorEvent(
                "Failed to process frames after camera was flipped.");
          }
        };

    videoRenderer =
        new VideoRenderer(
            mediaRecorder.getSurface(),
            resolutionFeature.getCaptureSize().getWidth(),
            resolutionFeature.getCaptureSize().getHeight(),
            videoRendererUncaughtExceptionHandler);
  }

  public void setDescriptionWhileRecording(
      @NonNull final Result result, CameraProperties properties) {
    if (!recordingVideo) {
      result.error("setDescriptionWhileRecordingFailed", "Device was not recording", null);
      return;
    }

    // See VideoRenderer.java; support for this EGL extension is required to switch camera while
    // recording.
    if (!SdkCapabilityChecker.supportsEglRecordableAndroid()) {
      result.error(
          "setDescriptionWhileRecordingFailed",
          "Device does not support switching the camera while recording",
          null);
      return;
    }

    stopAndReleaseCamera();
    // The other camera is recorded through the video renderer, in a regular session.
    highSpeedRecording = false;
    prepareVideoRenderer();
    cameraProperties = properties;
    cameraFeatures =
        CameraFeatures.init(
            cameraFeatureFactory,
            cameraProperties,
            activity,
            dartMessenger,
            videoCaptureSettings.resolutionPreset);
    cameraFeatures.setAutoFocus(
        cameraFeatureFactory.createAutoFocusFeature(cameraProperties, true));
    try {
      open(imageFormatGroup);
    } catch (CameraAccessException e) {
      result.error("setDescriptionWhileRecordingFailed", e.getMessage(), null);
    }
    result.success(null);
  }

  public void dispose() {
    Log.i(TAG, "dispose");

    close();
    surfaceProducer.release();
    getDeviceOrientationManager().stop();
  }

  /** Factory class that assists in creating a {@link HandlerThread} instance. */
  static class HandlerThreadFactory {
    /**
     * Creates a new instance of the {@link HandlerThread} class.
     *
     * <p>This method is visible for testing purposes only and should never be used outside this *
     * class.
     *
     * @param name to give to the HandlerThread.
     * @return new instance of the {@link HandlerThread} class.
     */
    @VisibleForTesting
    public static HandlerThread create(String name) {
      return new HandlerThread(name);
    }
  }

  /** Factory class that assists in creating a {@link Handler} instance. */
  static class HandlerFactory {
    /**
     * Creates a new instance of the {@link Handler} class.
     *
     * <p>This method is visible for testing purposes only and should never be used outside this *
     * class.
     *
     * @param looper to give to the Handler.
     * @return new instance of the {@link Handler} class.
     */
    @VisibleForTesting
    public static Handler create(Looper looper) {
      return new Handler(looper);
    }
  }
}
