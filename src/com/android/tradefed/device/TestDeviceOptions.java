/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.tradefed.config.Option;

/**
 * Container for {@link ITestDevice} {@link Option}s
 */
public class TestDeviceOptions {

    @Option(name = "enable-root", description = "enable adb root on boot.")
    private boolean mEnableAdbRoot = true;

    @Option(name = "disable-keyguard", description = "attempt to disable keyguard once complete.")
    private boolean mDisableKeyguard = true;

    @Option(name = "disable-keyguard-cmd", description = "shell command to disable keyguard.")
    private String mDisableKeyguardCmd = "input keyevent 82";

    @Option(name = "max-tmp-logcat-file", description =
        "The maximum size of a tmp logcat file, in bytes.")
    private long mMaxLogcatFileSize = 10 * 1024 * 1024;

    /**
     * @return the mEnableAdbRoot
     */
    public boolean isEnableAdbRoot() {
        return mEnableAdbRoot;
    }

    /**
     * @param mEnableAdbRoot the mEnableAdbRoot to set
     */
    public void setEnableAdbRoot(boolean enableAdbRoot) {
        mEnableAdbRoot = enableAdbRoot;
    }

    /**
     * @return the mDisableKeyguard
     */
    public boolean isDisableKeyguard() {
        return mDisableKeyguard;
    }

    /**
     * @param mDisableKeyguard the mDisableKeyguard to set
     */
    public void setDisableKeyguard(boolean disableKeyguard) {
        mDisableKeyguard = disableKeyguard;
    }

    /**
     * @return the mDisableKeyguardCmd
     */
    public String getDisableKeyguardCmd() {
        return mDisableKeyguardCmd;
    }

    /**
     * @param mDisableKeyguardCmd the mDisableKeyguardCmd to set
     */
    public void setDisableKeyguardCmd(String disableKeyguardCmd) {
        mDisableKeyguardCmd = disableKeyguardCmd;
    }

    /**
     * Get the maximum size of a tmp logcat file, in bytes.
     * <p/>
     * The actual size of the log info stored will be up to twice this number, as two logcat files
     * are stored.
     *
     * TODO: make this represent a strictly enforced total max size
     */
    public long getMaxLogcatFileSize() {
        return mMaxLogcatFileSize;
    }

    /**
     * @param maxLogcatFileSize the max logcat file size to set
     */
    public void setMaxLogcatFileSize(long maxLogcatFileSize) {
        mMaxLogcatFileSize = maxLogcatFileSize;
    }
}