/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.device.contentprovider;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.StreamUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.net.UrlEscapers;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handler that abstract the content provider interactions and allow to use the device side content
 * provider for different operations.
 *
 * <p>All implementation in this class should be mindful of the user currently running on the
 * device.
 */
public class ContentProviderHandler {
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_ABSOLUTE_PATH = "absolute_path";
    public static final String COLUMN_DIRECTORY = "is_directory";
    public static final String COLUMN_MIME_TYPE = "mime_type";
    public static final String COLUMN_METADATA = "metadata";
    public static final String QUERY_INFO_VALUE = "INFO";
    public static final String NO_RESULTS_STRING = "No result found.";

    // Has to be kept in sync with columns in ManagedFileContentProvider.java.
    public static final String[] COLUMNS =
            new String[] {
                COLUMN_NAME,
                COLUMN_ABSOLUTE_PATH,
                COLUMN_DIRECTORY,
                COLUMN_MIME_TYPE,
                COLUMN_METADATA
            };

    public static final String PACKAGE_NAME = "android.tradefed.contentprovider";
    public static final String CONTENT_PROVIDER_URI = "content://android.tradefed.contentprovider";
    private static final String APK_NAME = "TradefedContentProvider.apk";
    private static final String CONTENT_PROVIDER_APK_RES = "/apks/contentprovider/" + APK_NAME;
    private static final String ERROR_MESSAGE_TAG = "[ERROR]";

    private ITestDevice mDevice;
    private File mContentProviderApk = null;

    /** Constructor. */
    public ContentProviderHandler(ITestDevice device) {
        mDevice = device;
    }

    /**
     * Ensure the content provider helper apk is installed and ready to be used.
     *
     * @return True if ready to be used, False otherwise.
     */
    public boolean setUp() throws DeviceNotAvailableException {
        Set<String> packageNames = mDevice.getInstalledPackageNames();
        if (packageNames.contains(PACKAGE_NAME)) {
            return true;
        }
        if (mContentProviderApk == null) {
            try {
                mContentProviderApk = extractResourceApk();
            } catch (IOException e) {
                CLog.e(e);
                return false;
            }
        }
        // Install package for all users
        String output =
                mDevice.installPackage(
                        mContentProviderApk,
                        /** reinstall */
                        true,
                        /** grant permission */
                        true);
        if (output != null) {
            CLog.e("Something went wrong while installing the content provider apk: %s", output);
            FileUtil.deleteFile(mContentProviderApk);
            return false;
        }
        return true;
    }

    /** Clean the device from the content provider helper. */
    public void tearDown() throws DeviceNotAvailableException {
        FileUtil.deleteFile(mContentProviderApk);
        mDevice.uninstallPackage(PACKAGE_NAME);
    }

    /**
     * Content provider callback that delete a file at the URI location. File will be deleted from
     * the device content.
     *
     * @param deviceFilePath The path on the device of the file to delete.
     * @return True if successful, False otherwise
     * @throws DeviceNotAvailableException
     */
    public boolean deleteFile(String deviceFilePath) throws DeviceNotAvailableException {
        String contentUri = createEscapedContentUri(deviceFilePath);
        String deleteCommand =
                String.format(
                        "content delete --user %d --uri %s", mDevice.getCurrentUser(), contentUri);
        CommandResult deleteResult = mDevice.executeShellV2Command(deleteCommand);

        if (isSuccessful(deleteResult)) {
            return true;
        }
        CLog.e(
                "Failed to remove a file at %s using content provider. Error: '%s'",
                deviceFilePath, deleteResult.getStderr());
        return false;
    }

    /**
     * Recursively pull directory contents from device using content provider.
     *
     * @param deviceFilePath the absolute file path of the remote source
     * @param localDir the local directory to pull files into
     * @return <code>true</code> if file was pulled successfully. <code>false</code> otherwise.
     * @throws DeviceNotAvailableException if connection with device is lost and cannot be
     *     recovered.
     */
    public boolean pullDir(String deviceFilePath, File localDir)
            throws DeviceNotAvailableException {
        if (!localDir.isDirectory()) {
            CLog.e("Local path %s is not a directory", localDir.getAbsolutePath());
            return false;
        }

        String contentUri = createEscapedContentUri(deviceFilePath);
        String queryContentCommand =
                String.format(
                        "content query --user %d --uri %s", mDevice.getCurrentUser(), contentUri);

        String listCommandResult = mDevice.executeShellCommand(queryContentCommand);

        if (listCommandResult.equals(NO_RESULTS_STRING)) {
            // Empty directory.
            return true;
        }

        String[] listResult = listCommandResult.split("[\\r\\n]+");

        for (String row : listResult) {
            HashMap<String, String> columnValues = parseQueryResultRow(row);
            boolean isDirectory = Boolean.valueOf(columnValues.get(COLUMN_DIRECTORY));
            String name = columnValues.get(COLUMN_NAME);
            String path = columnValues.get(COLUMN_ABSOLUTE_PATH);

            File localChild = new File(localDir, name);
            if (isDirectory) {
                if (!localChild.mkdir()) {
                    CLog.w(
                            "Failed to create sub directory %s, aborting.",
                            localChild.getAbsolutePath());
                    return false;
                }

                if (!pullDir(path, localChild)) {
                    CLog.w("Failed to pull sub directory %s from device, aborting", path);
                    return false;
                }
            } else {
                // handle regular file
                if (!pullFile(path, localChild)) {
                    CLog.w("Failed to pull file %s from device, aborting", path);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Content provider callback that pulls a file from the URI location into a local file.
     *
     * @param deviceFilePath The path on the device where to pull the file from.
     * @param localFile The {@link File} to store the contents in. If non-empty, contents will be
     *     replaced.
     * @return True if successful, False otherwise
     * @throws DeviceNotAvailableException
     */
    public boolean pullFile(String deviceFilePath, File localFile)
            throws DeviceNotAvailableException {
        String contentUri = createEscapedContentUri(deviceFilePath);
        String pullCommand =
                String.format(
                        "content read --user %d --uri %s", mDevice.getCurrentUser(), contentUri);

        // Open the output stream to the local file.
        OutputStream localFileStream;
        try {
            localFileStream = new FileOutputStream(localFile);
        } catch (FileNotFoundException e) {
            CLog.e("Failed to open OutputStream to the local file. Error: %s", e.getMessage());
            return false;
        }

        try {
            CommandResult pullResult = mDevice.executeShellV2Command(pullCommand, localFileStream);
            if (isSuccessful(pullResult)) {
                return true;
            }

            CLog.e(
                    "Failed to pull a file at '%s' to %s using content provider. Error: '%s'",
                    deviceFilePath, localFile, pullResult.getStderr());
            return false;
        } finally {
            StreamUtil.close(localFileStream);
        }
    }

    /**
     * Content provider callback that push a file to the URI location.
     *
     * @param fileToPush The {@link File} to be pushed to the device.
     * @param deviceFilePath The path on the device where to push the file.
     * @return True if successful, False otherwise
     * @throws DeviceNotAvailableException
     * @throws IllegalArgumentException
     */
    public boolean pushFile(File fileToPush, String deviceFilePath)
            throws DeviceNotAvailableException, IllegalArgumentException {
        if (fileToPush.isDirectory()) {
            throw new IllegalArgumentException(
                    String.format("File '%s' to push is a directory.", fileToPush));
        }
        if (!fileToPush.exists()) {
            throw new IllegalArgumentException(
                    String.format("File '%s' to push does not exist.", fileToPush));
        }
        String contentUri = createEscapedContentUri(deviceFilePath);
        String pushCommand =
                String.format(
                        "content write --user %d --uri %s", mDevice.getCurrentUser(), contentUri);
        CommandResult pushResult = mDevice.executeShellV2Command(pushCommand, fileToPush);

        if (isSuccessful(pushResult)) {
            return true;
        }

        CLog.e(
                "Failed to push a file '%s' at %s using content provider. Error: '%s'",
                fileToPush, deviceFilePath, pushResult.getStderr());
        return false;
    }

    /** Returns true if {@link CommandStatus} is successful and there is no error message. */
    private boolean isSuccessful(CommandResult result) {
        if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
            return false;
        }
        String stdout = result.getStdout();
        if (stdout.contains(ERROR_MESSAGE_TAG)) {
            return false;
        }
        String stderr = result.getStderr();
        return Strings.isNullOrEmpty(stderr);
    }

    /** Helper method to extract the content provider apk. */
    private File extractResourceApk() throws IOException {
        File apkTempFile = FileUtil.createTempFile(APK_NAME, ".apk");
        InputStream apkStream =
                ContentProviderHandler.class.getResourceAsStream(CONTENT_PROVIDER_APK_RES);
        FileUtil.writeToFile(apkStream, apkTempFile);
        return apkTempFile;
    }

    /**
     * Returns the full URI string for the given device path, escaped and encoded to avoid non-URL
     * characters.
     */
    public static String createEscapedContentUri(String deviceFilePath) {
        String escapedFilePath = deviceFilePath;
        try {
            // Escape the path then encode it.
            String escaped = UrlEscapers.urlPathSegmentEscaper().escape(deviceFilePath);
            escapedFilePath = URLEncoder.encode(escaped, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            CLog.e(e);
        }
        return String.format("\"%s/%s\"", CONTENT_PROVIDER_URI, escapedFilePath);
    }

    /**
     * Parses the String output of "adb shell content query" for a single row.
     *
     * @param row The entire row representing a single file/directory returned by the "adb shell
     *     content query" command.
     * @return Key-value map of column name to column value.
     */
    @VisibleForTesting
    final HashMap<String, String> parseQueryResultRow(String row) {
        HashMap<String, String> columnValues = new HashMap<>();

        StringJoiner pattern = new StringJoiner(", ");
        for (int i = 0; i < COLUMNS.length; i++) {
            pattern.add(String.format("(%s=.*)", COLUMNS[i]));
        }

        Pattern p = Pattern.compile(pattern.toString());
        Matcher m = p.matcher(row);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String[] keyValue = m.group(i).split("=");
                if (keyValue.length == 2) {
                    columnValues.put(keyValue[0], keyValue[1]);
                }
            }
        }
        return columnValues;
    }
}
