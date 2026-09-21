package com.pluscubed.logcat.helper;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.pluscubed.logcat.R;
import com.pluscubed.logcat.data.SavedLog;
import com.pluscubed.logcat.util.UtilLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Saved-log storage.
 *
 * <p>Saved logs live in a "matlog" folder inside the directory the user granted
 * through the system folder picker (see {@link LogStorage}). Temporary files -
 * zip staging and the attachment pieces for "send log" - live in the app cache,
 * which needs no permission at all.
 */
public class SaveLogHelper {

    public static final String TEMP_DEVICE_INFO_FILENAME = "device_info.txt";
    public static final String TEMP_LOG_FILENAME = "logcat.txt";
    public static final String TEMP_DMESG_FILENAME = "dmesg.txt";

    private static final String TEMP_ZIP_FILENAME = "logs";
    private static final int BUFFER = 0x1000; // 4K

    /** Folder created inside whatever directory the user picks. */
    public static final String SAVED_LOGS_DIR = "matlog";
    private static final String TMP_DIR = "tmp";

    private static UtilLogger log = new UtilLogger(SaveLogHelper.class);

    // ---------------------------------------------------------------- temp

    public static File getTempDirectory(Context context) {
        File tmpDir = new File(context.getCacheDir(), TMP_DIR);
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            log.e("couldn't create temp directory %s", tmpDir);
        }
        return tmpDir;
    }

    public static File saveTemporaryFile(Context context, String filename, CharSequence text, List<CharSequence> lines) {
        try {
            File tempFile = new File(getTempDirectory(context), filename);

            // specifying BUFFER gets rid of an annoying warning message
            try (PrintStream out = new PrintStream(
                    new BufferedOutputStream(new FileOutputStream(tempFile, false), BUFFER))) {
                if (text != null) { // one big string
                    out.print(text);
                } else { // multiple lines separated by newline
                    for (CharSequence line : lines) {
                        out.println(line);
                    }
                }
            }

            log.d("Saved temp file: %s", tempFile);

            return tempFile;

        } catch (IOException ex) {
            log.e(ex, "unexpected exception");
            return null;
        }
    }

    public static File saveTemporaryZipFile(Context context, String filename, List<File> files) {
        try {
            return saveZipFileAndThrow(getTempDirectory(context), filename, files);
        } catch (IOException e) {
            log.e(e, "unexpected error");
        }
        return null;
    }

    public static void cleanTemp(Context context) {
        File[] files = getTempDirectory(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    // --------------------------------------------------------- saved logs

    /**
     * The "matlog" folder inside the user-granted directory, creating it (and
     * asking for nothing - the grant already covers it) when {@code create} is
     * true.
     */
    private static DocumentFile getSavedLogsDirectory(Context context, boolean create) {
        DocumentFile picked = LogStorage.getPickedFolder(context);
        if (picked == null) {
            return null;
        }

        DocumentFile dir = picked.findFile(SAVED_LOGS_DIR);
        if (dir != null && dir.isDirectory()) {
            return dir;
        }

        if (!create || !picked.canWrite()) {
            return null;
        }
        return picked.createDirectory(SAVED_LOGS_DIR);
    }

    public static boolean hasSavedLogsFolder(Context context) {
        return getSavedLogsDirectory(context, false) != null;
    }

    /**
     * Returns false (and explains why) when the user has not granted a folder
     * yet. Callers should bail out rather than attempting to write.
     */
    public static boolean checkStorageReady(Context context) {
        if (hasSavedLogsFolder(context)) {
            return true;
        }
        Toast.makeText(context, R.string.sd_card_not_found, Toast.LENGTH_LONG).show();
        return false;
    }

    public static Uri getLogUri(Context context, String filename) {
        DocumentFile dir = getSavedLogsDirectory(context, false);
        if (dir == null) {
            return null;
        }
        DocumentFile file = dir.findFile(filename);
        return file == null ? null : file.getUri();
    }

    public static void deleteLogIfExists(Context context, String filename) {
        DocumentFile dir = getSavedLogsDirectory(context, false);
        if (dir == null) {
            return;
        }
        DocumentFile file = dir.findFile(filename);
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    public static Date getLastModifiedDate(Context context, String filename) {
        DocumentFile dir = getSavedLogsDirectory(context, false);
        if (dir != null) {
            DocumentFile file = dir.findFile(filename);
            if (file != null && file.exists()) {
                return new Date(file.lastModified());
            }
        }
        // shouldn't happen
        log.e("file last modified date not found: %s", filename);
        return new Date();
    }

    /**
     * Get all the log filenames, order by last modified descending
     */
    public static List<String> getLogFilenames(Context context) {
        DocumentFile dir = getSavedLogsDirectory(context, false);
        if (dir == null) {
            return Collections.emptyList();
        }

        List<DocumentFile> files = new ArrayList<>(Arrays.asList(dir.listFiles()));

        Collections.sort(files, (object1, object2) ->
                Long.compare(object2.lastModified(), object1.lastModified()));

        List<String> result = new ArrayList<>();
        for (DocumentFile file : files) {
            result.add(file.getName());
        }
        return result;
    }

    public static SavedLog openLog(Context context, String filename, int maxLines) {

        Uri uri = getLogUri(context, filename);

        LinkedList<String> logLines = new LinkedList<>();
        boolean truncated = false;

        if (uri == null) {
            log.e("couldn't find log file: %s", filename);
            return new SavedLog(logLines, false);
        }

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                return new SavedLog(logLines, false);
            }
            BufferedReader bufferedReader =
                    new BufferedReader(new InputStreamReader(raw), BUFFER);

            while (bufferedReader.ready()) {
                logLines.add(bufferedReader.readLine());
                if (logLines.size() > maxLines) {
                    logLines.removeFirst();
                    truncated = true;
                }
            }
        } catch (IOException ex) {
            log.e(ex, "couldn't read file");
        }

        return new SavedLog(logLines, truncated);
    }

    public static synchronized boolean saveLog(Context context, CharSequence logString, String filename) {
        return saveLog(context, null, logString, filename);
    }

    public static synchronized boolean saveLog(Context context, List<CharSequence> logLines, String filename) {
        return saveLog(context, logLines, null, filename);
    }

    private static boolean saveLog(Context context, List<CharSequence> logLines, CharSequence logString, String filename) {

        DocumentFile dir = getSavedLogsDirectory(context, true);
        if (dir == null) {
            log.e("no saved-log folder has been granted; cannot write %s", filename);
            return false;
        }

        DocumentFile file = dir.findFile(filename);
        if (file == null) {
            file = dir.createFile("text/plain", filename);
        }
        if (file == null) {
            log.e("couldn't create file %s", filename);
            return false;
        }

        try (OutputStream raw = context.getContentResolver().openOutputStream(file.getUri(), "wa")) {
            if (raw == null) {
                log.e("couldn't open output stream for %s", filename);
                return false;
            }
            // "wa" appends, which is what the recorder relies on: it flushes
            // chunks of the log to disk while recording is still going.
            try (PrintStream out = new PrintStream(new BufferedOutputStream(raw, BUFFER))) {
                if (logLines != null) {
                    for (CharSequence line : logLines) {
                        out.println(line);
                    }
                } else if (logString != null) {
                    out.print(logString);
                }
            }
            return true;
        } catch (IOException ex) {
            log.e(ex, "unexpected exception");
            return false;
        }
    }

    /**
     * Copies a saved log into the app cache so it can be attached to a share
     * intent or zipped. Sharing needs a File the FileProvider can hand out.
     */
    public static File copySavedLogToTemp(Context context, String filename) {
        Uri uri = getLogUri(context, filename);
        if (uri == null) {
            log.e("couldn't find log file: %s", filename);
            return null;
        }

        File target = new File(getTempDirectory(context), filename);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target, false), BUFFER)) {
            if (in == null) {
                return null;
            }
            copy(in, out);
            return target;
        } catch (IOException e) {
            log.e(e, "couldn't copy %s to temp", filename);
            return null;
        }
    }

    public static File saveZipFile(Context context, String filename, List<File> files) {
        DocumentFile dir = getSavedLogsDirectory(context, true);
        if (dir == null) {
            log.e("no saved-log folder has been granted; cannot write %s", filename);
            return null;
        }

        DocumentFile zipFile = dir.findFile(filename);
        if (zipFile == null) {
            zipFile = dir.createFile("application/zip", filename);
        }
        if (zipFile == null) {
            log.e("couldn't create zip %s", filename);
            return null;
        }

        try (OutputStream raw = context.getContentResolver().openOutputStream(zipFile.getUri(), "w")) {
            if (raw == null) {
                return null;
            }
            writeZip(raw, files);
        } catch (IOException e) {
            log.e(e, "unexpected error");
            return null;
        }

        // Read it back only once the output stream is closed, so the provider
        // has definitely committed the bytes.
        return copySavedLogToTemp(context, filename);
    }

    private static File saveZipFileAndThrow(File dir, String filename, List<File> files) throws IOException {
        File zipFile = new File(dir, filename);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(zipFile), BUFFER)) {
            writeZip(out, files);
        }
        return zipFile;
    }

    /**
     * Writes a complete zip (including the central directory) to {@code output}
     * without closing it - the caller owns that stream.
     */
    private static void writeZip(OutputStream output, List<File> files) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output, BUFFER));
        for (File file : files) {
            try (InputStream input = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
                ZipEntry entry = new ZipEntry(file.getName());
                zip.putNextEntry(entry);
                copy(input, zip);
                zip.closeEntry();
            }
        }
        zip.finish();
        zip.flush();
    }

    /**
     * Copies all bytes from the input stream to the output stream. Does not
     * close or flush either stream.
     *
     * @return the number of bytes copied
     */
    private static long copy(InputStream from, OutputStream to) throws IOException {
        byte[] buf = new byte[BUFFER];
        long total = 0;
        while (true) {
            int r = from.read(buf);
            if (r == -1) {
                break;
            }
            to.write(buf, 0, r);
            total += r;
        }
        return total;
    }

    public static String createLogFilename(boolean withDate) {
        if (withDate) {
            Date date = new Date();
            GregorianCalendar calendar = new GregorianCalendar();
            calendar.setTime(date);

            DecimalFormat twoDigitDecimalFormat = new DecimalFormat("00");
            DecimalFormat fourDigitDecimalFormat = new DecimalFormat("0000");

            String year = fourDigitDecimalFormat.format(calendar.get(Calendar.YEAR));
            String month = twoDigitDecimalFormat.format(calendar.get(Calendar.MONTH) + 1);
            String day = twoDigitDecimalFormat.format(calendar.get(Calendar.DAY_OF_MONTH));
            String hour = twoDigitDecimalFormat.format(calendar.get(Calendar.HOUR_OF_DAY));
            String minute = twoDigitDecimalFormat.format(calendar.get(Calendar.MINUTE));
            String second = twoDigitDecimalFormat.format(calendar.get(Calendar.SECOND));

            return TEMP_ZIP_FILENAME + "-" + year + "-" + month + "-" + day + "-" + hour + "-" + minute + "-" + second + ".zip";
        } else {
            return TEMP_ZIP_FILENAME + ".zip";
        }
    }
}
