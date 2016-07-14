/* Copyright 2012-2016 The Tor Project
 * See LICENSE for licensing information */

package org.torproject.collector.torperf;

import org.torproject.collector.conf.Configuration;
import org.torproject.collector.conf.ConfigurationException;
import org.torproject.collector.conf.Key;
import org.torproject.collector.main.LockFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.Stack;
import java.util.TimeZone;
import java.util.TreeMap;

/* Download possibly truncated Torperf .data and .extradata files from
 * configured sources, append them to the files we already have, and merge
 * the two files into the .tpf format. */
public class TorperfDownloader extends Thread {
  private static Logger logger = LoggerFactory.getLogger(TorperfDownloader.class);

  /** Executes the torperf module using the given configuration. */
  public static void main(Configuration config) throws ConfigurationException {
    logger.info("Starting torperf module of CollecTor.");

    // Use lock file to avoid overlapping runs
    LockFile lf = new LockFile(config.getPath(Key.LockFilePath).toString(), "torperf");
    lf.acquireLock();

    // Process Torperf files
    new TorperfDownloader(config).run();

    // Remove lock file
    lf.releaseLock();

    logger.info("Terminating torperf module of CollecTor.");
  }

  private Configuration config;

  public TorperfDownloader(Configuration config) {
    this.config = config;
  }

  private File torperfOutputDirectory = null;
  private Map<String, String> torperfSources = new HashMap<>();
  private String[] torperfFilesLines = null;
  private SimpleDateFormat dateFormat;

  @Override
  public void run() {
    try {
      startProcessing();
    } catch (ConfigurationException ce) {
      logger.error("Configuration failed: " + ce, ce);
      throw new RuntimeException(ce);
    }
  }

  private void startProcessing() throws ConfigurationException {
    this.torperfFilesLines = config.getStringArray(Key.TorperfFilesLines);
    this.torperfOutputDirectory = config.getPath(Key.TorperfOutputDirectory)
        .toFile();
    if (!this.torperfOutputDirectory.exists()) {
      this.torperfOutputDirectory.mkdirs();
    }
    this.dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    this.dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.readLastMergedTimestamps();
    for (String[] source : config.getStringArrayArray(Key.TorperfSources)) {
      torperfSources.put(source[0], source[1]);
    }
    for (String torperfFilesLine : this.torperfFilesLines) {
      this.downloadAndMergeFiles(torperfFilesLine);
    }
    this.writeLastMergedTimestamps();

    this.cleanUpRsyncDirectory();
  }

  private File torperfLastMergedFile =
      new File("stats/torperf-last-merged");

  SortedMap<String, String> lastMergedTimestamps =
      new TreeMap<String, String>();

  private void readLastMergedTimestamps() {
    if (!this.torperfLastMergedFile.exists()) {
      return;
    }
    try {
      BufferedReader br = new BufferedReader(new FileReader(
          this.torperfLastMergedFile));
      String line;
      while ((line = br.readLine()) != null) {
        String[] parts = line.split(" ");
        String fileName = null;
        String timestamp = null;
        if (parts.length == 2) {
          try {
            Double.parseDouble(parts[1]);
            fileName = parts[0];
            timestamp = parts[1];
          } catch (NumberFormatException e) {
            /* Handle below. */
          }
        }
        if (fileName == null || timestamp == null) {
          this.logger.warn("Invalid line '" + line + "' in "
              + this.torperfLastMergedFile.getAbsolutePath() + ".  "
              + "Ignoring past history of merging .data and .extradata "
              + "files.");
          this.lastMergedTimestamps.clear();
          break;
        }
        this.lastMergedTimestamps.put(fileName, timestamp);
      }
      br.close();
    } catch (IOException e) {
      this.logger.warn("Error while reading '"
          + this.torperfLastMergedFile.getAbsolutePath() + ".  Ignoring "
          + "past history of merging .data and .extradata files.");
      this.lastMergedTimestamps.clear();
    }
  }

  private void writeLastMergedTimestamps() {
    try {
      this.torperfLastMergedFile.getParentFile().mkdirs();
      BufferedWriter bw = new BufferedWriter(new FileWriter(
          this.torperfLastMergedFile));
      for (Map.Entry<String, String> e :
          this.lastMergedTimestamps.entrySet()) {
        String fileName = e.getKey();
        String timestamp = e.getValue();
        bw.write(fileName + " " + timestamp + "\n");
      }
      bw.close();
    } catch (IOException e) {
      this.logger.warn("Error while writing '"
          + this.torperfLastMergedFile.getAbsolutePath() + ".  This may "
          + "result in ignoring history of merging .data and .extradata "
          + "files in the next execution.", e);
    }
  }

  private void downloadAndMergeFiles(String torperfFilesLine) {
    String[] parts = torperfFilesLine.split(" ");
    String sourceName = parts[0];
    int fileSize = -1;
    try {
      fileSize = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      this.logger.warn("Could not parse file size in "
          + "TorperfFiles configuration line '" + torperfFilesLine
          + "'.", e);
      return;
    }

    /* Download and append the .data file. */
    String dataFileName = parts[2];
    String sourceBaseUrl = torperfSources.get(sourceName);
    String dataUrl = sourceBaseUrl + dataFileName;
    String dataOutputFileName = sourceName + "-" + dataFileName;
    File dataOutputFile = new File(torperfOutputDirectory,
        dataOutputFileName);
    boolean downloadedDataFile = this.downloadAndAppendFile(dataUrl,
        dataOutputFile, true);

    /* Download and append the .extradata file. */
    String extradataFileName = parts[3];
    String extradataUrl = sourceBaseUrl + extradataFileName;
    String extradataOutputFileName = sourceName + "-" + extradataFileName;
    File extradataOutputFile = new File(torperfOutputDirectory,
        extradataOutputFileName);
    boolean downloadedExtradataFile = this.downloadAndAppendFile(
        extradataUrl, extradataOutputFile, false);

    /* Merge both files into .tpf format. */
    if (!downloadedDataFile && !downloadedExtradataFile) {
      return;
    }
    String skipUntil = null;
    if (this.lastMergedTimestamps.containsKey(dataOutputFileName)) {
      skipUntil = this.lastMergedTimestamps.get(dataOutputFileName);
    }
    try {
      skipUntil = this.mergeFiles(dataOutputFile, extradataOutputFile,
          sourceName, fileSize, skipUntil);
    } catch (IOException e) {
      this.logger.warn("Failed merging " + dataOutputFile
          + " and " + extradataOutputFile + ".", e);
    }
    if (skipUntil != null) {
      this.lastMergedTimestamps.put(dataOutputFileName, skipUntil);
    }
  }

  private boolean downloadAndAppendFile(String urlString, File outputFile,
      boolean isDataFile) {

    /* Read an existing output file to determine which line will be the
     * first to append to it. */
    String lastTimestampLine = null;
    int linesAfterLastTimestampLine = 0;
    if (outputFile.exists()) {
      try {
        BufferedReader br = new BufferedReader(new FileReader(
            outputFile));
        String line;
        while ((line = br.readLine()) != null) {
          if (isDataFile || line.contains(" LAUNCH")) {
            lastTimestampLine = line;
            linesAfterLastTimestampLine = 0;
          } else {
            linesAfterLastTimestampLine++;
          }
        }
        br.close();
      } catch (IOException e) {
        this.logger.warn("Failed reading '"
            + outputFile.getAbsolutePath() + "' to determine the first "
            + "line to append to it.", e);
        return false;
      }
    }
    try {
      this.logger.debug("Downloading " + (isDataFile ? ".data" :
          ".extradata") + " file from '" + urlString + "' and merging it "
          + "into '" + outputFile.getAbsolutePath() + "'.");
      URL url = new URL(urlString);
      HttpURLConnection huc = (HttpURLConnection) url.openConnection();
      huc.setRequestMethod("GET");
      huc.connect();
      BufferedReader br = new BufferedReader(new InputStreamReader(
          huc.getInputStream()));
      String line;
      BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile,
          true));
      boolean copyLines = lastTimestampLine == null;
      while ((line = br.readLine()) != null) {
        if (copyLines && linesAfterLastTimestampLine == 0) {
          if (isDataFile || line.contains(" LAUNCH")) {
            lastTimestampLine = line;
          }
          bw.write(line + "\n");
        } else if (copyLines && linesAfterLastTimestampLine > 0) {
          linesAfterLastTimestampLine--;
        } else if (line.equals(lastTimestampLine)) {
          copyLines = true;
        }
      }
      bw.close();
      br.close();
      if (!copyLines) {
        this.logger.warn("The last timestamp line in '"
            + outputFile.getAbsolutePath() + "' is not contained in the "
            + "new file downloaded from '" + url + "'.  Cannot append "
            + "new lines without possibly leaving a gap.  Skipping.");
        return false;
      }
    } catch (IOException e) {
      this.logger.warn("Failed downloading and/or merging '"
          + urlString + "'.", e);
      return false;
    }
    if (lastTimestampLine == null) {
      this.logger.warn("'" + outputFile.getAbsolutePath()
          + "' doesn't contain any timestamp lines.  Unable to check "
          + "whether that file is stale or not.");
    } else {
      long lastTimestampMillis = -1L;
      if (isDataFile) {
        lastTimestampMillis = Long.parseLong(lastTimestampLine.substring(
            0, lastTimestampLine.indexOf(" "))) * 1000L;
      } else {
        lastTimestampMillis = Long.parseLong(lastTimestampLine.substring(
            lastTimestampLine.indexOf(" LAUNCH=") + " LAUNCH=".length(),
            lastTimestampLine.indexOf(".",
            lastTimestampLine.indexOf(" LAUNCH=")))) * 1000L;
      }
      if (lastTimestampMillis < System.currentTimeMillis()
          - 330L * 60L * 1000L) {
        this.logger.warn("The last timestamp in '"
            + outputFile.getAbsolutePath() + "' is more than 5:30 hours "
            + "old: " + lastTimestampMillis);
      }
    }
    return true;
  }

  private String mergeFiles(File dataFile, File extradataFile,
      String source, int fileSize, String skipUntil) throws IOException {
    SortedMap<String, String> config = new TreeMap<String, String>();
    config.put("SOURCE", source);
    config.put("FILESIZE", String.valueOf(fileSize));
    if (!dataFile.exists() || !extradataFile.exists()) {
      this.logger.warn("File " + dataFile.getAbsolutePath() + " or "
          + extradataFile.getAbsolutePath() + " is missing.");
      return null;
    }
    this.logger.debug("Merging " + dataFile.getAbsolutePath() + " and "
          + extradataFile.getAbsolutePath() + " into .tpf format.");
    BufferedReader brD = new BufferedReader(new FileReader(dataFile));
    BufferedReader brE = new BufferedReader(new FileReader(extradataFile));
    String lineD = brD.readLine();
    String lineE = brE.readLine();
    int skippedLineCount = 1;
    int skippedExtraDataCount = 1;
    String maxDataComplete = null;
    String maxUsedAt = null;
    while (lineD != null) {

      /* Parse .data line.  Every valid .data line will go into the .tpf
       * format, either with additional information from the .extradata
       * file or without it. */
      if (lineD.isEmpty()) {
        this.logger.trace("Skipping empty line " + dataFile.getName()
            + ":" + skippedLineCount++ + ".");
        lineD = brD.readLine();
        continue;
      }
      SortedMap<String, String> data = this.parseDataLine(lineD);
      if (data == null) {
        this.logger.trace("Skipping illegal line " + dataFile.getName()
            + ":" + skippedLineCount++ + " '" + lineD + "'.");
        lineD = brD.readLine();
        continue;
      }
      String dataComplete = data.get("DATACOMPLETE");
      double dataCompleteSeconds = Double.parseDouble(dataComplete);
      if (skipUntil != null && dataComplete.compareTo(skipUntil) < 0) {
        this.logger.trace("Skipping " + dataFile.getName() + ":"
            + skippedLineCount++ + " which we already processed before.");
        lineD = brD.readLine();
        continue;
      }
      maxDataComplete = dataComplete;

      /* Parse .extradata line if available and try to find the one that
       * matches the .data line. */
      SortedMap<String, String> extradata = null;
      while (lineE != null) {
        if (lineE.isEmpty()) {
          this.logger.trace("Skipping " + extradataFile.getName() + ":"
              + skippedExtraDataCount++ + " which is empty.");
          lineE = brE.readLine();
          continue;
        }
        if (lineE.startsWith("BUILDTIMEOUT_SET ")) {
          this.logger.trace("Skipping " + extradataFile.getName() + ":"
              + skippedExtraDataCount++ + " which is a BUILDTIMEOUT_SET "
              + "line.");
          lineE = brE.readLine();
          continue;
        } else if (lineE.startsWith("ok ")
            || lineE.startsWith("error ")) {
          this.logger.trace("Skipping " + extradataFile.getName() + ":"
              + skippedExtraDataCount++ + " which is in the old format.");
          lineE = brE.readLine();
          continue;
        }
        extradata = this.parseExtradataLine(lineE);
        if (extradata == null) {
          this.logger.trace("Skipping Illegal line "
              + extradataFile.getName() + ":" + skippedExtraDataCount++
              + " '" + lineE + "'.");
          lineE = brE.readLine();
          continue;
        }
        if (!extradata.containsKey("USED_AT")) {
          this.logger.trace("Skipping " + extradataFile.getName() + ":"
              + skippedExtraDataCount++ + " which doesn't contain a "
              + "USED_AT element.");
          lineE = brE.readLine();
          continue;
        }
        String usedAt = extradata.get("USED_AT");
        double usedAtSeconds = Double.parseDouble(usedAt);
        if (skipUntil != null && usedAt.compareTo(skipUntil) < 0) {
          this.logger.trace("Skipping " + extradataFile.getName() + ":"
              + skippedExtraDataCount++ + " which we already processed "
              + "before.");
          lineE = brE.readLine();
          continue;
        }
        maxUsedAt = usedAt;
        if (Math.abs(usedAtSeconds - dataCompleteSeconds) <= 1.0) {
          this.logger.debug("Merging " + extradataFile.getName() + ":"
              + skippedExtraDataCount++ + " into the current .data line.");
          lineE = brE.readLine();
          break;
        } else if (usedAtSeconds > dataCompleteSeconds) {
          this.logger.trace("Comparing " + extradataFile.getName()
              + " to the next .data line.");
          extradata = null;
          break;
        } else {
          this.logger.trace("Skipping " + extradataFile.getName() + ":"
              + skippedExtraDataCount++ + " which is too old to be "
              + "merged with " + dataFile.getName() + ":"
              + skippedLineCount + ".");
          lineE = brE.readLine();
          continue;
        }
      }

      /* Write output line to .tpf file. */
      SortedMap<String, String> keysAndValues =
          new TreeMap<String, String>();
      if (extradata != null) {
        keysAndValues.putAll(extradata);
      }
      keysAndValues.putAll(data);
      keysAndValues.putAll(config);
      this.logger.debug("Writing " + dataFile.getName() + ":"
          + skippedLineCount++ + ".");
      lineD = brD.readLine();
      try {
        this.writeTpfLine(source, fileSize, keysAndValues);
      } catch (IOException ex) {
        this.logger.warn("Error writing output line.  "
            + "Aborting to merge " + dataFile.getName() + " and "
            + extradataFile.getName() + ".", skippedExtraDataCount);
        break;
      }
    }
    brD.close();
    brE.close();
    this.writeCachedTpfLines();
    if (maxDataComplete == null) {
      return maxUsedAt;
    } else if (maxUsedAt == null) {
      return maxDataComplete;
    } else if (maxDataComplete.compareTo(maxUsedAt) > 0) {
      return maxUsedAt;
    } else {
      return maxDataComplete;
    }
  }

  private SortedMap<Integer, String> dataTimestamps;

  private SortedMap<String, String> parseDataLine(String line) {
    String[] parts = line.trim().split(" ");
    if (line.length() == 0 || parts.length < 20) {
      return null;
    }
    if (this.dataTimestamps == null) {
      this.dataTimestamps = new TreeMap<Integer, String>();
      this.dataTimestamps.put(0, "START");
      this.dataTimestamps.put(2, "SOCKET");
      this.dataTimestamps.put(4, "CONNECT");
      this.dataTimestamps.put(6, "NEGOTIATE");
      this.dataTimestamps.put(8, "REQUEST");
      this.dataTimestamps.put(10, "RESPONSE");
      this.dataTimestamps.put(12, "DATAREQUEST");
      this.dataTimestamps.put(14, "DATARESPONSE");
      this.dataTimestamps.put(16, "DATACOMPLETE");
      this.dataTimestamps.put(21, "DATAPERC10");
      this.dataTimestamps.put(23, "DATAPERC20");
      this.dataTimestamps.put(25, "DATAPERC30");
      this.dataTimestamps.put(27, "DATAPERC40");
      this.dataTimestamps.put(29, "DATAPERC50");
      this.dataTimestamps.put(31, "DATAPERC60");
      this.dataTimestamps.put(33, "DATAPERC70");
      this.dataTimestamps.put(35, "DATAPERC80");
      this.dataTimestamps.put(37, "DATAPERC90");
    }
    SortedMap<String, String> data = new TreeMap<String, String>();
    try {
      for (Map.Entry<Integer, String> e : this.dataTimestamps.entrySet()) {
        int intKey = e.getKey();
        if (parts.length > intKey + 1) {
          String key = e.getValue();
          String value = String.format("%s.%02d", parts[intKey],
              Integer.parseInt(parts[intKey + 1]) / 10000);
          data.put(key, value);
        }
      }
    } catch (NumberFormatException e) {
      return null;
    }
    data.put("WRITEBYTES", parts[18]);
    data.put("READBYTES", parts[19]);
    if (parts.length >= 21) {
      data.put("DIDTIMEOUT", parts[20]);
    }
    return data;
  }

  private SortedMap<String, String> parseExtradataLine(String line) {
    String[] parts = line.split(" ");
    SortedMap<String, String> extradata = new TreeMap<String, String>();
    String previousKey = null;
    for (String part : parts) {
      String[] keyAndValue = part.split("=", -1);
      if (keyAndValue.length == 2) {
        String key = keyAndValue[0];
        previousKey = key;
        String value = keyAndValue[1];
        if (value.contains(".") && value.lastIndexOf(".")
            == value.length() - 2) {
          /* Make sure that all floats have two trailing digits. */
          value += "0";
        }
        extradata.put(key, value);
      } else if (keyAndValue.length == 1 && previousKey != null) {
        String value = keyAndValue[0];
        if (previousKey.equals("STREAM_FAIL_REASONS")
            && (value.equals("MISC") || value.equals("EXITPOLICY")
            || value.equals("RESOURCELIMIT")
            || value.equals("RESOLVEFAILED"))) {
          extradata.put(previousKey, extradata.get(previousKey) + ":"
              + value);
        } else {
          return null;
        }
      } else {
        return null;
      }
    }
    return extradata;
  }

  private String cachedSource;

  private int cachedFileSize;

  private String cachedStartDate;

  private SortedMap<String, String> cachedTpfLines;

  private void writeTpfLine(String source, int fileSize,
      SortedMap<String, String> keysAndValues) throws IOException {
    StringBuilder sb = new StringBuilder();
    int written = 0;
    for (Map.Entry<String, String> keyAndValue :
        keysAndValues.entrySet()) {
      String key = keyAndValue.getKey();
      String value = keyAndValue.getValue();
      sb.append((written++ > 0 ? " " : "") + key + "=" + value);
    }
    String line = sb.toString();
    String startString = keysAndValues.get("START");
    long startMillis = Long.parseLong(startString.substring(0,
        startString.indexOf("."))) * 1000L;
    String startDate = dateFormat.format(startMillis);
    if (this.cachedTpfLines == null || !source.equals(this.cachedSource)
        || fileSize != this.cachedFileSize
        || !startDate.equals(this.cachedStartDate)) {
      this.writeCachedTpfLines();
      this.readTpfLinesToCache(source, fileSize, startDate);
    }
    if (!this.cachedTpfLines.containsKey(startString)
        || line.length() > this.cachedTpfLines.get(startString).length()) {
      this.cachedTpfLines.put(startString, line);
    }
  }

  private void readTpfLinesToCache(String source, int fileSize,
      String startDate) throws IOException {
    this.cachedTpfLines = new TreeMap<String, String>();
    this.cachedSource = source;
    this.cachedFileSize = fileSize;
    this.cachedStartDate = startDate;
    File tpfFile = new File(torperfOutputDirectory,
        startDate.replaceAll("-", "/") + "/"
        + source + "-" + String.valueOf(fileSize) + "-" + startDate
        + ".tpf");
    if (!tpfFile.exists()) {
      return;
    }
    BufferedReader br = new BufferedReader(new FileReader(tpfFile));
    String line;
    while ((line = br.readLine()) != null) {
      if (line.startsWith("@type ")) {
        continue;
      }
      if (line.contains("START=")) {
        String startString = line.substring(line.indexOf("START=")
            + "START=".length()).split(" ")[0];
        this.cachedTpfLines.put(startString, line);
      }
    }
    br.close();
  }

  private void writeCachedTpfLines() throws IOException {
    if (this.cachedSource == null || this.cachedFileSize == 0
        || this.cachedStartDate == null || this.cachedTpfLines == null) {
      return;
    }
    File tarballFile = new File(torperfOutputDirectory,
        this.cachedStartDate.replaceAll("-", "/")
        + "/" + this.cachedSource + "-"
        + String.valueOf(this.cachedFileSize) + "-"
        + this.cachedStartDate + ".tpf");
    File rsyncFile = new File("recent/torperf/" + tarballFile.getName());
    File[] outputFiles = new File[] { tarballFile, rsyncFile };
    for (File outputFile : outputFiles) {
      outputFile.getParentFile().mkdirs();
      BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile));
      for (String line : this.cachedTpfLines.values()) {
        bw.write("@type torperf 1.0\n");
        bw.write(line + "\n");
      }
      bw.close();
    }
    this.cachedSource = null;
    this.cachedFileSize = 0;
    this.cachedStartDate = null;
    this.cachedTpfLines = null;
  }

  /** Delete all files from the rsync directory that have not been modified
   * in the last three days. */
  public void cleanUpRsyncDirectory() {
    long cutOffMillis = System.currentTimeMillis()
        - 3L * 24L * 60L * 60L * 1000L;
    Stack<File> allFiles = new Stack<File>();
    allFiles.add(new File("recent/torperf"));
    while (!allFiles.isEmpty()) {
      File file = allFiles.pop();
      if (file.isDirectory()) {
        allFiles.addAll(Arrays.asList(file.listFiles()));
      } else if (file.lastModified() < cutOffMillis) {
        file.delete();
      }
    }
  }
}

