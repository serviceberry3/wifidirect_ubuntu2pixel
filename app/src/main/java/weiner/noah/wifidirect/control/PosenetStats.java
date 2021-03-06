package weiner.noah.wifidirect.control;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.tensorflow.lite.examples.noah.lib.BodyPart;
import org.tensorflow.lite.examples.noah.lib.KeyPoint;
import org.tensorflow.lite.examples.noah.lib.Person;
import org.tensorflow.lite.examples.noah.lib.Posenet;
import org.tensorflow.lite.examples.noah.lib.Position;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import weiner.noah.wifidirect.Battery;
import weiner.noah.wifidirect.AtomicFloat;
import weiner.noah.wifidirect.ConfirmationDialog;
import weiner.noah.wifidirect.Constants;
import weiner.noah.wifidirect.ErrorDialog;
import weiner.noah.wifidirect.R;
import weiner.noah.wifidirect.Thermal;
import weiner.noah.wifidirect.ThermalService;
import weiner.noah.wifidirect.utils.CircBuffer;
import weiner.noah.wifidirect.utils.ImageUtils;

public class PosenetStats {
    private Posenet posenet;
    private MainActivity mainActivity;
    private HumanFollower caller;
    private final String TAG = "PosenetStats";

    //whether we have both eyes of human in the frame. Determines whether or not we can calculate dist to the human
    private boolean bothEyesFound = false;

    //whether we were able to get a solid reading of the angle
    private boolean angleCalculatedCorrectly = false;

    //whether we were able to get a solid reading of the torso tilt ratio
    private boolean torsoTiltCalculatedCorrectly = false;

    //whether we were able to get a solid reading of offset between frame center and bb center
    private boolean bbOffCenterCalculatedCorrectly = false;

    //whether, if we couldn't do full bb, we could at least find center offset using eyes
    private boolean bbOffCenterFellBackToEyesOnly = false;

    //should we log thermal data?
    private final boolean SHOULD_LOG_THERM_DATA = false;

    private Thread mLiveFeedThread = null;
    private PosenetLiveStatFeed posenetLiveStatFeed;

    private final int CIRC_BUFF_SIZE = 25;

    private final AtomicFloat dist_to_hum = new AtomicFloat();
    private final AtomicFloat hum_angle = new AtomicFloat();
    private final AtomicFloat hum_tilt_ratio = new AtomicFloat();
    private final AtomicFloat bb_off_center = new AtomicFloat();

    private final Thermal thermal;
    private final ThermalService thermalService;
    private final Battery battery;

    private final CircBuffer xVelBuffer = new CircBuffer(CIRC_BUFF_SIZE);
    private final CircBuffer yVelBuffer = new CircBuffer(CIRC_BUFF_SIZE);
    private final CircBuffer angVelBuffer = new CircBuffer(CIRC_BUFF_SIZE);

    private AtomicFloat mPerPixel = new AtomicFloat();


    public PosenetStats(Posenet posenet, MainActivity mainActivity, HumanFollower caller) {
        this.posenet = posenet;
        this.mainActivity = mainActivity;
        this.caller = caller;

        //instantiate new Thermal
        this.thermal = new Thermal("/sys/class/thermal/", mainActivity);

        //instantiate new Battery
        this.battery = new Battery("sys/class/power_supply/", mainActivity);

        this.thermalService = new ThermalService(mainActivity, caller);

        //On construction, we'd like to launch a background thread which runs Posenet on incoming images from front-facing camera,
        //and allows polling of the data (distance from human, angle of human, etc)
    }

    public void start() {
        posenetLiveStatFeed = new PosenetLiveStatFeed();
        mLiveFeedThread = new Thread(posenetLiveStatFeed);
        mLiveFeedThread.start();

        if (SHOULD_LOG_THERM_DATA) {
            //start logging thermal and battery readings
            thermal.startLogging();
        }

        thermalService.startListening();
    }

    public void stop() {
        //end the LiveFeedThread
        if (mLiveFeedThread != null) {
            mLiveFeedThread.interrupt();
        }
        mLiveFeedThread = null;

        if (posenetLiveStatFeed != null) {
            posenetLiveStatFeed.closeCamera();
            posenetLiveStatFeed.stopBackgroundThread();
            posenetLiveStatFeed = null;
        }

        //stop logging thermal and battery readings?
        //thermal.stopLogging();

        //thermalService.stopListening();
    }

    public float getDistToHum() {
        if (bothEyesFound)
            return dist_to_hum.get();
        else
            return -1;
    }

    public float getHumAngle() {
        if (angleCalculatedCorrectly)
            return hum_angle.get();
        else
            return -1;
    }

    public float getTorsoTiltRatio() {
        if (torsoTiltCalculatedCorrectly)
            return hum_tilt_ratio.get();
        else
            return -1;
    }

    public float getBbOffCenter() {
        if (bbOffCenterCalculatedCorrectly)
            return bb_off_center.get();
        else
            return -1;
    }

    public float getXVel() {
        float ret = xVelBuffer.getDispOverTime();

        //we're actually just looking for displacement div by time
        return xVelBuffer.getDispOverTime();
    }

    //get current scale in meters per pixel based on calculated dist of person
    public float getCurrScale() {
        return mPerPixel.get();
    }

    public float getYVel() {
        return yVelBuffer.getDispOverTime();
    }

    public float getAngVel() {
        return angVelBuffer.getDispOverTime();
    }

    private class PosenetLiveStatFeed implements Runnable {
        /**
         * List of body joints that should be connected.
         */
        ArrayList<Pair> bodyJoints = new ArrayList<Pair>(
                Arrays.asList(new Pair(BodyPart.LEFT_WRIST, BodyPart.LEFT_ELBOW),
                        new Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_SHOULDER),
                        new Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
                        new Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
                        new Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
                        new Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
                        new Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
                        new Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_SHOULDER),
                        new Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
                        new Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
                        new Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
                        new Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)));

        /**
         * Threshold for confidence score.
         */
        private double minConfidence = 0.5;

        /**
         * Radius of circle used to draw keypoints.
         */
        private float circleRadius = 8.0f;

        /**
         * Paint class holds the style and color information to draw geometries,text and bitmaps.
         */
        private Paint redPaint = new Paint();
        private Paint bluePaint = new Paint();
        private Paint greenPaint = new Paint();
        private Paint whitePaint = new Paint();

        /**
         * A shape for extracting frame data.
         */
        private int PREVIEW_WIDTH = 640;
        private int PREVIEW_HEIGHT = 480;



        //Macros for 'looking' variable
        private final int LOOKING_LEFT = 0;
        private final int LOOKING_RIGHT = 1;

        /**
         * Tag for the [Log].
         */
        private String TAG = "PosenetActivity";

        private String FRAGMENT_DIALOG = "dialog";

        //Whether to use front- or rear-facing camera
        private final boolean USE_FRONT_CAM = true;


        /**
         * ID of the current [CameraDevice].
         */
        private String cameraId = null; //nullable

        /**
         * A [CameraCaptureSession] for camera preview.
         */
        private CameraCaptureSession captureSession = null; //nullable

        /**
         * A reference to the opened [CameraDevice].
         */
        private CameraDevice cameraDevice = null; //nullable

        /**
         * The [android.util.Size] of camera preview.
         */
        private Size previewSize = null;

        /**
         * The [android.util.Size.getWidth] of camera preview.
         */
        private int previewWidth = 0;

        /**
         * The [android.util.Size.getHeight] of camera preview.
         */
        private int previewHeight = 0;

        /**
         * A counter to keep count of total frames.
         */
        private int frameCounter = 0;

        /**
         * An IntArray to save image data in ARGB8888 format
         */
        private int[] rgbBytes;

        /**
         * A ByteArray to save image data in YUV format
         */
        private byte[][] yuvBytes = new byte[3][];  //???

        /**
         * An additional thread for running tasks that shouldn't block the UI.
         */
        private HandlerThread backgroundThread = null; //nullable

        /**
         * A [Handler] for running tasks in the background.
         */
        private Handler backgroundHandler = null; //nullable

        /**
         * An [ImageReader] that handles preview frame capture.
         */
        private ImageReader imageReader = null; //nullable

        /**
         * [CaptureRequest.Builder] for the camera preview
         */
        private CaptureRequest.Builder previewRequestBuilder = null; //nullable

        /**
         * [CaptureRequest] generated by [.previewRequestBuilder
         */
        private CaptureRequest previewRequest = null; //nullable

        /**
         * A [Semaphore] to prevent the app from exiting before closing the camera, allows one thread access at a time.
         */
        private Semaphore cameraOpenCloseLock = new Semaphore(1);

        /**
         * Whether the current camera device supports Flash or not.
         */
        private boolean flashSupported = false;

        /**
         * Orientation of the camera sensor.
         */
        private int sensorOrientation = 0;  //was null. Need Integer?

        //NAIVE IMPLEMENTATION ACCEL ARRAYS

        //temporary array to store raw linear accelerometer data before low-pass filter applied
        private final float[] NtempAcc = new float[3];

        //acceleration array for data after filtering
        private final float[] Nacc = new float[3];

        //velocity array (calculated from acceleration values)
        private final float[] Nvelocity = new float[3];

        //position (displacement) array (calculated from dVelocity values)
        private final float[] Nposition = new float[3];

        //NOSHAKE SPRING IMPLEMENTATION ACCEL ARRAYS
        private final float[] StempAcc = new float[3];
        private final float[] Sacc = new float[3];
        private final float[] accAfterFrix = new float[3];

        //long to use for keeping track of thyme
        private long timestamp = 0;

        //the view to be stabilized
        private View layoutSensor, waitingText;

        //the text that can be dragged around (compare viewing of this text to how the stabilized text looks)
        private TextView noShakeText;

        //original vs. changed layout parameters of the draggable text
        private RelativeLayout.LayoutParams originalLayoutParams;
        private RelativeLayout.LayoutParams editedLayoutParams;
        private int ogLeftMargin, ogTopMargin;

        //the accelerometer and its manager
        private Sensor accelerometer;
        private SensorManager sensorManager;

        //changes in x and y to be used to move the draggable text based on user's finger
        private int _xDelta, _yDelta;

        //time variables, and the results of H(t) and Y(t) functions
        private double HofT, YofT, startTime, timeElapsed;

        //the raw values that the low-pass filter is applied to
        private float[] gravity = new float[3];

        //working on circular buffer for the data
        private float[] accelBuffer = new float[3];

        private float impulseSum;

        //is the device shaking??
        private volatile int shaking = 0;

        private int index = 0, check = 0, times = 0;

        private Thread outputPlayerThread = null;

        public float toMoveX, toMoveY;

        float noseDeltaX, noseDeltaY;

        //declare global matrix containing my model 3D coordinates of human pose, to be used for camera pose estimation
        private Point3[] humanModelRaw = new Point3[6];
        private List<Point3> humanModelList = new ArrayList<Point3>();
        private MatOfPoint3f humanModelMat;

        //declare global matrix containing the actual 2D coordinates of the human found
        private Point[] humanActualRaw = new Point[6];

        //used for bounding box points
        private Point[] boundingBox = new Point[4];

        private List<Point> humanActualList = new ArrayList<Point>();
        private MatOfPoint2f humanActualMat;

        //matrices to be used for pose estimation calculation
        private Mat cameraMatrix, rotationMat, translationMat;
        private MatOfDouble distortionMat;

        Point3[] testPts = new Point3[3];
        List<Point3> testPtList = new ArrayList<Point3>();

        private int capture = 0;

        //which direction the person is looking (split at exactly perp to camera)
        private int looking;

        //declare floats for computing actual 2D dist found between nose and eyes and shoulders
        //this lets us deduce whether the person is looking left or rt (we need to swap axes)
        private float distToLeftEyeX, distToRightEyeX, distToLeftShouldX, distToRtShouldX;

        //float for finding center of human bust (pt between shoulders) in 2D coordinates, used as "origin" for drawing
        private float torsoCtrX, torsoCtrY;

        private Point torsoCenter;

        /**
         * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
         */
        private class stateCallback extends CameraDevice.StateCallback {
            //when camera has been opened, release the lock and create a preview session for the camera
            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice) {
                Log.i(TAG, "CAMERA OPENED");
                cameraOpenCloseLock.release();
                PosenetLiveStatFeed.this.cameraDevice = cameraDevice;
                createCameraPreviewSession();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                Log.i(TAG, "CAMERA DISCONNECTED");
                cameraOpenCloseLock.release();
                cameraDevice.close();
                cameraDevice = null;

                //kill the calling HumanFollower
                caller.kill();
            }

            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int i) {
                Log.e(TAG, "CAMERA ERROR");
                onDisconnected(cameraDevice);
                mainActivity.finish();

                //kill the calling HumanFollower
                caller.kill();
            }
        }

        /**
         * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
         */
        private class captureCallback extends CameraCaptureSession.CaptureCallback {
            @Override
            public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                super.onCaptureProgressed(session, request, partialResult);

            }

            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
            }
        }

        //Creates a new [CameraCaptureSession] for camera preview.
        private void createCameraPreviewSession() {
            try {
                // We capture images from preview in YUV format.
                imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

                imageReader.setOnImageAvailableListener(new imageAvailableListener(), backgroundHandler);

                List<Surface> recordingSurfaces = new ArrayList<Surface>();

                // This is the surface we need to record images for processing.
                Surface recordingSurface = imageReader.getSurface();

                recordingSurfaces.add(recordingSurface);

                //We set up a CaptureRequest.Builder with the output Surface.
                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                previewRequestBuilder.addTarget(recordingSurface);

                // Here we create a CameraCaptureSession for camera preview.
                cameraDevice.createCaptureSession(
                        recordingSurfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                // The camera is already closed
                                if (cameraDevice == null) return;

                                // When the session is ready, we start displaying the preview.
                                captureSession = cameraCaptureSession;

                                try {
                                    // Auto focus should be continuous for camera preview.
                                    previewRequestBuilder.set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                    );
                                    // Flash is automatically enabled when necessary.
                                    setAutoFlash(previewRequestBuilder);

                                    // Finally, we start displaying the camera preview.
                                    previewRequest = previewRequestBuilder.build();
                                    captureSession.setRepeatingRequest(previewRequest, new captureCallback(), backgroundHandler);
                                }
                                catch (CameraAccessException e) {
                                    Log.e(TAG, e.toString());
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                                showToast("Failed");
                            }
                        }, null);
            } catch (CameraAccessException e) {
                Log.e(TAG, e.toString());
            }
        }

        private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
            if (flashSupported) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            }
        }

        /**
         * Fill the yuvBytes with data from image planes.
         */
        private void fillBytes(Image.Plane[] planes, byte[][] yuvBytes) {
            // Row stride is the total number of bytes occupied in memory by a row of an image.
            // Because of the variable row stride it's not possible to know in
            // advance the actual necessary dimensions of the yuv planes.
            for (int i = 0; i < planes.length; i++) {
                ByteBuffer buffer = planes[i].getBuffer();

                //create new byte array in yuvBytes the size of this plane
                if (yuvBytes[i] == null) {
                    yuvBytes[i] = new byte[(buffer.capacity())];
                }

                //store the raw ByteBuffer of the plane at this location in yuvBytes 2D array
                buffer.get(yuvBytes[i]);
            }
        }

        /**
         * Starts a background thread and its [Handler].
         */
        private void startBackgroundThread() {
            //A HandlerThread is a Thread that has a Looper
            backgroundThread = new HandlerThread("imageAvailableListener");

            //start up the background thread
            backgroundThread.start();

            //create a new Handler using the background thread's Looper to post work on the background thread
            //This will allow us to handle camera state changes on background thread, since we pass this handler to cameraManager.openCamera()
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }

        /**
         * Stops the background thread and its [Handler].
         */
        private void stopBackgroundThread() {
            if (backgroundThread != null)  {
                //Quits the handler thread's looper safely.
                //
                //Causes handler thread's looper to terminate as soon as all remaining messages in message queue that are already due to be delivered have been handled.
                //Pending delayed messages with due times in the future will not be delivered.
                //
                //Any attempt to post messages to queue after looper is asked to quit will fail.
                //
                //If thread has not been started or has finished (that is if getLooper() returns null), then false is returned. Otherwise thread's looper is asked to quit and true is returned
                backgroundThread.quitSafely();
            }

            try {
                if (backgroundThread != null) {
                    //terminate the background thread by joining
                    backgroundThread.join();
                }

                //clear background thread vars
                backgroundThread = null;
                backgroundHandler = null;
            }
            catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }
        }


        /**
         * A [OnImageAvailableListener] to receive frames as they are available.
         */
        private class imageAvailableListener implements ImageReader.OnImageAvailableListener {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Log.i(TAG, "onImageAvailable");

                /*
                int temp = thermal.getBattTemp();
                Log.i(TAG, "Batter ytemp is " + temp);*/

                //We need to wait until we have some size from onPreviewSizeChosen
                if (previewWidth == 0 || previewHeight == 0) {
                    return;
                }

                //acquire the latest image from the the ImageReader queue
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    return;
                }

                //get the planes from the image
                Image.Plane[] planes = image.getPlanes();

                //put all planes data into 2D byte array called yuvBytes
                fillBytes(planes, yuvBytes);

                //get first plane
                Image.Plane copy = planes[0];

                //get raw bytes from incoming 2d image
                ByteBuffer byteBuffer = copy.getBuffer();

                //create new array of raw bytes of the appropriate size (remaining)
                byte[] buffer = new byte[byteBuffer.remaining()];

                //store the ByteBuffer in the raw byte array (the pixels from first plane of image)
                byteBuffer.get(buffer);

                //instantiate new Matrix object to hold the image pixels
                Mat imageGrab = new Mat();

                //put all of the bytes into the Mat
                imageGrab.put(0, 0, buffer);

                ImageUtils imageUtils = new ImageUtils();

                //convert the three planes into single int array called rgbBytes
                imageUtils.convertYUV420ToARGB8888(yuvBytes[0], yuvBytes[1], yuvBytes[2], previewWidth, previewHeight,
                        /*yRowStride=*/ image.getPlanes()[0].getRowStride(),
                        /*uvRowStride=*/ image.getPlanes()[1].getRowStride(),
                        /*uvPixelStride=*/ image.getPlanes()[1].getPixelStride(),
                        rgbBytes //an int[]
                );

                // Create bitmap from int array
                Bitmap imageBitmap = Bitmap.createBitmap(rgbBytes, previewWidth, previewHeight, Bitmap.Config.ARGB_8888);

                /*
                // Create rotated version (FOR PORTRAIT DISPLAY)
                Matrix rotateMatrix = new Matrix();
                rotateMatrix.postRotate(90.0f);

                Bitmap rotatedBitmap = Bitmap.createBitmap(imageBitmap, 0, 0, previewWidth, previewHeight, rotateMatrix, true);*/
                image.close();

                //testing convert bitmap to OpenCV Mat
                Mat testMat = new Mat();

                org.opencv.android.Utils.bitmapToMat(imageBitmap, testMat);

                /*
                //save the final rotated 480 x 640 bitmap
                if (capture == 0) {
                        Log.i(TAG, "Writing image");
                        Imgcodecs.imwrite("/data/data/weiner.noah.noshake.posenet.test/testCapture.jpg", testMat);
                }*/

                Log.i(TAG, String.format("Focal length found is %d", testMat.cols()));

                //set up the intrinsic camera matrix and initialize the world-to-camera translation and rotation matrices
                makeCameraMat();

                //send the final bitmap to be drawn on and output to the screen
                processImage(imageBitmap);
            }
        }

        private void makeCameraMat() {
            // Camera internals
            double focal_length_x = 526.69; // Approximate focal length, found from OpenCV chessboard calibration
            double focal_length_y = 540.36;

            //center of image plane
            Point center = new Point(313.07, 238.39);

            //Log.i(TAG, String.format("Center at %f, %f", center.x, center.y));

            //create a 3x3 camera (intrinsic params) matrix
            cameraMatrix = Mat.eye(3, 3, CvType.CV_64F);

            double[] vals = {focal_length_x, 0, center.x, 0, focal_length_y, center.y, 0, 0, 1};

            //populate the 3x3 camera matrix
            cameraMatrix.put(0, 0, vals);

            /*
            cameraMatrix.put(0, 0, 400);
            cameraMatrix.put(1, 1, 400);
            cameraMatrix.put(0, 2, 640 / 2f);
            cameraMatrix.put(1, 2, 480 / 2f);
             */

            //distortionMat = new MatOfDouble(0,0,0,0);

            /*
            cameraMatrix.put(0, 0, 400);
            cameraMatrix.put(1, 1, 400);
            cameraMatrix.put(0, 2, 640 / 2f);
            cameraMatrix.put(1, 2, 480 / 2f);
             */

            //assume no camera distortion
            distortionMat = new MatOfDouble(new Mat(4, 1, CvType.CV_64FC1));
            distortionMat.put(0, 0, 0);
            distortionMat.put(1, 0, 0);
            distortionMat.put(2, 0, 0);
            distortionMat.put(3, 0, 0);

            //new mat objects to store rotation and translation matrices from camera coords to world coords when solvePnp runs
            rotationMat = new Mat(1, 3, CvType.CV_64FC1);
            translationMat = new Mat(1, 3, CvType.CV_64FC1);

            //Hack! initialize transition and rotation matrixes to improve estimation
            translationMat.put(0, 0, -100);
            translationMat.put(0, 0, 100);
            translationMat.put(0, 0, 1000);

            if (distToLeftEyeX < distToRightEyeX) {
                //looking at left
                rotationMat.put(0, 0, -1.0);
                rotationMat.put(1, 0, -0.75);
                rotationMat.put(2, 0, -3.0);
                looking = LOOKING_LEFT;
            } else {
                //looking at right
                rotationMat.put(0, 0, 1.0);
                rotationMat.put(1, 0, -0.75);
                rotationMat.put(2, 0, -3.0);
                looking = LOOKING_RIGHT;
            }

        }

        /**
         * Crop Bitmap to maintain aspect ratio of model input.
         */
        private Bitmap cropBitmap(Bitmap bitmap) {
            float bitmapRatio = (float) bitmap.getHeight() / bitmap.getWidth();

            float modelInputRatio = (float) Constants.MODEL_HEIGHT / Constants.MODEL_WIDTH;

            //first set new edited bitmap equal to the passed one
            Bitmap croppedBitmap = bitmap;

            // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
            double maxDifference = 1e-5;

            // Checks if the bitmap has similar aspect ratio as the required model input.
            if (Math.abs(modelInputRatio - bitmapRatio) < maxDifference) {
                return croppedBitmap;
            } else if (modelInputRatio < bitmapRatio) {
                // New image is taller so we are height constrained.
                float cropHeight = bitmap.getHeight() - ((float) bitmap.getWidth() / modelInputRatio);

                croppedBitmap = Bitmap.createBitmap(bitmap, 0, (int) (cropHeight / 2), bitmap.getWidth(), (int) (bitmap.getHeight() - cropHeight));
            } else {
                float cropWidth = bitmap.getWidth() - ((float) bitmap.getHeight() * modelInputRatio);

                croppedBitmap = Bitmap.createBitmap(bitmap, (int) (cropWidth / 2), 0, (int) (bitmap.getWidth() - cropWidth), bitmap.getHeight());
            }

            Mat croppedImage = new Mat();

            org.opencv.android.Utils.bitmapToMat(croppedBitmap, croppedImage);

            /*
            if (capture == 0) {
                    Log.i(TAG, "Writing cropped image");
                    Imgcodecs.imwrite("/data/data/weiner.noah.noshake.posenet.test/testCaptureCropped.jpg", croppedImage);
            }*/

            return croppedBitmap;
        }


        //Process image using Posenet library. The image needs to be scaled in order to fit Posenet's input dimension requirements of
        //257 x 257 (defined in Constants.java), and probably needs to be cropped in order to preserve the image's aspect ratio
        private void processImage(Bitmap bitmap) {
            // Crop bitmap.
            Bitmap croppedBitmap = cropBitmap(bitmap);

            Mat scaledImage = new Mat();

            Log.i(TAG, String.valueOf(croppedBitmap.getConfig()));

            // Created scaled version of bitmap for model input (scales it to 257 x 257)
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, Constants.MODEL_WIDTH, Constants.MODEL_HEIGHT, true);

            //get bitmap from mat
            org.opencv.android.Utils.bitmapToMat(scaledBitmap, scaledImage);

            /*
            //save the scaled down bitmap of the first image taken (as a jpg)
            if (capture == 0) {
                    capture = 1;
                    Log.i(TAG, "Writing scaled image");
                    Imgcodecs.imwrite("/data/data/weiner.noah.noshake.posenet.test/testCaptureScaled0.jpg", scaledImage);
            }*/

            //Perform inference.
            Person person = posenet.estimateSinglePose(scaledBitmap);

            getTrackingInformation(person, scaledBitmap);

            //displacementOnly(person, canvas);
        }

        private int noseFound = 0;
        private float noseOriginX, noseOriginY, lastNosePosX, lastNosePosY;

        /**
         * Draw bitmap on Canvas.
         */
        //the Canvas class holds the draw() calls. To draw something, you need 4 basic components: A Bitmap to hold the pixels,
        // a Canvas to host the draw calls (writing into the bitmap),
        // a drawing primitive (e.g. Rect, Path, text, Bitmap), and a paint (to describe the colors and styles for the drawing).
        private void getTrackingInformation(Person person, Bitmap bitmap) { //NOTE: the Bitmap passed here is 257x257 pixels, good for Posenet model
            //Draw `bitmap` and `person` in square canvas.
            int screenWidth, screenHeight, left, right, top, bottom, canvasHeight, canvasWidth;

            //initialize right and left eye found flags to false
            int rightEyeFound = 0, leftEyeFound = 0;

            humanActualRaw[0] = humanActualRaw[1] = humanActualRaw[2] = humanActualRaw[3] = humanActualRaw[4] = humanActualRaw[5] = null;

            //initialize angle calculated correctly to false?
            //angleCalculatedCorrectly = false;

            float xValue, yValue, xVel, yVel;
            float dist = 0;

            double bbox_center = 0;

            BodyPart currentPart;
            Position leftEye = null, rightEye = null;


            int bmWidth = bitmap.getWidth(); //should be 257
            int bmHeight = bitmap.getHeight(); //should be 257

            Log.i(TAG, String.format("Bitmap width and height are %d and %d", bmWidth, bmHeight)); //should be 257x257


            //get the keypoints list ONCE at the beginning
            List<KeyPoint> keyPoints = person.getKeyPoints();

            //Log.d(TAG, String.format("Found %d keypoints for the person", keyPoints.size()));

            //Process keypoints of the person's body
            for (KeyPoint keyPoint : keyPoints) {
                //get the body part ONCE at the beginning
                currentPart = keyPoint.getBodyPart();

                //make sure we're confident enough about where this posenet pose is to display it
                if (keyPoint.getScore() > minConfidence) {
                    Position position = keyPoint.getPosition();
                    xValue = (float) position.getX();
                    yValue = (float) position.getY();


                    //I'll start by just using the person's nose to try to estimate how fast the phone is moving
                    if (currentPart == BodyPart.NOSE) {
                        //add nose to first slot of Point array for pose estimation
                        humanActualRaw[0] = new Point(xValue, yValue);
                        humanActualRaw[1] = new Point(xValue, yValue);

                    } else if (currentPart == BodyPart.LEFT_EYE) {
                        //add nose to first slot of Point array for pose estimation
                        humanActualRaw[2] = new Point(xValue, yValue);

                        //add x val of left eye to bbox array
                        boundingBox[1] = new Point(xValue, yValue);

                        leftEyeFound = 1;
                        leftEye = new Position(xValue, yValue);

                        //if we've also already found right eye, we have both eyes. Send data to the scale computer
                        if (rightEyeFound == 1) {
                            bothEyesFound = true;
                            dist = computeScale(leftEye, rightEye);
                            dist_to_hum.set(dist);

                            //add dist to human to the circular buffer
                            xVelBuffer.put(dist, SystemClock.elapsedRealtimeNanos());

                            Log.i(TAG, "Dist to hum is " + dist_to_hum.get());
                        }
                    } else if (currentPart == BodyPart.RIGHT_EYE) {
                        //add nose to first slot of Point array for pose estimation
                        humanActualRaw[3] = new Point(xValue, yValue);

                        //add x val of rt eye to bbox array
                        boundingBox[0] = new Point(xValue, yValue);

                        rightEyeFound = 1;
                        rightEye = new Position(xValue, yValue);

                        //if we've also already found left eye, we have both eyes. Send data to the scale computer
                        if (leftEyeFound == 1) {
                            bothEyesFound = true;
                            dist = computeScale(leftEye, rightEye);
                            dist_to_hum.set(dist);

                            //add dist to human to the circular buffer
                            xVelBuffer.put(dist, SystemClock.elapsedRealtimeNanos());

                            Log.i(TAG, "Dist to hum is " + dist_to_hum.get());
                        }

                    } else if (currentPart == BodyPart.RIGHT_SHOULDER) {
                        //add rt shoulder to fifth slot of Point array for pose estimation
                        humanActualRaw[4] = new Point(xValue, yValue);

                        boundingBox[2] = new Point(xValue, yValue);
                    } else if (currentPart == BodyPart.LEFT_SHOULDER) {
                        //add left shoulder to sixth slot of Point array for pose estimation
                        humanActualRaw[5] = new Point(xValue, yValue);

                        boundingBox[3] = new Point(xValue, yValue);
                    }
                }
            }

            //check whether both left and right eyes were in the frame, and set bothEyesFound accordingly
            bothEyesFound = (rightEyeFound & leftEyeFound) == 1;

            //notify HumanFollower that there's new distance data available
            caller.onNewDistanceData();
            caller.setFreshDist(true);

            //if we have everything needed to calculate torso tilt ratio
            if (humanActualRaw[2] != null && humanActualRaw[3] != null && humanActualRaw[4] != null && humanActualRaw[5] != null ) {
                double dist_rt_shoulder_eye = humanActualRaw[3].x - humanActualRaw[4].x;
                double dist_left_shoulder_eye = humanActualRaw[5].x - humanActualRaw[2].x;

                float adjusted_hum_ang_raw;

                hum_tilt_ratio.set((float)dist_rt_shoulder_eye / (float)dist_left_shoulder_eye);

                torsoTiltCalculatedCorrectly = true;

                Log.i(TAG, "Posenet: human torso ratio " + hum_tilt_ratio.get());

                //get raw torso angle and adjust it based on camera location
                double human_angle_raw = getHumAngleFromTorsoRatio(hum_tilt_ratio.get());

                //the angle couldn't be calculated correctly
                if (human_angle_raw == -10000) {
                    angleCalculatedCorrectly = false;
                }
                else {
                    //negative means turning right
                    if (human_angle_raw < 0) {
                        adjusted_hum_ang_raw = (float) human_angle_raw + Constants.angleCalibrationAdjustmentRight;
                        //positive means turning left

                        //add curr angle to circular buff
                    }
                    else {
                        adjusted_hum_ang_raw = (float) human_angle_raw - Constants.angleCalibrationAdjustmentLeft;

                        //add curr angle to circular buff
                    }
                    hum_angle.set(adjusted_hum_ang_raw);
                    angVelBuffer.put(adjusted_hum_ang_raw, SystemClock.elapsedRealtimeNanos());

                    angleCalculatedCorrectly = true;
                }


                Log.i("TORSO_DBUG", "Posenet: human torso angle using trig is " + hum_angle.get());
            }
            //otherwise if we do have nose and both eyes
            else if (humanActualRaw[0] != null && humanActualRaw[1] != null &&
                    humanActualRaw[2] != null && humanActualRaw[3] != null) {
                float adjusted_hum_ang_raw;

                Log.i(TAG, "Posenet: falling back to eyes/nose only for torso angle calculation");

                //fall back to estimating human angle from ratio of [nose to rt eye]:[nose to left eye]
                double dist_rt_eye_nose = humanActualRaw[0].x - humanActualRaw[3].x;
                double dist_left_eye_nose = humanActualRaw[2].x - humanActualRaw[0].x;

                Log.i(TAG, "Dist from rt eye to nose is " + dist_rt_eye_nose + ", dist from left eye to nose is " + dist_left_eye_nose);

                hum_tilt_ratio.set((float)dist_rt_eye_nose / (float)dist_left_eye_nose);

                torsoTiltCalculatedCorrectly = true;

                Log.i(TAG, "Posenet: human torso ratio calc from eyes/nose is " + hum_tilt_ratio.get());

                //get raw torso angle and adjust it based on camera location
                double human_angle_raw = getHumAngleFromFaceRatio(hum_tilt_ratio.get());

                //the angle couldn't be calculated correctly
                if (human_angle_raw == -10000) {
                    angleCalculatedCorrectly = false;
                }
                else {
                    //negative means turning right
                    if (human_angle_raw < 0) {
                        adjusted_hum_ang_raw = (float) human_angle_raw + Constants.angleCalibrationAdjustmentFaceRight;

                        //positive means turning left
                    }
                    else {
                        adjusted_hum_ang_raw = (float) human_angle_raw - Constants.angleCalibrationAdjustmentFaceLeft;

                    }
                    hum_angle.set(adjusted_hum_ang_raw);
                    angVelBuffer.put(adjusted_hum_ang_raw, SystemClock.elapsedRealtimeNanos());

                    angleCalculatedCorrectly = true;
                }


                Log.i("TORSO_DBUG", "Posenet: human torso angle using trig FROM FACE is " + hum_angle.get());
            }

            else {
                Log.i(TAG, "Posenet: UNABLE to calculate torso tilt ratio!!");
                torsoTiltCalculatedCorrectly = false;
                angleCalculatedCorrectly = false;
            }

            //notify HumanFollower of new torso tilt ratio data available
            caller.setFreshTorsoTiltRatio(true);

            //notify HumanFollower of new angle data available
            caller.setFreshAngle(true);


            //check that all of the keypoints for a human body bust area were found
            if (humanActualRaw[0] != null && humanActualRaw[1] != null && humanActualRaw[2] != null && humanActualRaw[3] != null
                    && humanActualRaw[4] != null && humanActualRaw[5] != null) {
                //BOUNDING BOX
                //top is aligned with uppermost eye
                double bbox_top = Math.max(humanActualRaw[3].y, humanActualRaw[2].y);

                //left is aligned w right shoulder
                double bbox_left = humanActualRaw[4].x;

                //rt is aligned w left shoulder
                double bbox_rt = humanActualRaw[5].x;

                Log.i(TAG, "bbox left is " + bbox_left + ", bbox right is " + bbox_rt);

                //bottom is at lowermost shoulder
                double bbox_bot = Math.min(humanActualRaw[4].y, humanActualRaw[5].y);

                bbox_center = (bbox_rt + bbox_left) / 2;

                float offset = (float)(bbox_center - Constants.FRAME_CENTER);

                //save bounding box's offset from center of frame into the bb_off_center AtomicFloat
                bb_off_center.set(offset);

                //this one is in pixels
                yVelBuffer.put(offset * mPerPixel.get(), SystemClock.elapsedRealtimeNanos());

                bbOffCenterCalculatedCorrectly = true;


                //IF USING SOLVEPNP
                /*
                distToLeftEyeX = (float) Math.abs(humanActualRaw[2].x - humanActualRaw[0].x);
                distToRightEyeX = (float) Math.abs(humanActualRaw[3].x - humanActualRaw[0].x);

                distToLeftEyeX = (float) Math.abs(humanActualRaw[5].x - humanActualRaw[0].x);
                distToRightEyeX = (float) Math.abs(humanActualRaw[4].x - humanActualRaw[0].x);


                //correction for axis flipping
                if (distToLeftEyeX > distToRightEyeX) {
                    //person looking towards left, swap left eye and rt eye for actual
                    Point temp = humanActualRaw[2];
                    humanActualRaw[2] = humanActualRaw[3];
                    humanActualRaw[3] = temp;
                }

                //correction for axis flipping
                if (distToLeftShouldX < distToRtShouldX) {
                    //person looking towards left, swap left eye and rt eye for actual
                    Point temp = humanActualRaw[5];
                    humanActualRaw[5] = humanActualRaw[4];
                    humanActualRaw[4] = temp;
                }*/
            }
            //otherwise maybe we have the two eyes, so can calculate a center offset with just those
            else if (humanActualRaw[2] != null && humanActualRaw[3] != null) {
                Log.i(TAG, "Posenet: falling back to eyes only for centering calculation");

                //find center point of eyes
                bbox_center = (humanActualRaw[2].x + humanActualRaw[3].x) / 2;

                float offset = (float)(bbox_center - Constants.FRAME_CENTER);

                //save bounding box's offset from center of frame into the bb_off_center AtomicFloat
                bb_off_center.set(offset);

                yVelBuffer.put(offset * mPerPixel.get(), SystemClock.elapsedRealtimeNanos());

                bbOffCenterCalculatedCorrectly = true;
            }

            else {
                Log.i(TAG, "Posenet: UNABLE to calculate bounding box center offset!!");
                bbOffCenterCalculatedCorrectly = false;
            }

            //notify HumanFollower of new bb center offset data available
            caller.setFreshBbCenterOffset(true);


                //HARDCODED, FIXME
                /*
                //find chest pt (midpt between shoulders)
                torsoCtrX = (float) (humanActualRaw[4].x + humanActualRaw[5].x) / 2;
                torsoCtrY = (float) (humanActualRaw[4].y + humanActualRaw[5].y) / 2;

                torsoCenter = new Point(torsoCtrX, torsoCtrY);
                //torsoCenter = new Point((rt_should.x + left_should.x)/2, (rt_should.y + left_should.y)/2);
                //humanActualRaw[0] = torsoCenter;

                //clear out the ArrayList
                humanActualList.clear();

                //compute pose estimation and draw line coming out of person's chest

                //add the pts of interest to a list
                humanActualList.add(humanActualRaw[0]);
                humanActualList.add(humanActualRaw[1]);
                humanActualList.add(humanActualRaw[2]);
                humanActualList.add(humanActualRaw[3]);
                humanActualList.add(humanActualRaw[4]);
                humanActualList.add(humanActualRaw[5]);

                Log.i(TAG, String.format("Human actual: [%f,%f], [%f,%f], [%f,%f], [%f,%f], [%f,%f], [%f, %f]",
                        humanActualList.get(0).x,
                        humanActualList.get(0).y,
                        humanActualList.get(1).x,
                        humanActualList.get(1).y,
                        humanActualList.get(2).x,
                        humanActualList.get(2).y,
                        humanActualList.get(3).x,
                        humanActualList.get(3).y,
                        humanActualList.get(4).x,
                        humanActualList.get(4).y,
                        humanActualList.get(5).x,
                        humanActualList.get(5).y));

                humanActualMat.fromList(humanActualList);

                //now should have everything we need to run solvePnP

                //solve for translation and rotation matrices based on a model of 3d pts for human bust area
                Calib3d.solvePnP(humanModelMat, humanActualMat, cameraMatrix, distortionMat, rotationMat, translationMat);

                //Now we'll try projecting our 3D axes onto the image plane
                MatOfPoint3f testPtMat = new MatOfPoint3f();
                testPtMat.fromList(testPtList);

                //the 2d pts that correspond to the 3d pts above. Will be filled upon return of projectPoints()
                MatOfPoint2f imagePts = new MatOfPoint2f();

                //project our basic x-y-z axis from the world coordinate system onto the camera coord system using rot and trans mats we solved
                Calib3d.projectPoints(testPtMat, rotationMat, translationMat, cameraMatrix, distortionMat, imagePts);

                //imagePts now contains 3 2D coordinates which correspond to ends of the 3D axes

                Log.i(TAG, String.format("Resulting imagepts Mat is of size %d x %d", imagePts.rows(), imagePts.cols()));

                //extract the 3 2D coordinates for drawing the 3D axes
                double[] x_ax = imagePts.get(0, 0);
                double[] y_ax = imagePts.get(1, 0);
                double[] z_ax = imagePts.get(2, 0);


                Log.i(TAG, String.format("Found point %f, %f for x axis", x_ax[0], x_ax[1]));
                Log.i(TAG, String.format("Found point %f, %f for y axis", y_ax[0], y_ax[1]));
                Log.i(TAG, String.format("Found point %f, %f for z axis", z_ax[0], z_ax[1]));


                //filter out the weird bogus data I was getting
                if (!(x_ax[0] > 2500 || x_ax[1] > 1400 || y_ax[0] > 1500 || y_ax[1] < -1000 || z_ax[0] > 1500 || z_ax[1] < -900
                        //check for illogical axes layout
                        || (looking == LOOKING_LEFT && z_ax[0] < y_ax[0]) || (looking == LOOKING_RIGHT && z_ax[0] > y_ax[0]))) {


                    //estimate angles for yaw and pitch of the human's upper body

                    //Mat eulerAngles = new Mat();

                    //THIS FXN DOESN'T WORK RIGHT NOW
                    //getEulerAngles(eulerAngles);

                    Log.i(TAG, "z ax[0] is " + z_ax[0]);
                    Log.i(TAG, "torsoCenter.x is ");
                    //we know length of z axis to be 81.25. Let's find length of 'opposite' side of the rt triangle so that we can use sine to find angle
                    float lenOpposite = (float) z_ax[0] - (float) torsoCenter.x;
                    Log.i(TAG, "Len opposite is " + lenOpposite);

                    float humAngle = getHumAnglesTrig(lenOpposite, 135f); //81.25?

                    hum_angle.set(humAngle);

                    //check to see if the human angle calculated successfully (90 or -90 almost always indicates corruption)
                    angleCalculatedCorrectly = (humAngle != -90f && humAngle != 90f);

                    //notify HumanFollower of new angle data available
                    caller.setFreshAngle(true);

                    Log.i(TAG, "Human angle is " + humAngle + " degrees");

                    //Log.i(TAG, String.format("Euler angles mat is of size %d x %d", eulerAngles.rows(), eulerAngles.cols()));

                    //pitch, yaw, roll
                    //double[] angles = eulerAngles.get(0,0);

                    double[] pitch = rotationMat.get(0, 0);
                    double[] yaw = rotationMat.get(1, 0);

                } else {
                    Log.i(TAG, "Something preventing angle data calculation");
                }
            }

            //reset contents of the arrays

            humanActualRaw[0] = humanActualRaw[1] = humanActualRaw[2] = humanActualRaw[3] = humanActualRaw[4] = null;

            //increment framecounter, if at 4 set to 0
            frameCounter++;
            if (frameCounter == 4) {
                frameCounter = 0;
            }*/
        }

        private float getHumAnglesTrig(float opp, float hyp) {
            Log.i(TAG, "opp/hyp is " + (opp / hyp));

            float ratio = opp / hyp;

            if (ratio <= -1)
                return -90f;
            else if (ratio >= 1)
                return 90f;

            return (float) Math.toDegrees(Math.asin(ratio));
        }

        //used trig to derive basic function of human's pivot angle on left:right ratio of shoulder-eye distances
        private double getHumAngleFromTorsoRatio(float ratio) {
            float infinity = Float.POSITIVE_INFINITY;
            float nan = infinity - infinity;
            float neg_infinity = infinity * -1;

            if (ratio > neg_infinity && ratio < infinity && ratio != nan) {
                //angle is 0 if ratio exactly 1
                if (ratio == 1)
                    return 0;

                final double v = (Math.sqrt(2f) * Math.sqrt((29257f * ratio * ratio) + (2736f * ratio) + 29257f)) / (167f * ratio - 167f);
                if (ratio >= -1f) {
                    return Math.toDegrees((
                            -2f * Math.atan(
                                    -v +
                                            (175 * ratio / (167 * ratio - 167)) +
                                            (175 / (167 * ratio - 167))
                            )
                    ));
                }

                else {
                    return Math.toDegrees((
                            -2f * Math.atan(
                                    v +
                                            (175 * ratio / (167 * ratio - 167)) +
                                            (175 / (167 * ratio - 167))
                            )
                    ));
                }
            }
            return -10000;
        }

        private double getHumAngleFromFaceRatio(float ratio) {
            float infinity = Float.POSITIVE_INFINITY;
            float nan = infinity - infinity;
            float neg_infinity = infinity * -1;

            if (ratio > neg_infinity && ratio < infinity && ratio != nan) {
                //angle is 0 if ratio exactly 1
                if (ratio == 1)
                    return 0;

                final double v = Math.sqrt( (4594 * ratio * ratio) - (6688 * ratio) + 4594) ;

                if (ratio >= -1f) {
                    return Math.toDegrees((
                            -2f * Math.atan(
                                    (-v + (25 * ratio) + 25
                                    ) /
                                            ( 63 * (ratio - 1) )

                            )
                    ));
                }

                else {
                    return Math.toDegrees((
                            -2f * Math.atan(
                                    (v + (25 * ratio) + 25
                                    ) /
                                            ( 63 * (ratio - 1) )

                            )
                    ));
                }
            }
            return -10000;
        }

        //compute how much distance each pixel currently represents in real life, using known data about avg human pupillary distance
        private float computeScale(Position leftEye, Position rightEye) {
            //I'll just use the x distance between left eye and right eye points to get distance in pixels between eyes
            //don't forget left eye is on the right and vice versa
            float pixelDistance = leftEye.getX() - rightEye.getX();

            Log.d(TAG, String.format("Pupillary distance in pixels: %f", pixelDistance));

            //now we want to find out how many real meters each pixel on the display corresponds to
            float scale = Constants.PD / pixelDistance;
            //how many real-world meters each pixel in the camera image represents

            Log.d(TAG, String.format("Each pixel on the screen represents %f meters in real life in plane of person's face", scale));

            //save scale for calculating y vel of person
            mPerPixel.set(scale);

            //find experimental distance from camera to human and display it on screen

            return calculateDistanceToHuman(pixelDistance);
        }

        private float calculateDistanceToHuman(float pixelDistance) {
            //Triangle simularity
            //D = (W * F) / P, where d = distance to hum, W = width of obj in real world coordinate frame, F = focal len of camera,
            //P = distance between eyes in pixels

            float curr_hum_angle_radians = (float)Math.toRadians(hum_angle.get());

            float apparent_pd_shrink_from_pivot = 0;

            //if human turned to right, correct some for camera location
            if (curr_hum_angle_radians < 0)
                pixelDistance += 5;

            //work in the angle: negative angle means person rotating rt from their perspective
            //pos angle means person rotating left form their POV
            if (getHumAngle() != -1.0f) {


                //find how far the eyes have displaced due to the human's current pivot angle
                float rt_eye_new_x_coord = (float)
                                //new x coordinate of right eye due to pivoting
                                ( (-0.0315 * Math.cos(curr_hum_angle_radians)) + (0.0875 * Math.sin(curr_hum_angle_radians)) );

                float rt_eye_disp_due_to_pivot = (float) Math.abs(rt_eye_new_x_coord -
                        //original x coordinate of right eye
                        (-.0315));


                //find how far the eyes have displaced due to the human's current pivot angle
                float left_eye_new_x_coord = (float)
                                //new x coordinate of left eye due to pivoting
                                ( (0.0315 * Math.cos(curr_hum_angle_radians)) + (0.0875 * Math.sin(curr_hum_angle_radians)) );

                float left_eye_disp_due_to_pivot = (float) Math.abs(left_eye_new_x_coord -
                //original x coordinate of left eye
                (.0315));

                Log.i("TORSO_DBUG", "Posenet: rt eye new x coord is " + rt_eye_new_x_coord + ", left eye new x coord is " + left_eye_new_x_coord);
                Log.i(TAG, "Posenet: rt eye disp is " + rt_eye_disp_due_to_pivot + ", left eye disp is " + left_eye_disp_due_to_pivot);

                //find how much the actual distance would have appeared to shrink in real life (in meters). WAS .063
                //this is the difference in displacements
                apparent_pd_shrink_from_pivot = Math.abs(left_eye_disp_due_to_pivot - rt_eye_disp_due_to_pivot);

                Log.i("TORSO DBUG", "Apparent pd shrink from pivot is " + apparent_pd_shrink_from_pivot);
            }

            //find distance to human in meters, subtracting
            return (Constants.PD - (apparent_pd_shrink_from_pivot * Constants.PIVOT_WEIGHT)) * Constants.focalLenExp / pixelDistance;
        }


        /**
         * Shows a [Toast] on the UI thread.
         *
         * @param text The message to show
         */
        private void showToast(final String text) {
            if (mainActivity != null)
                mainActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mainActivity, text, Toast.LENGTH_SHORT).show();
                    }
                });
        }

        private void requestCameraPermission() {
            if (mainActivity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                ConfirmationDialog confirmationDialog = new ConfirmationDialog();
                confirmationDialog.show(mainActivity.getSupportFragmentManager(), FRAGMENT_DIALOG);
            } else {
                String[] camera = {Manifest.permission.CAMERA};
                mainActivity.requestPermissions(camera, Constants.REQUEST_CAMERA_PERMISSION);
            }
        }

        /**
         * Sets up member variables related to camera.
         */
        private void setUpCameraOutputs() {
            Log.i(TAG, "setUpCameraOutputs called!");

            CameraManager cameraManager = (CameraManager) mainActivity.getSystemService(Context.CAMERA_SERVICE);

            try {
                for (String cameraId : cameraManager.getCameraIdList()) {
                    CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

                    //don't use front facing camera in this example
                    Integer cameraDirection = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);

                    if (USE_FRONT_CAM && cameraDirection != null && cameraDirection == CameraCharacteristics.LENS_FACING_BACK) {
                        //skip this one because it's a back-facing camera, we wanna use the front-facing
                        continue;
                    } else if (!USE_FRONT_CAM && cameraDirection != null && cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                        //skip this one because it's front-facing cam, we wanna use the rear-facing
                        continue;
                    }

                    previewSize = new Size(PREVIEW_WIDTH, PREVIEW_HEIGHT);

                    imageReader = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 2);

                    try {
                        //get current orientation of camera sensor
                        sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    }
                    catch (NullPointerException e) {
                        e.printStackTrace();
                    }

                    previewHeight = previewSize.getHeight();
                    previewWidth = previewSize.getWidth();

                    rgbBytes = new int[previewWidth * previewHeight];

                    flashSupported = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                    this.cameraId = cameraId;

                    //we've now found a usable back camera and finished setting up member variables, so don't need to keep iterating
                    return;

                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            } catch (NullPointerException e) {
                //NPE thrown when Camera2API is used but not supported on the device
                ErrorDialog.newInstance(mainActivity.getString(R.string.camera_error)).show(mainActivity.getSupportFragmentManager(), FRAGMENT_DIALOG);
            }
        }


        /**
         * Opens the camera specified by [PosenetActivity.cameraId].
         */
        private void openCamera() {
            int permissionCamera = Objects.requireNonNull(mainActivity.getApplicationContext()).checkPermission(Manifest.permission.CAMERA, Process.myPid(), Process.myUid());

            //make sure we have camera permission
            if (permissionCamera != PackageManager.PERMISSION_GRANTED) {
                //still need permission to access camera, so get it now
                requestCameraPermission();
            }

            //find and set up the camera
            setUpCameraOutputs();

            CameraManager cameraManager = (CameraManager) mainActivity.getSystemService(Context.CAMERA_SERVICE);

            try {
                // Wait for camera to open - 2.5 seconds is sufficient
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    throw new RuntimeException("Time out waiting to lock camera opening.");
                }

                Log.i(TAG, "cameraManager.openCamera()");

                if (cameraId == null) {
                    showToast("There's an issue with the camera connection.");
                    return;
                }

                //DELIBERATELY CRASH THE APP
                //int test = 5 / 0;
                cameraManager.openCamera(cameraId, new stateCallback(), backgroundHandler);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException("Interrupted while trying to lock camera opening.");
            }
            catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        /**
         * Closes the current [CameraDevice].
         */
        private void closeCamera() {
            //there is no capture session, nothing we can do
            if (captureSession == null) {
                return;
            }

            try {
                //get semaphore
                cameraOpenCloseLock.acquire();

                //close the CameraCaptureSession
                captureSession.close();
                captureSession = null;

                //close the CameraDevice
                cameraDevice.close();
                cameraDevice = null;

                //close the ImageReader
                imageReader.close();
                imageReader = null;
            }
            catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
            }

            //Java finally block is always executed whether exception occurs or not and is handled or not
            //often used for important cleanup code that MUST be executed
            finally {
                cameraOpenCloseLock.release();
            }
        }

        //ENTRY POINT OF THREAD
        @Override
        public void run() {

            //Initialize the current thread as a looper.
            //Looper.prepare();

            startBackgroundThread();


            //get the Mats created AFTER OpenCV was loaded successfully
            humanModelMat = mainActivity.getHumanModelMat();
            humanActualMat = mainActivity.getHumanActualMat();


            //these are the 3d pts we'd like to draw: essentially projecting a 3D axis magnitude 1000 onto 2D image in middle of person's chest
            testPts[0] = new Point3(1000.0, -1087.5, -918.75);
            testPts[1] = new Point3(0, 2087.5, -918.75);
            testPts[2] = new Point3(0, -1087.5, 81.25);

            testPtList.add(testPts[0]);
            testPtList.add(testPts[1]);
            testPtList.add(testPts[2]);

            /*
            //populate the 3D human model
            humanModelRaw[0] = new Point3(0.0f, 0.0f, 0.0f); //nose
            humanModelRaw[1] = new Point3(0.0f, 0.0f, 0.0f); //nose again
            humanModelRaw[2] = new Point3(-215.0f, 170.0f, -135.0f); //left eye ctr WAS -150
            humanModelRaw[3] = new Point3(215.0f, 170.0f, -135.0f); //rt eye ctr

            //humanModelRaw[3] = new Point3(450.0f, -700.0f, -600.0f); //rt shoulder
            //humanModelRaw[4] = new Point3(-450.0f, -700.0f, -600.0f); //left shoulder
             */


            //from real measured coords
            humanModelRaw[0] = new Point3(0.0f, 0.0f, 0.0f); //nose
            humanModelRaw[1] = new Point3(0.0f, 0.0f, 0.0f); //nose
            humanModelRaw[2] = new Point3(-225.0f, 318.75f, -262.5f); //left eye ctr
            humanModelRaw[3] = new Point3(225.0f, 318.75f, -262.5f); //right eye ctr WAS -150
            humanModelRaw[4] = new Point3(-871.875f, -1087.5f, -918.75f); //rt shoulder 450, -700, -600
            humanModelRaw[5] = new Point3(871.875f, -1087.5f, -918.75f); //left shoulder -450, -700, -600

            //push all of the model coordinates into the ArrayList version so they can be converted to a MatofPoint3f
            humanModelList.add(humanModelRaw[0]);
            humanModelList.add(humanModelRaw[1]);
            humanModelList.add(humanModelRaw[2]);
            humanModelList.add(humanModelRaw[3]);
            humanModelList.add(humanModelRaw[4]);
            humanModelList.add(humanModelRaw[5]);

            humanModelMat.fromList(humanModelList);

            showToast("PosenetStatsLiveFeed calling openCamera()!");
            openCamera();
        }
    }
}
