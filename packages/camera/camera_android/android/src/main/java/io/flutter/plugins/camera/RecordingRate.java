// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camera;

import android.util.Range;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The frame rates a camera records at when an app asks for one.
 *
 * <p>A regular capture session records up to the highest rate in the camera's auto-exposure
 * ranges, 60 on most phones. Asked for more, it does not fail: it quietly records at some rate of
 * its own (a Pixel 9 Pro Fold asked for 120 records 30). Anything above the regular rates needs a
 * constrained high-speed session, which a camera has only at the sizes and rates it lists.
 *
 * <p>So a request within the regular rates is recorded as asked. A request above them is recorded
 * in a high-speed session where the camera lists that exact rate, and otherwise at the highest
 * regular rate. The regular rate is always set, because a high-speed recording is not always
 * possible (see {@code Camera#recordsAtHighSpeed}) and the preview never runs at it.
 */
final class RecordingRate {
  /** Frames a second in a regular session: the preview's rate, and a regular recording's. */
  final int regularFps;

  /** Frames a second in a high-speed session, or null when the camera has none at this rate. */
  @Nullable final Integer highSpeedFps;

  RecordingRate(int regularFps, @Nullable Integer highSpeedFps) {
    this.regularFps = regularFps;
    this.highSpeedFps = highSpeedFps;
  }

  /**
   * @param requestedFps the rate the app asked for.
   * @param regularRanges the camera's auto-exposure frame rate ranges, which are what a regular
   *     session can run at.
   * @param highSpeedRanges the frame rate ranges of a high-speed session at the recording's size;
   *     null or empty when it has none.
   */
  @NonNull
  static RecordingRate choose(
      int requestedFps,
      @Nullable Range<Integer>[] regularRanges,
      @Nullable Range<Integer>[] highSpeedRanges) {
    int highestRegular = 0;
    if (regularRanges != null) {
      for (Range<Integer> range : regularRanges) {
        if (range != null && range.getUpper() != null) {
          highestRegular = Math.max(highestRegular, range.getUpper());
        }
      }
    }
    if (highestRegular <= 0 || requestedFps <= highestRegular) {
      return new RecordingRate(requestedFps, null);
    }

    Integer highSpeed = null;
    if (highSpeedRanges != null) {
      for (Range<Integer> range : highSpeedRanges) {
        // Only a fixed range records every frame at the rate: [30, 120] lets the rate fall.
        if (range != null
            && range.getLower() != null
            && range.getUpper() != null
            && range.getLower() == requestedFps
            && range.getUpper() == requestedFps) {
          highSpeed = requestedFps;
        }
      }
    }
    return new RecordingRate(highestRegular, highSpeed);
  }
}
