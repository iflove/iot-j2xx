package com.ftdi.j2xx.d2xx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.util.Log;

import com.ftdi.j2xx.D2xxManager;
import com.ftdi.j2xx.FT_Device;


/**
 * Created by lazy on 2019-12-05
 */
public class D2xxController implements Handler.Callback {
    private static final String TAG = "D2xxController";
    private static final int MSG_LOOP_READ_DATA = 0x001;
    private static final int MSG_READ_DATA = 0x002;
    private static final int MSG_WRITE_DATA = 0x003;
    private static final int MSG_OPENED_DEV = 0x004;
    private static final int MSG_OPENED_DEV_FAIL = 0x005;
    /*port number*/
    public static final int DEFAULT_OPEN_INDEX = 0;
    public static final String PUPPET_FRAGMENT_TAG = "com.ftdi.j2xx.d2xx.D2xxController.PuppetFragment";
    private static D2xxController d2xxController;
    private Context parentContext;

    private D2xxManager d2xxManager;
    private FT_Device ftDev = null;
    private final Object ftDevObject = new Object();

    private int devCount = -1;
    private int currentIndex = -1;
    private boolean succeeded;
    boolean uartConfigured = false;

    private Handler uiHandler;
    private D2xxEvent d2xxEvent;

    private HandlerThread readHandlerThread;
    private Handler readHandler;
    private HandlerThread writeHandlerThread;
    private Handler writeHandler;
    private boolean isReadindData;
    private byte[] readBuff;

    /*local variables*/
    /*baud rate*/
    int baudRate;
    /*8:8bit, 7: 7bit*/
    byte dataBits;
    /*1:1stop bits, 2:2 stop bits*/
    byte stopBits;
    /* 0: none, 1: odd, 2: even, 3: mark, 4: space*/
    byte parity;
    /*0:none, 1: flow control(CTS,RTS)*/
    byte flowControl;


    private D2xxController(@NonNull Context parentContext) {
        this.parentContext = parentContext;
        this.uiHandler = new Handler(parentContext.getMainLooper(), this);
        init(parentContext);
    }

    private void init(@NonNull Context parentContext) {
        try {
            d2xxManager = D2xxManager.getInstance(parentContext);
            readHandlerThread = new HandlerThread("d2xx-R", Process.THREAD_PRIORITY_BACKGROUND);
            readHandlerThread.start();
            readHandler = new Handler(readHandlerThread.getLooper(), this);

            writeHandlerThread = new HandlerThread("d2xx-W", Process.THREAD_PRIORITY_BACKGROUND);
            writeHandlerThread.start();
            writeHandler = new Handler(writeHandlerThread.getLooper(), this);
            succeeded = true;
        } catch (D2xxManager.D2xxException ex) {
            Log.e(TAG, "D2xxException" + ex.getMessage());
            succeeded = false;
        }
        /* by default it is 9600 */
        this.baudRate = 9600;
        /* default is stop bit 1 */
        this.stopBits = 1;
        /* default data bit is 8 bit */
        this.dataBits = 8;
        /* default is none */
        this.parity = 0;
        /* default flow control is is none */
        this.flowControl = 0;
    }

    public static D2xxController with(@NonNull Context context) {
        if (d2xxController == null) {
            synchronized (D2xxController.class) {
                if (d2xxController == null) {
                    d2xxController = new D2xxController(context.getApplicationContext());
                }
            }
        }
        return d2xxController;
    }

    public void setConfig(int baud, byte dataBits, byte stopBits, byte parity, byte flowControl) {
        this.baudRate = baud;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.flowControl = flowControl;
    }

    public D2xxController attachedPuppet(@NonNull FragmentActivity puppetFragmentActivity) {
        if (puppetFragmentActivity.isFinishing()) {
            return this;
        }
        FragmentManager supportFragmentManager = puppetFragmentActivity.getSupportFragmentManager();
        Fragment fragment = supportFragmentManager.findFragmentByTag(PUPPET_FRAGMENT_TAG);
        if (fragment == null) {
            PuppetFragment puppetFragment = new PuppetFragment(this);
            supportFragmentManager.beginTransaction().add(android.R.id.content, puppetFragment, PUPPET_FRAGMENT_TAG).commitAllowingStateLoss();
        }
        return this;
    }

    public void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.setPriority(500);
        this.parentContext.registerReceiver(usbReceiver, filter);
    }

    public void createDeviceList() {
        if (!isSucceeded()) {
            Log.e(TAG, "D2xx load fail");
            return;
        }
        int tempDevCount = d2xxManager.createDeviceInfoList(parentContext);
        Log.d(TAG, "createDeviceList: " + tempDevCount);
        if (tempDevCount > 0 && tempDevCount != devCount) {
            devCount = tempDevCount;
            updatePortNumber();
            return;
        }
        devCount = -1;
        currentIndex = -1;
    }

    public void write(final byte[] data) {
        if (data == null) {
            return;
        }

        if (!isSucceeded() || !isOpenedDev()) {
            return;
        }

        if (writeHandler != null) {
            Message message = writeHandler.obtainMessage();
            message.what = MSG_WRITE_DATA;
            message.obj = data;
            writeHandler.sendMessageDelayed(message, writeHandler.hasMessages(MSG_WRITE_DATA) ? 100 : 10);
        }
    }

    public void write(final String hex) {
        Log.i(TAG, "write: " + hex);
        write(hexStringToByte(hex));
    }

    private void updatePortNumber() {
        if (devCount > 0) {
            //默认打开 0
            int tmpPortNumber = DEFAULT_OPEN_INDEX;
            if (ftDev == null) {
                ftDev = d2xxManager.openByIndex(parentContext, tmpPortNumber);
            } else {
                synchronized (ftDevObject) {
                    ftDev.close();
                    ftDev = d2xxManager.openByIndex(parentContext, tmpPortNumber);
                }
            }

            uartConfigured = false;
            if (ftDev == null || !ftDevIsOpen()) {
                Log.w(TAG, "open device port(" + tmpPortNumber + ") NG, ftDev == null");
                if (uiHandler != null) {
                    Message message = uiHandler.obtainMessage(MSG_OPENED_DEV_FAIL);
                    message.obj = tmpPortNumber;
                    uiHandler.sendMessageDelayed(message, 100);
                }
                return;
            }
            currentIndex = tmpPortNumber;
            Log.i(TAG, "open device port(" + tmpPortNumber + ") OK");

            configFtDev();

            if (readHandler != null) {
                isReadindData = true;
                readHandler.sendMessageDelayed(readHandler.obtainMessage(MSG_LOOP_READ_DATA), 50);
            }
            if (uiHandler != null) {
                Message message = uiHandler.obtainMessage(MSG_OPENED_DEV);
                message.obj = currentIndex;
                uiHandler.sendMessageDelayed(message, 100);
            }
        }
    }

    private void configFtDev() {
        synchronized (ftDevObject) {
            if (ftDev == null || !ftDevIsOpen() || uartConfigured) {
                Log.w(TAG, " NG, configFtDev fail");
                return;
            }
            // configure our port
            // reset to UART mode for 232 devices
            ftDev.setBitMode((byte) 0, D2xxManager.FT_BITMODE_RESET);

            ftDev.setBaudRate(this.baudRate);

            switch (this.dataBits) {
                case 7:
                    dataBits = D2xxManager.FT_DATA_BITS_7;
                    break;
                case 8:
                    dataBits = D2xxManager.FT_DATA_BITS_8;
                    break;
                default:
                    dataBits = D2xxManager.FT_DATA_BITS_8;
                    break;
            }
            switch (this.stopBits) {
                case 1:
                    stopBits = D2xxManager.FT_STOP_BITS_1;
                    break;
                case 2:
                    stopBits = D2xxManager.FT_STOP_BITS_2;
                    break;
                default:
                    stopBits = D2xxManager.FT_STOP_BITS_1;
                    break;
            }

            switch (parity) {
                case 0:
                    parity = D2xxManager.FT_PARITY_NONE;
                    break;
                case 1:
                    parity = D2xxManager.FT_PARITY_ODD;
                    break;
                case 2:
                    parity = D2xxManager.FT_PARITY_EVEN;
                    break;
                case 3:
                    parity = D2xxManager.FT_PARITY_MARK;
                    break;
                case 4:
                    parity = D2xxManager.FT_PARITY_SPACE;
                    break;
                default:
                    parity = D2xxManager.FT_PARITY_NONE;
                    break;
            }

            ftDev.setDataCharacteristics(dataBits, stopBits, parity);

            short flowCtrlSetting;
            switch (flowControl) {
                case 0:
                    flowCtrlSetting = D2xxManager.FT_FLOW_NONE;
                    break;
                case 1:
                    flowCtrlSetting = D2xxManager.FT_FLOW_RTS_CTS;
                    break;
                case 2:
                    flowCtrlSetting = D2xxManager.FT_FLOW_DTR_DSR;
                    break;
                case 3:
                    flowCtrlSetting = D2xxManager.FT_FLOW_XON_XOFF;
                    break;
                default:
                    flowCtrlSetting = D2xxManager.FT_FLOW_NONE;
                    break;
            }

            // TODO : flow ctrl: XOFF/XOM
            // TODO : flow ctrl: XOFF/XOM
            ftDev.setFlowControl(flowCtrlSetting, (byte) 0x0b, (byte) 0x0d);

            uartConfigured = true;
            Log.i(TAG, "configFtDev: done");

        }
    }

    public void destroy() {
        if (this.parentContext != null) {
            this.parentContext.unregisterReceiver(usbReceiver);
        }
        closeDev();
    }

    private void closeDev() {
        Log.d(TAG, "closeDev: ");
        devCount = -1;
        currentIndex = -1;
        uartConfigured = false;
        isReadindData = false;

        if (readHandler != null) {
            readHandler.removeCallbacksAndMessages(null);
        }
        if (writeHandler != null) {
            writeHandler.removeCallbacksAndMessages(null);
        }
        if (isOpenedDev()) {
            synchronized (ftDevObject) {
                ftDev.close();
            }
        }
    }

    public boolean ftDevIsOpen() {
        if (ftDev != null) {
            try {
                return ftDev.isOpen();
                //hanle mIsOpen
            } catch (NullPointerException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private void onStart() {
        createDeviceList();
    }

    private void onStop() {

    }

    public boolean isSucceeded() {
        return succeeded && d2xxManager != null;
    }

    public boolean isOpenedDev() {
        if (ftDev == null) {
            return false;
        }
        synchronized (ftDevObject) {
            return ftDev != null && ftDevIsOpen();
        }
    }

    public D2xxManager getD2xxManager() {
        return d2xxManager;
    }

    public D2xxController setD2xxEvent(D2xxEvent d2xxEvent) {
        this.d2xxEvent = d2xxEvent;
        return this;
    }

    private D2xxEvent getD2xxEvent() {
        return d2xxEvent;
    }

    private static String encodeHexString(byte[] byteArray) {
        StringBuilder hexStringBuffer = new StringBuilder();
        for (byte b : byteArray) {
            hexStringBuffer.append(byteToHex(b));
        }
        return hexStringBuffer.toString().toUpperCase();
    }

    private static String byteToHex(byte num) {
        char[] hexDigits = new char[2];
        hexDigits[0] = Character.forDigit((num >> 4) & 0xF, 16);
        hexDigits[1] = Character.forDigit((num & 0xF), 16);
        return new String(hexDigits).toUpperCase();
    }

    public static byte[] hexStringToByte(String hex) {
        hex = hex.replace(" ", "");
        int len = (hex.length() / 2);
        byte[] result = new byte[len];
        char[] achar = hex.toCharArray();
        for (int i = 0; i < len; i++) {
            int pos = i * 2;
            result[i] = (byte) (toByte(achar[pos]) << 4 | toByte(achar[pos + 1]));
        }
        return result;
    }

    private static int toByte(char c) {
        return (byte) "0123456789ABCDEF".indexOf(c);
    }

    /***********USB broadcast receiver*******************************************/
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.i(TAG, "ATTACHED...");
                if (uiHandler != null) {
                    uiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            createDeviceList();
                        }
                    }, 1000);
                }

                if (getD2xxEvent() != null) {
                    getD2xxEvent().onDevAttached(context, intent);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.i(TAG, "DETACHED...");
                closeDev();
                if (getD2xxEvent() != null) {
                    getD2xxEvent().onDevDetached(context, intent);
                }
            }
        }
    };

    @Override
    public boolean handleMessage(@NonNull Message message) {
        switch (message.what) {
            case MSG_LOOP_READ_DATA: {
                if (!isReadindData) {
                    return true;
                }
                synchronized (this.ftDevObject) {
                    if (ftDev == null || !ftDevIsOpen()) {
                        return true;
                    }
                    int available = ftDev.getQueueStatus();
                    if (available > 0) {
                        //根据实际数据量来实现读取、一般响应返回同样长度指令
                        if (readBuff == null || readBuff.length != available) {
                            readBuff = new byte[available];
                        }
                        ftDev.read(readBuff, available);
                        String hexString = encodeHexString(readBuff);
                        if (uiHandler != null) {
                            Message obtainMessage = uiHandler.obtainMessage();
                            obtainMessage.what = MSG_READ_DATA;
                            obtainMessage.obj = hexString;
                            uiHandler.sendMessageDelayed(obtainMessage, 100);
                        }
                    }
                }
                if (readHandler != null) {
                    readHandler.sendMessageDelayed(readHandler.obtainMessage(MSG_LOOP_READ_DATA), 100);
                }
                return true;
            }
            case MSG_READ_DATA: {
                if (getD2xxEvent() != null) {
                    getD2xxEvent().onRead((String) message.obj);
                }
                break;
            }
            case MSG_WRITE_DATA: {
                synchronized (this.ftDevObject) {
                    if (ftDev == null || !ftDevIsOpen()) {
                        return true;
                    }
                    ftDev.setLatencyTimer((byte) 16);
                    byte[] data = (byte[]) message.obj;
                    if (data != null) {
                        ftDev.write(data, data.length);
                    }
                }
                break;
            }
            case MSG_OPENED_DEV: {
                if (getD2xxEvent() != null) {
                    getD2xxEvent().onOpenedDev((int) message.obj);
                }
                break;
            }
            case MSG_OPENED_DEV_FAIL: {
                if (getD2xxEvent() != null) {
                    getD2xxEvent().onOpenDevFail((int) message.obj);
                }
                break;
            }
            default:
                break;
        }
        return false;
    }

    public interface D2xxEvent {
        void onDevAttached(Context context, Intent intent);

        void onDevDetached(Context context, Intent intent);

        void onOpenDevFail(int port);

        void onOpenedDev(int port);

        void onRead(String hex);
    }

    public static class PuppetFragment extends Fragment {
        private D2xxController d2xxController;

        PuppetFragment(D2xxController d2xxController) {
            this.d2xxController = d2xxController;
        }

        @Override
        public void onActivityCreated(@Nullable Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            try {
                if (this.d2xxController != null) {
                    this.d2xxController.registerUsbReceiver();
                    this.d2xxController.onStart();
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onResume() {
            super.onResume();
        }

        @Override
        public void onPause() {
            super.onPause();
        }

        @Override
        public void onStop() {
            super.onStop();
            try {
                if (this.d2xxController != null) {
                    this.d2xxController.onStop();
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            try {
                if (this.d2xxController != null) {
                    this.d2xxController.destroy();
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }
}
