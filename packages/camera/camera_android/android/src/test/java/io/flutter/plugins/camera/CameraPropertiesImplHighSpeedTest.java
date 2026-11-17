// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The high-speed ranges read two characteristics, and outside Robolectric every characteristic's
 * key is the same null, so these live apart from {@link CameraPropertiesImplTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class CameraPropertiesImplHighSpeedTest {
  private static final String CAMERA_NAME = "0";
  private static final Size FULL_HD = new Size(1920, 1080);

  private final CameraCharacteristics mockCharacteristics = mock(CameraCharacteristics.class);
  private final StreamConfigurationMap mockMap = mock(StreamConfigurationMap.class);
  private CameraPropertiesImpl cameraProperties;

  @Before
  public void before() throws CameraAccessException {
    final CameraManager mockCameraManager = mock(CameraManager.class);
    when(mockCameraManager.getCameraCharacteristics(CAMERA_NAME)).thenReturn(mockCharacteristics);
    cameraProperties = new CameraPropertiesImpl(CAMERA_NAME, mockCameraManager);
    when(mockCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        .thenReturn(mockMap);
  }

  private void capabilitiesAre(int... capabilities) {
    when(mockCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))
        .thenReturn(capabilities);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void returnsTheRangesOfAListedSize() {
    final Range<Integer>[] ranges =
        (Range<Integer>[])
            new Range<?>[] {new Range<Integer>(30, 120), new Range<Integer>(120, 120)};
    capabilitiesAre(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO);
    when(mockMap.getHighSpeedVideoSizes()).thenReturn(new Size[] {new Size(1280, 720), FULL_HD});
    when(mockMap.getHighSpeedVideoFpsRangesFor(FULL_HD)).thenReturn(ranges);

    assertArrayEquals(ranges, cameraProperties.getHighSpeedVideoFpsRangesFor(FULL_HD));
  }

  @Test
  public void isEmptyForASizeTheCameraDoesNotList() {
    capabilitiesAre(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO);
    when(mockMap.getHighSpeedVideoSizes()).thenReturn(new Size[] {new Size(1280, 720)});

    assertEquals(0, cameraProperties.getHighSpeedVideoFpsRangesFor(FULL_HD).length);
    // Asking the map for the ranges of a size it does not list throws.
    verify(mockMap, never()).getHighSpeedVideoFpsRangesFor(any());
  }

  @Test
  public void isEmptyWithoutTheCapability() {
    capabilitiesAre(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE);

    assertEquals(0, cameraProperties.getHighSpeedVideoFpsRangesFor(FULL_HD).length);
    verify(mockMap, never()).getHighSpeedVideoSizes();
  }

  @Test
  public void isEmptyWhenTheCameraListsNoCapabilities() {
    assertEquals(0, cameraProperties.getHighSpeedVideoFpsRangesFor(FULL_HD).length);
  }
}
