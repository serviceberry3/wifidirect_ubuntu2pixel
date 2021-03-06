/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package weiner.noah.wifidirect;

public class Constants {
    //Request camera and external storage permission.
    public static final int REQUEST_CAMERA_PERMISSION = 1;

    //From https://blog.paperspace.com/posenet-keypoint-detection-android-app/: "The posenet model accepts an image size of (257, 257).
    //If an image is to be passed to the posenet model, then its size must be (257, 257); otherwise, an exception will be thrown
    //For example, if an image read from a gallery is a different size, it must be resized before being passed to posenet

    //required input image width
    public static final int MODEL_WIDTH = 257;

    //required input image height
    public static final int MODEL_HEIGHT= 257;

    //average pupillary distance for adults? use this *for now* to estimate drone's distance from person
    public static final float PD = (float) 0.063; //63 mm

    //91 cm away -> ~90 pixel pupillary distance.
    //Thus experimental focal len of camera = (P x D) / W = (90px x 0.91m) / 0.063m = 1300
    public static final float focalLenExpAdjusted = 1300f;

    //Focal length based on the scaled-down 257x257 image. 30cm away -> ~42 pixel pup distance
    //Thus experimental focal len of camera = (P x D) / W = (42px x 0.30m) / 0.063m = 200
    //Or (30px x 0.46m) / 0.063m = 219
    public static final float focalLenExp = 219f;

    //Meanwhile, using opencv chessboard calibration, claimed focal length x is 524.1, y is 523.9

    public static final float angleCalibrationAdjustmentLeft = 0f;
    public static final float angleCalibrationAdjustmentRight = 0f;

    //tuning params for when using only face to calculate human torso angle
    public static final float angleCalibrationAdjustmentFaceLeft = 20f;
    public static final float angleCalibrationAdjustmentFaceRight = 34f;

    public static final float PIVOT_WEIGHT = 1.5f;

    public static final float pupillaryDistanceCalibrationAdjustmentLeft = 0f;
    public static final float pupillaryDistanceCalibrationAdjustmentRight = 5f;

    public static final double FRAME_CENTER = 128.5;

    //
}
