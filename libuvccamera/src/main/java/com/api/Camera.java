package com.api;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The Camera class is used to start/stop camera, and retrieve camera frames (NV21).
 *
 * 1. Call open() and input an OnCameraConnectListener object as a argument to obtain a camera instance.
 *
 * 2. After the onConnect() callback function is invoked, call setFrameCallback() with a FrameCallback object,
 * and then call startCamera() to start camera.
 *
 * 3. Then the onFrame() callback function will be invoked on every camera frame with timestamp.
 * We aim to provide 25 FPS for 1280*720 frame (NV21)
 *
 * 4. Call stopCamera() to stop camera, and onDisconnect() callback function will be invoked.
 */

public class Camera {
    private static final String TAG = "CameraAPI";

    private static final int CAM_ATTACHED = 0;
    private static final int CAM_ONDISCONNECT = 1;
    private static final int ERROR_NO_CAMERA = 10;
    private static final int ERROR_WRONG_CAMERA_VID = 11;

    private static final int CAM_PREVIEW_WIDTH = 1280;
    private static final int CAM_PREVIEW_HEIGHT = 720;

    private static final int CAM_VID = 1539;
    private static final int CAM_PID = 33073;

    private final Object mSync = new Object();
    private HandlerThread handlerThread;
    private Context mContext;
    private Handler mHandler;

    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private List<UsbDevice> usbDevices;
    private USBMonitor.UsbControlBlock mCtrlBlock = null;

    private OnCameraConnectListener mOnCameraConnectListener;
    private FrameCallback frameCallback;

    private boolean attached = false;
    private int videoFrameCount;
    private long lastTimeMillis;
    private boolean stopping = false;

    /**
     * Creates a new Camera object
     */
    public static Camera open(Context context, OnCameraConnectListener listener) {
        Log.i(TAG, "Camera.open");
        Camera camera = new Camera(context, listener);

        return camera;
    }

    /**
     * Installs a callback to be invoked for every frame
     */
    public void setFrameCallback(FrameCallback callback) {
        frameCallback = callback;
        stopping = false;
    }

    /**
     * Starts camera capturing
     */
    public void startCamera() {
        Log.i(TAG, "startCamera");
        if (null == mCtrlBlock) {
            Log.e(TAG, "mCtrlBlock null");
            return;
        }

        final UVCCamera camera = new UVCCamera();
        camera.open(mCtrlBlock);
        Log.i(TAG, "supportedSize:" + camera.getSupportedSize());
        try {
            camera.setPreviewSize(CAM_PREVIEW_WIDTH, CAM_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
        } catch (final IllegalArgumentException e) {
            try {
                // fallback to YUV mode
                camera.setPreviewSize(CAM_PREVIEW_WIDTH, CAM_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
            } catch (final IllegalArgumentException e1) {
                camera.destroy();
                return;
            }
        }

        camera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
        camera.startPreview();

        synchronized (mSync) {
            mUVCCamera = camera;
        }
    }

    /**
     * Stops camera capturing
     */
    public void stopCamera() {
        Log.i(TAG, "stopCamera");
        mUSBMonitor.unregister();

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                _stopCapture();
            }
        });

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                _stopPreview();
            }
        }, 300);
    }

    private void _stopCapture() {
        Log.i(TAG, "_stopCapture()");
        synchronized (mSync) {
            if (mUVCCamera != null)
                mUVCCamera.stopCapture();
        }
    }

    private void _stopPreview() {
        Log.i(TAG, "_stopPreview()");
        synchronized (mSync) {
            stopping = true;
            if (mUVCCamera != null)
                mUVCCamera.stopPreview();

//            mUSBMonitor.unregister();

            if (mUVCCamera != null) {
                mUVCCamera.destroy();
                mUVCCamera = null;
            }
            if (mUSBMonitor != null) {
                mUSBMonitor.destroy();
                mUSBMonitor = null;
            }

            if (null != handlerThread) {
                handlerThread.quit();
                handlerThread.interrupt();
            }
        }
    }

    public void setPreviewDisplay(Surface surface) {
        if (null != mUVCCamera)
            mUVCCamera.setPreviewDisplay(surface);
    }


    public interface OnCameraConnectListener {
        /**
         * Called after camera opend
         */
        public void onConnect();
        /**
         * Called when camera removed or its power off (this callback is called after camera closing)
         */
        public void onDisconnect();

        public void onError(int errorno);
    }

    /**
     * Callback interface used to deliver copies of frames.
     */
    public interface FrameCallback {
        /**
         * Called as frame available
         * frame format: NV21
         * frame resolution: 1280*720
         * frame rate: 25 fps
         */
        void onFrame(ByteBuffer data, long timestamp);
    };


    /**
     * private function
     */
    private Camera(Context context, OnCameraConnectListener listener) {
        mContext = context;
        mOnCameraConnectListener = listener;
        mUSBMonitor = new USBMonitor(context, mOnDeviceConnectListener);
        synchronized (mSync) {
            if (mUSBMonitor != null) {
                mUSBMonitor.register();
            }
        }

        handlerThread = new HandlerThread("CameraHandlerThread");
        handlerThread.start();
        startHandlerThread();

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "post runnable");
                if (!attached) {
                    Log.i(TAG, "not attached");
                    listener.onError(ERROR_NO_CAMERA);
                }
            }
            }, 3000);
    }

    private void startHandlerThread() {
        mHandler = new Handler(handlerThread.getLooper()){
            public void handleMessage(Message msg){
                super.handleMessage(msg);
                switch(msg.what){
                    case CAM_ATTACHED:
                        if (!attached) {
                            updateDevices();
                            connectUSB();
                            attached = true;
                        }
                        break;
                    case CAM_ONDISCONNECT:
                        close();
                        break;
                }
            }
        };
    }

    private void updateDevices() {
        final List<DeviceFilter> filter = DeviceFilter.getDeviceFilters(mContext, com.serenegiant.uvccamera.R.xml.device_filter);
        usbDevices = mUSBMonitor.getDeviceList(filter.get(0));
        Log.i(TAG, "number of usb devices = " + usbDevices.size());
    }

    private void connectUSB() {
        Log.i(TAG, "connectUSB");
        mUSBMonitor.processConnect(usbDevices.get(0));
    }

    private void close() {
        synchronized (mSync) {
            if (mUVCCamera != null) {
                mUVCCamera.close();
            }
        }
    }

    private void calculateFps() {
        if (videoFrameCount == 0) {
            lastTimeMillis = System.nanoTime() / 1000000;
            videoFrameCount++;
        } else {
            if (++videoFrameCount >= 100) {
                long diffTimeMillis = System.nanoTime() / 1000000 - lastTimeMillis;
                Log.v(TAG, (double) videoFrameCount * 1000 / diffTimeMillis + " fps");
                videoFrameCount = 0;
            }
        }
    }

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Log.v(TAG, "onAttach:");
            mHandler.sendEmptyMessage(CAM_ATTACHED);
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.v(TAG, "onConnect");
            synchronized (mSync) {
                if (mUVCCamera != null) {
                    mUVCCamera.destroy();
                }
            }

            Log.i(TAG, "camera VID = " + ctrlBlock.getVenderId() + " PID = " + ctrlBlock.getProductId());
            if (CAM_VID == ctrlBlock.getVenderId() && CAM_PID == ctrlBlock.getProductId()) {
                mCtrlBlock = ctrlBlock;
                mOnCameraConnectListener.onConnect();
            } else {
                mCtrlBlock = null;
                mOnCameraConnectListener.onError(ERROR_WRONG_CAMERA_VID);
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            Log.v(TAG, "onDisconnect:");
            mHandler.sendEmptyMessage(CAM_ONDISCONNECT);
            mOnCameraConnectListener.onDisconnect();
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Log.v(TAG, "onDettach:");
            attached = false;
        }

        @Override
        public void onCancel(final UsbDevice device) {
        }
    };

    private final IFrameCallback mIFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame, long timestamp) {
//            Log.i(TAG, "onFrame timestamp = " + timestamp);
//            calculateFps();
//            synchronized (mSync) {
            if (!stopping) {
                if (null != frameCallback) {
                    frameCallback.onFrame(frame, timestamp);
                }
            }
        }
    };
}
