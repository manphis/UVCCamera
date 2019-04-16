package com.example.uvctestapp;

import android.os.Handler;
import android.os.Message;
import android.os.StatFs;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.api.Camera;
import com.example.ledlibrary.CradleMiniConfig;
import com.example.uvctestapp.util.SDCard;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaVideoBufferEncoder;
import com.serenegiant.encoder.MediaAudioEncoder;
import com.serenegiant.encoder.MediaMuxerWrapper;

import com.example.ledlibrary.CradleHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "camera_mainactivity";

    public static final String KEY_AUTO_RECORD = "key_auto_record";

    private static final int UVC_ATTACHED = 1;
    private static final int UVC_CONNECTED = 2;
    private static final int UVC_DISCONNECTED = 3;

    private static final int CAM_PREVIEW_WIDTH = 1280;
    private static final int CAM_PREVIEW_HEIGHT = 720;

    private static final int MIN_FREE_SIZE_MB = 2000;

    private static final String STATE_PREVIEW = "previewing...";
    private static final String STATE_RECORD = "recording...";

    private static final int RECORD_TIME_LIMIT_SEC = 60 * 10;   // 600 min = 60 sec

    private final Object mSync = new Object();

    private TextView resolutionView, framerateView, stateView;
    private Button startRecordBtn, stopRecordBtn;
    private ImageView stateImageview;

    private FileChannel mChannel;
    private MediaMuxerWrapper mMuxer;
    private MediaVideoBufferEncoder mVideoEncoder;

    private boolean mIsRecording;
    private int videoFrameCount;
    private long lastTimeMillis;
    private long recordStartTimeMillis = 0L;
    private String SDCardPath = null;
    private boolean shouldRestartRecord = false;
    private boolean autoRecord = true;
    private long g_timestamp = 0;

    private Camera mCamera = null;

    private CradleHandler cradleHandler;

    private Handler mHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what){
                case UVC_CONNECTED:
                    setFrameCallback();
                    startPreview();
                    break;

                case UVC_DISCONNECTED:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        resolutionView = (TextView) findViewById(R.id.res_value);
        framerateView = (TextView) findViewById(R.id.fps_value);
        stateView = (TextView) findViewById(R.id.state_value);
        startRecordBtn = (Button) findViewById(R.id.start_record_btn);
        startRecordBtn.setOnClickListener(btnListener);
        stopRecordBtn = (Button) findViewById(R.id.stop_record_btn);
        stopRecordBtn.setOnClickListener(btnListener);
        stateImageview = (ImageView) findViewById(R.id.state_iv);

        resolutionView.setText(CAM_PREVIEW_WIDTH + " x " + CAM_PREVIEW_HEIGHT);

        init();
        initLED();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart:");

        mCamera = Camera.open(this, mOnCameraConnectListener);
    }

    @Override
    protected void onStop() {
        if (null != mCamera) {
            mCamera.stopCamera();

            mCamera = null;
        }

        super.onStop();
    }

    @Override
    public void onDestroy() {
        synchronized (mSync) {
            handleStopRecording();
        }

        try {
            if (null != mChannel)
                mChannel.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

        destroyLED();

        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Log.i(TAG, "input keyevent = " + event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_SOFT_LEFT && !mIsRecording) {
            startRecordBtn.performClick();
        }
        else if (event.getKeyCode() == KeyEvent.KEYCODE_SOFT_RIGHT && mIsRecording) {
            stopRecordBtn.performClick();
        }

        return super.dispatchKeyEvent(event);
    }

    private void setFrameCallback() {
        mCamera.setFrameCallback(mCallback);
    }

    private void startPreview() {
        mCamera.startCamera();

        stateView.setText(STATE_PREVIEW);
    }

    public void handleStopRecording() {
        Log.v(TAG, "handleStopRecording:mMuxer=" + mMuxer);
        mVideoEncoder = null;
        if (mMuxer != null) {
            mMuxer.stopRecording();
            mMuxer = null;
        }
        if (!shouldRestartRecord) {
            Log.i(TAG, "set_LED Light_Short_Sound_Success");
            cradleHandler.set_LED(CradleMiniConfig.Light_Short_Sound_Success);
        }
    }

    private void init() {
//        if (null != getIntent()) {
//            autoRecord = getIntent().getExtras().getBoolean(KEY_AUTO_RECORD, false);
//            Log.i(TAG, "autoRecord = " + autoRecord);
//        }

        if (autoRecord) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    handleStartRecording();
                }
            }, 3000);
        }
    }

    private void initLED() {
        Log.i(TAG, "initLED");
        cradleHandler = new CradleHandler(this);
        cradleHandler.start();
    }

    private void destroyLED() {
        cradleHandler.stop();
    }

    private void initFileChannel() {
        File file = new File("/sdcard/yuv420_data");
        boolean append = false;
        try {
            mChannel = new FileOutputStream(file, append).getChannel();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private void calculateFps() {
        if (videoFrameCount == 0) {
            lastTimeMillis = System.nanoTime() / 1000000;
            videoFrameCount++;
        } else {
            if (++videoFrameCount >= 100) {
                long diffTimeMillis = System.nanoTime() / 1000000 - lastTimeMillis;
                double fps = (double) videoFrameCount * 1000 / diffTimeMillis;
                Log.v(TAG, fps + " fps");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateFps(fps);
                    }
                });
                videoFrameCount = 0;
            }
        }
    }

    private void calculateRecordTime() {
        long currentTime = System.nanoTime() / 1000000;
        if (currentTime - recordStartTimeMillis > RECORD_TIME_LIMIT_SEC * 1000) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Log.v(TAG, "restart recorder:");
                    shouldRestartRecord = true;
                    handleStopRecording();
                }
            });
            recordStartTimeMillis = currentTime;
        }
    }

    public void test(){}

    private boolean handleStartRecording() {
        Log.v(TAG, "handleStartRecording:");
        initSDCard();
        Log.i(TAG, "SDCardPath = " + SDCardPath);
        try {
            if ((mMuxer != null)) return false;
            if (null == SDCardPath) {
                cradleHandler.set_LED(CradleMiniConfig.Light_Short_Sound_Notification);
                toastMessage("NO SDCARD");
                return false;     // not record if no extra external sdcard
            }

            if (getStorageFreeSize(SDCardPath) < MIN_FREE_SIZE_MB) {
                cradleHandler.set_LED(CradleMiniConfig.Light_Short_Sound_Notification);
                toastMessage("NO ENOUGH FREE SIZE");
                return false;     // not record if no extra external sdcard
            }
//                mMuxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
            mMuxer = new MediaMuxerWrapper(SDCardPath, ".mp4");
            // for video capturing using MediaVideoEncoder
            mVideoEncoder = new MediaVideoBufferEncoder(mMuxer, CAM_PREVIEW_WIDTH, CAM_PREVIEW_HEIGHT, mMediaEncoderListener);
            if (false) {
                // for audio capturing
                new MediaAudioEncoder(mMuxer, mMediaEncoderListener);
            }
            mMuxer.prepare();
            mMuxer.startRecording();
        } catch (final IOException e) {
            Log.e(TAG, "startCapture:", e);
            return false;
        }
        recordStartTimeMillis = System.nanoTime() / 1000000;
//        Log.i(TAG, "set_LED Light_Short_Sound_Notification");
//        cradleHandler.set_LED(CradleMiniConfig.Light_Short_Sound_Notification);   // led not blinking
        return true;
    }

    private void updateBtn() {
        Log.i(TAG, "updateBtn");
        startRecordBtn.setEnabled(!mIsRecording);
        stopRecordBtn.setEnabled(mIsRecording);

        if (mIsRecording) {
            stateView.setText(STATE_RECORD);
            stateImageview.setVisibility(View.VISIBLE);
        } else {
            stateView.setText(STATE_PREVIEW);
            stateImageview.setVisibility(View.INVISIBLE);
        }
    }

    private void updateFps(double fps) {
        String value_str = String.valueOf(fps);
        int len = value_str.length();
        if (len > 5) len = 5;
        value_str = value_str.substring(0, len);
        framerateView.setText(value_str + " fps");
    }



    private Camera.OnCameraConnectListener mOnCameraConnectListener = new Camera.OnCameraConnectListener() {

        @Override
        public void onConnect() {
            Log.i(TAG, "onConnect");
            mHandler.sendEmptyMessage(UVC_CONNECTED);
        }

        @Override
        public void onDisconnect() {
            Log.i(TAG, "onDisconnect");
        }

        @Override
        public void onError(int errorno) {
            Log.i(TAG, "error = " + errorno);
            cradleHandler.set_LED(CradleMiniConfig.Light_Short_Sound_Notification);
        }
    };

    private Camera.FrameCallback mCallback = new Camera.FrameCallback() {
        @Override
        public void onFrame(ByteBuffer frame, long timestamp) {
            calculateFps();
            long t_diff = timestamp-g_timestamp;
            if (t_diff > 60000)
                Log.i(TAG, "TS camera_timestamp_diff_2 = " + t_diff);
            g_timestamp = timestamp;

            if (mIsRecording) {
                final MediaVideoBufferEncoder videoEncoder;
                synchronized (mSync) {
                    videoEncoder = mVideoEncoder;
                }
                if (videoEncoder != null) {
                    videoEncoder.frameAvailableSoon();
                    videoEncoder.encode(frame);
                }

                calculateRecordTime();
            }
        }
    };

    private View.OnClickListener btnListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.start_record_btn:
//                    if (handleStartRecording())
//                        startRecordBtn.setEnabled(false);
                    handleStartRecording();
                    break;
                case R.id.stop_record_btn:
                    handleStopRecording();
                    break;
            }
        }
    };

    private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
        @Override
        public void onPrepared(final MediaEncoder encoder) {
            Log.v(TAG, "onPrepared:encoder=" + encoder);
            mIsRecording = true;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateBtn();
                }
            });
        }

        @Override
        public void onStopped(final MediaEncoder encoder) {
            Log.v(TAG, "onStopped:encoder=" + encoder);
            if (encoder instanceof MediaVideoBufferEncoder) {
                try {
                    mIsRecording = false;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateBtn();
                        }
                    });

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String path = encoder.getOutputPath();
                            toastMessage("file path: " + path);
                            Log.i(TAG, "file path: " + path);
                        }
                    });

                    if (shouldRestartRecord) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                handleStartRecording();
                                shouldRestartRecord = false;
                            }
                        });
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "onPrepared:", e);
                }
            }
        }
    };

    private void toastMessage(String msg) {
        Toast toast = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        toast.show();
    }

    private void initSDCard() {
        SDCardPath = null;
        SDCard.initialize(this);
        SDCardPath = SDCard.instance().getDirectory();
    }

    private long getStorageFreeSize(String path) {
        StatFs stat = new StatFs(path);
        long bytesAvailable = 0;
        bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
        long megAvailable = bytesAvailable / (1024 * 1024);
        Log.i(TAG,"Available MB : " + megAvailable);

        return megAvailable;
    }
}
