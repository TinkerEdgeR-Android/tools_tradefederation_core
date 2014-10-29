/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tradefed.device;

import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.EmulatorConsole;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.config.IGlobalConfiguration;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.IDeviceMonitor.DeviceLister;
import com.android.tradefed.device.IManagedTestDevice.DeviceEventResponse;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.TableFormatter;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@inheritDoc}
 */

@OptionClass(alias = "dmgr", global_namespace = false)
public class DeviceManager implements IDeviceManager {

    /** max wait time in ms for fastboot devices command to complete */
    private static final long FASTBOOT_CMD_TIMEOUT = 1 * 60 * 1000;
    /**  time to wait in ms between fastboot devices requests */
    private static final long FASTBOOT_POLL_WAIT_TIME = 5 * 1000;
    /** time to wait for device adb shell responsive connection before declaring it unavailable
     * for testing */
    private static final int CHECK_WAIT_DEVICE_AVAIL_MS = 30 * 1000;

    /** a {@link DeviceSelectionOptions} that matches any device.  Visible for testing. */
    static final IDeviceSelection ANY_DEVICE_OPTIONS = new DeviceSelectionOptions();
    private static final String NULL_DEVICE_SERIAL_PREFIX = "null-device";
    private static final String EMULATOR_SERIAL_PREFIX = "emulator";

    private DeviceMonitorMultiplexer mDvcMon = new DeviceMonitorMultiplexer();

    private boolean mIsInitialized = false;

    private ManagedDeviceList mManagedDeviceList;

    private IAndroidDebugBridge mAdbBridge;
    private ManagedDeviceListener mManagedDeviceListener;
    private boolean mFastbootEnabled;
    private Set<IFastbootListener> mFastbootListeners;
    private FastbootMonitor mFastbootMonitor;
    private boolean mIsTerminated = false;
    private IDeviceSelection mGlobalDeviceFilter;

    @Option(name="max-emulators",
            description = "the maximum number of emulators that can be allocated at one time")
    private int mNumEmulatorSupported = 1;
    @Option(name="max-null-devices",
            description = "the maximum number of no device runs that can be allocated at one time.")
    private int mNumNullDevicesSupported = 1;

    private boolean mSynchronousMode = false;

    @Option(name="device-recovery-interval",
            description = "the interval in ms between attempts to recover unavailable devices.")
    private long mDeviceRecoveryInterval = 10 * 60 * 1000;

    private DeviceRecoverer mDeviceRecoverer;

    /**
     * Creator interface for {@link IManagedTestDevice}s
     */
    interface IManagedTestDeviceFactory {
        IManagedTestDevice createDevice(IDevice stubDevice);
    }

    /**
     * The DeviceManager should be retrieved from the {@link GlobalConfiguration}
     */
    public DeviceManager() {
    }

    @Override
    public void init() {
        init(null,null);
    }

    /**
     * Initialize the device manager. This must be called once and only once before any other
     * methods are called.
     */
    @Override
    public void init(IDeviceSelection globalDeviceFilter,
            List<IDeviceMonitor> globalDeviceMonitors) {
        init(globalDeviceFilter, globalDeviceMonitors, new IManagedTestDeviceFactory() {
            @Override
            public IManagedTestDevice createDevice(IDevice idevice) {
                TestDevice testDevice = new TestDevice(idevice, new DeviceStateMonitor(
                        DeviceManager.this, idevice, mFastbootEnabled), mDvcMon);
                testDevice.setFastbootEnabled(mFastbootEnabled);
                if (idevice instanceof FastbootDevice) {
                    testDevice.setDeviceState(TestDeviceState.FASTBOOT);
                } else if (idevice instanceof StubDevice) {
                    testDevice.setDeviceState(TestDeviceState.NOT_AVAILABLE);
                }
                return testDevice;
            }
        });
    }

    /**
     * Initialize the device manager. This must be called once and only once before any other
     * methods are called.
     */
    synchronized void init(IDeviceSelection globalDeviceFilter,
            List<IDeviceMonitor> globalDeviceMonitors, IManagedTestDeviceFactory deviceFactory) {
        if (mIsInitialized) {
            throw new IllegalStateException("already initialized");
        }

        if (globalDeviceFilter == null) {
            globalDeviceFilter = getGlobalConfig().getDeviceRequirements();
        }

        if (globalDeviceMonitors == null) {
            globalDeviceMonitors = getGlobalConfig().getDeviceMonitors();
        }

        mIsInitialized = true;
        mGlobalDeviceFilter = globalDeviceFilter;
        if (globalDeviceMonitors != null) {
            mDvcMon.addMonitors(globalDeviceMonitors);
        }
        mManagedDeviceList = new ManagedDeviceList(deviceFactory);

        final FastbootHelper fastboot = new FastbootHelper(getRunUtil());
        if (fastboot.isFastbootAvailable()) {
            mFastbootListeners = Collections.synchronizedSet(new HashSet<IFastbootListener>());
            mFastbootMonitor = new FastbootMonitor();
            startFastbootMonitor();
            // don't set fastboot enabled bit until mFastbootListeners has been initialized
            mFastbootEnabled = true;
            // TODO: consider only adding fastboot devices if explicit option is set, because
            // device property selection options won't work properly with a device in fastboot
            addFastbootDevices();
        } else {
            CLog.w("Fastboot is not available.");
            mFastbootListeners = null;
            mFastbootMonitor = null;
            mFastbootEnabled = false;
        }

        // don't start adding devices until fastboot support has been established
        // TODO: Temporarily increase default timeout as workaround for syncFiles timeouts
        DdmPreferences.setTimeOut(30*1000);
        mAdbBridge = createAdbBridge();
        mManagedDeviceListener = new ManagedDeviceListener();
        // It's important to add the listener before initializing the ADB bridge to avoid a race
        // condition when detecting devices.
        mAdbBridge.addDeviceChangeListener(mManagedDeviceListener);
        if (mDvcMon != null) {
            mDvcMon.setDeviceLister(new DeviceLister() {
                @Override
                public List<DeviceDescriptor> listDevices() {
                    return listAllDevices();
                }
            });
            mDvcMon.run();
        }

        // assume "adb" is in PATH
        // TODO: make this configurable
        mAdbBridge.init(false /* client support */, "adb");
        addEmulators();
        addNullDevices();

        IMultiDeviceRecovery multiDeviceRecovery = getGlobalConfig().getMultiDeviceRecovery();
        mDeviceRecoverer = new DeviceRecoverer(multiDeviceRecovery);
        startDeviceRecoverer();
    }

    /**
     * Instruct DeviceManager whether to use background threads or not.
     * <p/>
     * Exposed to make unit tests more deterministic.
     *
     * @param syncMode
     */
    void setSynchronousMode(boolean syncMode) {
        mSynchronousMode = syncMode;
    }

    private void checkInit() {
        if (!mIsInitialized) {
            throw new IllegalStateException("DeviceManager has not been initialized");
        }
    }

    /**
     * Start fastboot monitoring.
     * <p/>
     * Exposed for unit testing.
     */
    void startFastbootMonitor() {
        mFastbootMonitor.start();
    }

    /**
     * Start device recovery.
     * <p/>
     * Exposed for unit testing.
     */
    void startDeviceRecoverer() {
        mDeviceRecoverer.start();
    }

    /**
     * Get the {@link IGlobalConfiguration} instance to use.
     * <p />
     * Exposed for unit testing.
     */
    IGlobalConfiguration getGlobalConfig() {
        return GlobalConfiguration.getInstance();
    }

    /**
     * Get the {@link RunUtil} instance to use.
     * <p/>
     * Exposed for unit testing.
     */
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }

    /**
     * Create a {@link RunUtil} instance to use.
     * <p/>
     * Exposed for unit testing.
     */
    IRunUtil createRunUtil() {
        return new RunUtil();
    }

    /**
     * Asynchronously checks if device is available, and adds to queue
     * @param device
     */
    private void checkAndAddAvailableDevice(final IManagedTestDevice testDevice) {
        if (mGlobalDeviceFilter != null && !mGlobalDeviceFilter.matches(testDevice.getIDevice())) {
            CLog.v("device %s doesn't match global filter, ignoring",
                    testDevice.getSerialNumber());
            mManagedDeviceList.handleDeviceEvent(testDevice, DeviceEvent.AVAILABLE_CHECK_IGNORED);
            return;
        }

        final String threadName = String.format("Check device %s", testDevice.getSerialNumber());
        Runnable checkRunnable = new Runnable() {
            @Override
            public void run() {
                CLog.d("checking new device %s responsiveness", testDevice.getSerialNumber());
                if (testDevice.getMonitor().waitForDeviceShell(CHECK_WAIT_DEVICE_AVAIL_MS)) {
                    DeviceEventResponse r =  mManagedDeviceList.handleDeviceEvent(testDevice,
                            DeviceEvent.AVAILABLE_CHECK_PASSED);
                    if (r.stateChanged && r.allocationState == DeviceAllocationState.Available) {
                        CLog.logAndDisplay(LogLevel.INFO, "Detected new device %s",
                                testDevice.getSerialNumber());
                    } else {
                        CLog.d("Device %s failed or ignored responsiveness check, ",
                                testDevice.getSerialNumber());
                    }
                } else {
                    DeviceEventResponse r = mManagedDeviceList.handleDeviceEvent(testDevice,
                            DeviceEvent.AVAILABLE_CHECK_FAILED);
                    if (r.stateChanged && r.allocationState == DeviceAllocationState.Unavailable) {
                        CLog.w("Device %s is unresponsive, will not be available for testing",
                                testDevice.getSerialNumber());
                    }
                }
            }
        };
        if (mSynchronousMode ) {
            checkRunnable.run();
        } else {
            Thread checkThread = new Thread(checkRunnable, threadName);
            // Device checking threads shouldn't hold the JVM open
            checkThread.setDaemon(true);
            checkThread.start();
        }
    }

    /**
     * Add placeholder objects for the max number of 'no device required' concurrent allocations
     */
    private void addNullDevices() {
        for (int i = 0; i < mNumNullDevicesSupported; i++) {
            addAvailableDevice(new NullDevice(String.format("%s-%d", NULL_DEVICE_SERIAL_PREFIX, i)));
        }
    }

    /**
     * Add placeholder objects for the max number of emulators that can be allocated
     */
    private void addEmulators() {
        // TODO currently this means 'additional emulators not already running'
        int port = 5554;
        for (int i = 0; i < mNumEmulatorSupported; i++) {
            addAvailableDevice(new StubDevice(String.format("%s-%d", EMULATOR_SERIAL_PREFIX, port),
                    true));
            port += 2;
        }
    }

    private void addAvailableDevice(IDevice stubDevice) {
        IManagedTestDevice d = mManagedDeviceList.findOrCreate(stubDevice);
        if (d != null) {
            mManagedDeviceList.handleDeviceEvent(d, DeviceEvent.FORCE_AVAILABLE);
        } else {
            CLog.e("Could not create stub device");
        }
    }

    private void addFastbootDevices() {
        final FastbootHelper fastboot = new FastbootHelper(getRunUtil());
        Set<String> serials = fastboot.getDevices();
        if (serials != null) {
            for (String serial: serials) {
                FastbootDevice d = new FastbootDevice(serial);
                if (mGlobalDeviceFilter != null && mGlobalDeviceFilter.matches(d)) {
                    addAvailableDevice(d);
                }
            }
        }
    }

    static class FastbootDevice extends StubDevice {
        FastbootDevice(String serial) {
            super(serial, false);
        }
    }

    /**
     * Creates a {@link IDeviceStateMonitor} to use.
     * <p/>
     * Exposed so unit tests can mock
     */
    IDeviceStateMonitor createStateMonitor(IDevice device) {
        return new DeviceStateMonitor(this, device, mFastbootEnabled);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice allocateDevice() {
        return allocateDevice(ANY_DEVICE_OPTIONS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice allocateDevice(IDeviceSelection options) {
        checkInit();
        return mManagedDeviceList.allocate(options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice forceAllocateDevice(String serial) {
        checkInit();
        IManagedTestDevice d = mManagedDeviceList.findOrCreate(new StubDevice(serial, false));
        if (d != null) {
            DeviceEventResponse r = d.handleAllocationEvent(DeviceEvent.FORCE_ALLOCATE_REQUEST);
            if (r.stateChanged && r.allocationState == DeviceAllocationState.Allocated) {
                return d;
            }
        }
        return null;
    }

    /**
     * Creates the {@link IAndroidDebugBridge} to use.
     * <p/>
     * Exposed so tests can mock this.
     * @returns the {@link IAndroidDebugBridge}
     */
    synchronized IAndroidDebugBridge createAdbBridge() {
        return new AndroidDebugBridgeWrapper();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void freeDevice(ITestDevice device, FreeDeviceState deviceState) {
        checkInit();
        IManagedTestDevice managedDevice = (IManagedTestDevice)device;
        // force stop capturing logcat just to be sure
        managedDevice.stopLogcat();
        IDevice ideviceToReturn = device.getIDevice();
        // don't kill emulator if it wasn't launched by launchEmulator (ie emulatorProcess is null).
        if (ideviceToReturn.isEmulator() && managedDevice.getEmulatorProcess() != null) {
            try {
                killEmulator(device);
                // emulator killed - return a stub device
                // TODO: this is a bit of a hack. Consider having DeviceManager inject a StubDevice
                // when deviceDisconnected event is received
                ideviceToReturn = new StubDevice(ideviceToReturn.getSerialNumber(), true);
                deviceState = FreeDeviceState.AVAILABLE;
            } catch (DeviceNotAvailableException e) {
                CLog.e(e);
                deviceState = FreeDeviceState.UNAVAILABLE;
            }
        }

        DeviceEventResponse r = mManagedDeviceList.handleDeviceEvent(managedDevice,
                getEventFromFree(managedDevice, deviceState));
        if (r != null && !r.stateChanged) {
            CLog.e("Device %s was in unexpected state %s when freeing", device.getSerialNumber(),
                    r.allocationState.toString());
        }
    }

    /**
     * Helper method to convert from a {@link FreeDeviceState} to a {@link DeviceEvent}
     * @param managedDevice
     */
    static DeviceEvent getEventFromFree(IManagedTestDevice managedDevice, FreeDeviceState deviceState) {
        switch (deviceState) {
            case UNRESPONSIVE:
                return DeviceEvent.FREE_UNRESPONSIVE;
            case AVAILABLE:
                return DeviceEvent.FREE_AVAILABLE;
            case UNAVAILABLE:
                if (managedDevice.getDeviceState() == TestDeviceState.NOT_AVAILABLE) {
                    return DeviceEvent.FREE_UNKNOWN;
                }
                return DeviceEvent.FREE_UNAVAILABLE;
            case IGNORE:
                return DeviceEvent.FREE_UNKNOWN;
        }
        throw new IllegalStateException("unknown FreeDeviceState");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void launchEmulator(ITestDevice device, long bootTimeout, IRunUtil runUtil,
            List<String> emulatorArgs)
            throws DeviceNotAvailableException {
        if (!device.getIDevice().isEmulator()) {
            throw new IllegalStateException(String.format("Device %s is not an emulator",
                    device.getSerialNumber()));
        }
        if (!device.getDeviceState().equals(TestDeviceState.NOT_AVAILABLE)) {
            throw new IllegalStateException(String.format(
                    "Emulator device %s is in state %s. Expected: %s", device.getSerialNumber(),
                    device.getDeviceState(), TestDeviceState.NOT_AVAILABLE));
        }
        List<String> fullArgs = new ArrayList<String>(emulatorArgs);

        try {
            CLog.i("launching emulator with %s", fullArgs.toString());
            Process p = runUtil.runCmdInBackground(fullArgs);
            // sleep a small amount to wait for process to start successfully
            getRunUtil().sleep(500);
            assertEmulatorProcessAlive(p);
            IManagedTestDevice managedDevice = (IManagedTestDevice)device;
            managedDevice.setEmulatorProcess(p);
        } catch (IOException e) {
            // TODO: is this the most appropriate exception to throw?
            throw new DeviceNotAvailableException("Failed to start emulator process", e);
        }

        device.waitForDeviceAvailable(bootTimeout);
    }

    private void assertEmulatorProcessAlive(Process p) throws DeviceNotAvailableException {
        if (!isProcessRunning(p)) {
            try {
                CLog.e("Emulator process has died . stdout: '%s', stderr: '%s'",
                        StreamUtil.getStringFromStream(p.getInputStream()),
                        StreamUtil.getStringFromStream(p.getErrorStream()));
            } catch (IOException e) {
                // ignore
            }
            throw new DeviceNotAvailableException("emulator died after launch");
        }
    }

    /**
     * Check if emulator process has died
     *
     * @param p the {@link Process} to check
     * @return true if process is running, false otherwise
     */
    private boolean isProcessRunning(Process p) {
        try {
            p.exitValue();
        } catch (IllegalThreadStateException e) {
            // expected if process is still alive
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void killEmulator(ITestDevice device) throws DeviceNotAvailableException {
        EmulatorConsole console = EmulatorConsole.getConsole(device.getIDevice());
        if (console != null) {
            console.kill();
            // check and wait for device to become not avail
            device.waitForDeviceNotAvailable(5*1000);
            // lets ensure process is killed too - fall through
        } else {
            CLog.w("Could not get emulator console for %s", device.getSerialNumber());
        }
        // lets try killing the process
        Process emulatorProcess = ((IManagedTestDevice)device).getEmulatorProcess();
        if (emulatorProcess != null) {
            emulatorProcess.destroy();
            if (isProcessRunning(emulatorProcess)) {
                CLog.w("Emulator process still running after destroy for %s",
                        device.getSerialNumber());
                forceKillProcess(emulatorProcess, device.getSerialNumber());
            }
        }
        if (!device.waitForDeviceNotAvailable(20*1000)) {
            throw new DeviceNotAvailableException(String.format("Failed to kill emulator %s",
                    device.getSerialNumber()));
        }
    }

    /**
     * Disgusting hack alert! Attempt to force kill given process.
     * Relies on implementation details. Only works on linux
     *
     * @param emulatorProcess the {@link Process} to kill
     * @param emulatorSerial the serial number of emulator. Only used for logging
     */
    private void forceKillProcess(Process emulatorProcess, String emulatorSerial) {
        if (emulatorProcess.getClass().getName().equals("java.lang.UNIXProcess")) {
            try {
                CLog.i("Attempting to force kill emulator process for %s", emulatorSerial);
                Field f = emulatorProcess.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                Integer pid = (Integer)f.get(emulatorProcess);
                if (pid != null) {
                    RunUtil.getDefault().runTimedCmd(5*1000, "kill", "-9", pid.toString());
                }
            } catch (NoSuchFieldException e) {
                CLog.d("got NoSuchFieldException when attempting to read process pid");
            } catch (IllegalAccessException e) {
                CLog.d("got IllegalAccessException when attempting to read process pid");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice connectToTcpDevice(String ipAndPort) {
        ITestDevice tcpDevice = forceAllocateDevice(ipAndPort);
        if (tcpDevice == null) {
            return null;
        }
        if (doAdbConnect(ipAndPort)) {
            try {
                tcpDevice.setRecovery(new WaitDeviceRecovery());
                tcpDevice.waitForDeviceOnline();
                return tcpDevice;
            } catch (DeviceNotAvailableException e) {
                CLog.w("Device with tcp serial %s did not come online", ipAndPort);
            }
        }
        freeDevice(tcpDevice, FreeDeviceState.IGNORE);
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice reconnectDeviceToTcp(ITestDevice usbDevice)
            throws DeviceNotAvailableException {
        CLog.i("Reconnecting device %s to adb over tcpip", usbDevice.getSerialNumber());
        ITestDevice tcpDevice = null;
        if (usbDevice instanceof IManagedTestDevice) {
            IManagedTestDevice managedUsbDevice = (IManagedTestDevice)usbDevice;
            String ipAndPort = managedUsbDevice.switchToAdbTcp();
            if (ipAndPort != null) {
                CLog.d("Device %s was switched to adb tcp on %s", usbDevice.getSerialNumber(),
                        ipAndPort);
                tcpDevice = connectToTcpDevice(ipAndPort);
                if (tcpDevice == null) {
                    // ruh roh, could not connect to device
                    // Try to re-establish connection back to usb device
                    managedUsbDevice.recoverDevice();
                }
            }
        } else {
            CLog.e("reconnectDeviceToTcp: unrecognized device type.");
        }
        return tcpDevice;
    }

    @Override
    public boolean disconnectFromTcpDevice(ITestDevice tcpDevice) {
        CLog.i("Disconnecting and freeing tcp device %s", tcpDevice.getSerialNumber());
        boolean result = false;
        try {
            result = tcpDevice.switchToAdbUsb();
        } catch (DeviceNotAvailableException e) {
            CLog.w("Failed to switch device %s to usb mode: %s", tcpDevice.getSerialNumber(),
                    e.getMessage());
        }
        freeDevice(tcpDevice, FreeDeviceState.IGNORE);
        return result;
    }

    private boolean doAdbConnect(String ipAndPort) {
        final String resultSuccess = String.format("connected to %s", ipAndPort);
        for (int i = 1; i <= 3; i++) {
            String adbConnectResult = executeGlobalAdbCommand("connect", ipAndPort);
            // runcommand "adb connect ipAndPort"
            if (adbConnectResult != null && adbConnectResult.startsWith(resultSuccess)) {
                return true;
            }
            CLog.w("Failed to connect to device on %s, attempt %d of 3. Response: %s.",
                    ipAndPort, i, adbConnectResult);
            getRunUtil().sleep(5*1000);
        }
        return false;
    }

    /**
     * Execute a adb command not targeted to a particular device eg. 'adb connect'
     *
     * @param cmdArgs
     * @return
     */
    public String executeGlobalAdbCommand(String... cmdArgs) {
        String[] fullCmd = ArrayUtil.buildArray(new String[] {"adb"}, cmdArgs);
        CommandResult result = getRunUtil().runTimedCmd(FASTBOOT_CMD_TIMEOUT, fullCmd);
        if (CommandStatus.SUCCESS.equals(result.getStatus())) {
            return result.getStdout();
        }
        CLog.w("adb %s failed", cmdArgs[0]);
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void terminate() {
        checkInit();
        if (!mIsTerminated ) {
            mIsTerminated = true;
            if (mDeviceRecoverer != null) {
                mDeviceRecoverer.terminate();
            }
            mAdbBridge.removeDeviceChangeListener(mManagedDeviceListener);
            mAdbBridge.terminate();
            if (mFastbootMonitor != null) {
                mFastbootMonitor.terminate();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void terminateHard() {
        checkInit();
        if (!mIsTerminated ) {
            for (IManagedTestDevice device : mManagedDeviceList) {
                device.setRecovery(new AbortRecovery());
            }
            mAdbBridge.disconnectBridge();
            terminate();
        }
    }

    private static class AbortRecovery implements IDeviceRecovery {

        /**
         * {@inheritDoc}
         */
        @Override
        public void recoverDevice(IDeviceStateMonitor monitor, boolean recoverUntilOnline)
                throws DeviceNotAvailableException {
            throw new DeviceNotAvailableException("aborted test session");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void recoverDeviceBootloader(IDeviceStateMonitor monitor)
                throws DeviceNotAvailableException {
            throw new DeviceNotAvailableException("aborted test session");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void recoverDeviceRecovery(IDeviceStateMonitor monitor)
                throws DeviceNotAvailableException {
            throw new DeviceNotAvailableException("aborted test session");
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public List<DeviceDescriptor> listAllDevices() {
        final List<DeviceDescriptor> serialStates = new ArrayList<DeviceDescriptor>();
        IDeviceSelection selector = getDeviceSelectionOptions();
        for (IManagedTestDevice d : mManagedDeviceList) {
            IDevice idevice = d.getIDevice();
            serialStates.add(new DeviceDescriptor(idevice.getSerialNumber(),
                    idevice instanceof StubDevice,
                    d.getAllocationState(),
                    getDisplay(selector.getDeviceProductType(idevice)),
                    getDisplay(selector.getDeviceProductVariant(idevice)),
                    getDisplay(idevice.getProperty("ro.build.version.sdk")),
                    getDisplay(idevice.getProperty("ro.build.id")),
                    getDisplay(selector.getBatteryLevel(idevice))));
        }
        return serialStates;
    }

    @Override
    public void displayDevicesInfo(PrintWriter stream) {
        ArrayList<List<String>> displayRows = new ArrayList<List<String>>();
        displayRows.add(Arrays.asList("Serial", "State", "Product", "Variant", "Build",
                "Battery"));
        List<DeviceDescriptor> deviceList = listAllDevices();
        sortDeviceList(deviceList);
        addDevicesInfo(displayRows, deviceList);
        new TableFormatter().displayTable(displayRows, stream);
    }

    /**
     * Sorts list by state, then by serial
     *
     * @VisibleForTesting
     */
    static List<DeviceDescriptor> sortDeviceList(List<DeviceDescriptor> deviceList) {

        Comparator<DeviceDescriptor> c = new Comparator<DeviceDescriptor>() {

            @Override
            public int compare(DeviceDescriptor o1, DeviceDescriptor o2) {
                if (o1.getState() != o2.getState()) {
                    // sort by state
                    return o1.getState().toString()
                            .compareTo(o2.getState().toString());
                }
                // states are equal, sort by serial
                return o1.getSerial().compareTo(o2.getSerial());
            }

        };
        Collections.sort(deviceList, c);
        return deviceList;
    }

    /**
     * Get the {@link IDeviceSelection} to use to display device info
     * <p/>
     * Exposed for unit testing.
     */
    IDeviceSelection getDeviceSelectionOptions() {
        return new DeviceSelectionOptions();
    }

    private void addDevicesInfo(List<List<String>> displayRows,
            List<DeviceDescriptor> sortedDeviceList) {
        for (DeviceDescriptor desc : sortedDeviceList) {
            if (desc.isStubDevice() &&
                    desc.getState() != DeviceAllocationState.Allocated) {
                // don't add placeholder devices
                continue;
            }
            displayRows.add(Arrays.asList(
                    desc.getSerial(),
                    desc.getState().toString(),
                    desc.getProduct(),
                    desc.getProductVariant(),
                    desc.getBuildId(),
                    desc.getBatteryLevel())
            );
        }
    }

    /**
     * Gets a displayable string for given object
     * @param o
     * @return
     */
    private String getDisplay(Object o) {
        return o == null ? "unknown" : o.toString();
    }


    /**
     * A class to listen for and act on device presence updates from ddmlib
     */
    private class ManagedDeviceListener implements IDeviceChangeListener {

        /**
         * {@inheritDoc}
         */
        @Override
        public void deviceChanged(IDevice idevice, int changeMask) {
            if ((changeMask & IDevice.CHANGE_STATE) != 0) {
                IManagedTestDevice testDevice = mManagedDeviceList.findOrCreate(idevice);
                if (testDevice == null) {
                    return;
                }
                TestDeviceState newState = TestDeviceState.getStateByDdms(idevice.getState());
                testDevice.setDeviceState(newState);
                if (newState == TestDeviceState.ONLINE) {
                    DeviceEventResponse r = mManagedDeviceList.handleDeviceEvent(testDevice,
                            DeviceEvent.STATE_CHANGE_ONLINE);
                    if (r.stateChanged && r.allocationState ==
                            DeviceAllocationState.Checking_Availability) {
                        checkAndAddAvailableDevice(testDevice);
                    }
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void deviceConnected(IDevice idevice) {
            CLog.d("Detected device connect %s, id %d", idevice.getSerialNumber(),
                    idevice.hashCode());
            IManagedTestDevice testDevice = mManagedDeviceList.findOrCreate(idevice);
            if (testDevice == null) {
                return;
            }
            // DDMS will allocate a new IDevice, so need
            // to update the TestDevice record with the new device
            CLog.d("Updating IDevice for device %s", idevice.getSerialNumber());
            testDevice.setIDevice(idevice);
            TestDeviceState newState = TestDeviceState.getStateByDdms(idevice.getState());
            testDevice.setDeviceState(newState);
            if (newState == TestDeviceState.ONLINE) {
                DeviceEventResponse r = mManagedDeviceList.handleDeviceEvent(testDevice,
                        DeviceEvent.CONNECTED_ONLINE);
                if (r.stateChanged && r.allocationState ==
                        DeviceAllocationState.Checking_Availability) {
                    checkAndAddAvailableDevice(testDevice);
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void deviceDisconnected(IDevice disconnectedDevice) {
            IManagedTestDevice d = mManagedDeviceList.find(disconnectedDevice.getSerialNumber());
            if (d != null) {
                mManagedDeviceList.handleDeviceEvent(d, DeviceEvent.DISCONNECTED);
                d.setDeviceState(TestDeviceState.NOT_AVAILABLE);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addFastbootListener(IFastbootListener listener) {
        checkInit();
        if (mFastbootEnabled) {
            mFastbootListeners.add(listener);
        } else {
            throw new UnsupportedOperationException("fastboot is not enabled");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeFastbootListener(IFastbootListener listener) {
        checkInit();
        if (mFastbootEnabled) {
            mFastbootListeners.remove(listener);
        }
    }

    private class FastbootMonitor extends Thread {

        private boolean mQuit = false;

        FastbootMonitor() {
            super("FastbootMonitor");
        }

        public void terminate() {
            mQuit = true;
            interrupt();
        }

        @Override
        public void run() {
            final FastbootHelper fastboot = new FastbootHelper(getRunUtil());
            while (!mQuit) {
                // only poll fastboot devices if there are listeners, as polling it
                // indiscriminately can cause fastboot commands to hang
                if (!mFastbootListeners.isEmpty()) {
                    Set<String> serials = fastboot.getDevices();
                    if (serials != null) {
                        mManagedDeviceList.updateFastbootStates(serials);

                        // create a copy of listeners for notification to prevent deadlocks
                        Collection<IFastbootListener> listenersCopy =
                                new ArrayList<IFastbootListener>(mFastbootListeners.size());
                        listenersCopy.addAll(mFastbootListeners);
                        for (IFastbootListener listener : listenersCopy) {
                            listener.stateUpdated();
                        }
                    }
                }
                getRunUtil().sleep(FASTBOOT_POLL_WAIT_TIME);
            }
        }
    }

    /**
     * A class for a thread which performs periodic device recovery operations.
     */
    private class DeviceRecoverer extends Thread {

        private boolean mQuit = false;
        private IMultiDeviceRecovery mMultiDeviceRecovery;

        public DeviceRecoverer(IMultiDeviceRecovery multiDeviceRecovery) {
            mMultiDeviceRecovery = multiDeviceRecovery;
        }

        @Override
        public void run() {
            while (!mQuit) {
                getRunUtil().sleep(mDeviceRecoveryInterval);
                List<DeviceDescriptor> devices = listAllDevices();
                if (mMultiDeviceRecovery != null) {
                    mMultiDeviceRecovery.recoverDevices(devices);
                }
            }
        }

        public void terminate() {
            mQuit = true;
            interrupt();
        }
    }

    @VisibleForTesting
    List<IManagedTestDevice> getDeviceList() {
        return mManagedDeviceList.getCopy();
    }

    @VisibleForTesting
    void setMaxEmulators(int numEmulators) {
        mNumEmulatorSupported = numEmulators;
    }

    @VisibleForTesting
    void setMaxNullDevices(int nullDevices) {
        mNumNullDevicesSupported = nullDevices;
    }

    @Override
    public boolean isNullDevice(String serial) {
        return serial.startsWith(NULL_DEVICE_SERIAL_PREFIX);
    }

    @Override
    public boolean isEmulator(String serial) {
        return serial.startsWith(EMULATOR_SERIAL_PREFIX);
    }

    @Override
    public void addDeviceMonitor(IDeviceMonitor mon) {
        mDvcMon.addMonitor(mon);
    }

    @Override
    public void removeDeviceMonitor(IDeviceMonitor mon) {
        mDvcMon.removeMonitor(mon);
    }
}
