// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.util.Range;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class RecordingRateTest {
  /** The regular ranges of a Pixel 9 Pro Fold's back camera. */
  private static final Range<Integer>[] REGULAR =
      ranges(
          new int[][] {{15, 15}, {24, 24}, {15, 30}, {24, 30}, {30, 30}, {15, 60}, {60, 60}});

  /** Its high-speed ranges at 1920x1080. */
  private static final Range<Integer>[] HIGH_SPEED =
      ranges(new int[][] {{30, 120}, {30, 240}, {120, 120}, {240, 240}});

  @SuppressWarnings("unchecked")
  private static Range<Integer>[] ranges(int[][] bounds) {
    final Range<Integer>[] ranges = (Range<Integer>[]) new Range<?>[bounds.length];
    for (int i = 0; i < bounds.length; i++) {
      ranges[i] = new Range<Integer>(bounds[i][0], bounds[i][1]);
    }
    return ranges;
  }

  @Test
  public void aRegularRateIsRecordedAsAsked() {
    final RecordingRate rate = RecordingRate.choose(60, REGULAR, HIGH_SPEED);

    assertEquals(60, rate.regularFps);
    assertNull(rate.highSpeedFps);
  }

  @Test
  public void aRateBelowTheHighestRegularOneIsLeftAlone() {
    // 50 is in no range, and a regular session was always given it as asked.
    final RecordingRate rate = RecordingRate.choose(50, REGULAR, HIGH_SPEED);

    assertEquals(50, rate.regularFps);
    assertNull(rate.highSpeedFps);
  }

  @Test
  public void aRateAboveTheRegularOnesRecordsAtHighSpeed() {
    final RecordingRate rate = RecordingRate.choose(120, REGULAR, HIGH_SPEED);

    assertEquals(Integer.valueOf(120), rate.highSpeedFps);
    // The preview, and a recording that cannot run at high speed, keep the highest regular rate.
    assertEquals(60, rate.regularFps);
  }

  @Test
  public void withoutHighSpeedItRecordsAtTheHighestRegularRate() {
    assertEquals(60, RecordingRate.choose(120, REGULAR, null).regularFps);
    assertNull(RecordingRate.choose(120, REGULAR, null).highSpeedFps);
    assertNull(RecordingRate.choose(120, REGULAR, ranges(new int[0][])).highSpeedFps);
  }

  @Test
  public void aRangeThatLetsTheRateFallIsNotHighSpeedRecording() {
    final RecordingRate rate =
        RecordingRate.choose(120, REGULAR, ranges(new int[][] {{30, 120}, {30, 240}}));

    assertNull(rate.highSpeedFps);
    assertEquals(60, rate.regularFps);
  }

  @Test
  public void aRateTheHighSpeedSessionDoesNotListIsNotRecordedAtHighSpeed() {
    // No phone lists 100, which is why 50 Hz mains has no high-speed answer on Android.
    final RecordingRate rate = RecordingRate.choose(100, REGULAR, HIGH_SPEED);

    assertNull(rate.highSpeedFps);
    assertEquals(60, rate.regularFps);
  }

  @Test
  public void aCameraThatTopsOutAtThirtyRecordsAtThirty() {
    final RecordingRate rate =
        RecordingRate.choose(60, ranges(new int[][] {{15, 30}, {30, 30}}), null);

    assertEquals(30, rate.regularFps);
    assertNull(rate.highSpeedFps);
  }

  @Test
  public void aCameraThatListsNoRangesIsGivenTheRateAsAsked() {
    assertEquals(120, RecordingRate.choose(120, null, HIGH_SPEED).regularFps);
    assertNull(RecordingRate.choose(120, null, HIGH_SPEED).highSpeedFps);
  }
}
