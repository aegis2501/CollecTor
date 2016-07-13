/* Copyright 2010--2016 The Tor Project
 * See LICENSE for licensing information */

package org.torproject.collector.relaydescs;

import org.torproject.collector.conf.Configuration;
import org.torproject.collector.conf.ConfigurationException;
import org.torproject.collector.conf.Key;
import org.torproject.collector.main.LockFile;
import org.torproject.descriptor.DescriptorParseException;
import org.torproject.descriptor.DescriptorParser;
import org.torproject.descriptor.DescriptorSourceFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TimeZone;
import java.util.TreeMap;

public class ArchiveWriter extends Thread {

  private static Logger logger = LoggerFactory.getLogger(ArchiveWriter.class);

  private Configuration config;

  private long now = System.currentTimeMillis();
  private String outputDirectory;
  private String rsyncCatString;
  private DescriptorParser descriptorParser;
  private int storedConsensusesCounter = 0;
  private int storedMicrodescConsensusesCounter = 0;
  private int storedVotesCounter = 0;
  private int storedCertsCounter = 0;
  private int storedServerDescriptorsCounter = 0;
  private int storedExtraInfoDescriptorsCounter = 0;
  private int storedMicrodescriptorsCounter = 0;

  private SortedMap<Long, SortedSet<String>> storedConsensuses =
      new TreeMap<Long, SortedSet<String>>();
  private SortedMap<Long, SortedSet<String>> storedMicrodescConsensuses =
      new TreeMap<Long, SortedSet<String>>();
  private SortedMap<Long, Integer> expectedVotes =
      new TreeMap<Long, Integer>();
  private SortedMap<Long, SortedMap<String, SortedSet<String>>>
      storedVotes =
      new TreeMap<Long, SortedMap<String, SortedSet<String>>>();
  private SortedMap<Long, Map<String, String>> storedServerDescriptors =
      new TreeMap<Long, Map<String, String>>();
  private SortedMap<Long, Set<String>> storedExtraInfoDescriptors =
      new TreeMap<Long, Set<String>>();
  private SortedMap<Long, Set<String>> storedMicrodescriptors =
      new TreeMap<Long, Set<String>>();

  private File storedServerDescriptorsFile;
  private File storedExtraInfoDescriptorsFile;
  private File storedMicrodescriptorsFile;

  private static final byte[] CONSENSUS_ANNOTATION =
      "@type network-status-consensus-3 1.0\n".getBytes();

  private static final byte[] MICRODESCCONSENSUS_ANNOTATION =
      "@type network-status-microdesc-consensus-3 1.0\n".getBytes();

  private static final byte[] VOTE_ANNOTATION =
      "@type network-status-vote-3 1.0\n".getBytes();

  private static final byte[] CERTIFICATE_ANNOTATION =
      "@type dir-key-certificate-3 1.0\n".getBytes();

  private static final byte[] SERVER_DESCRIPTOR_ANNOTATION =
      "@type server-descriptor 1.0\n".getBytes();

  private static final byte[] EXTRA_INFO_ANNOTATION =
      "@type extra-info 1.0\n".getBytes();

  private static final byte[] MICRODESCRIPTOR_ANNOTATION =
      "@type microdescriptor 1.0\n".getBytes();

  private StringBuilder intermediateStats = new StringBuilder();

  private static Path recentPath;
  private static String recentPathName;
  private static final String RELAY_DESCRIPTORS = "relay-descriptors";
  private static final String MICRO = "micro";
  private static final String CONSENSUS_MICRODESC = "consensus-microdesc";
  private static final String MICRODESC = "microdesc";
  private static final String MICRODESCS = "microdescs";

  @SuppressWarnings("checkstyle:javadocmethod")
  public static void main(Configuration config) throws ConfigurationException {

    logger.info("Starting relay-descriptors module of CollecTor.");

    // Use lock file to avoid overlapping runs
    LockFile lf = new LockFile(config.getPath(Key.LockFilePath).toString(), RELAY_DESCRIPTORS);
    lf.acquireLock();

    recentPath = config.getPath(Key.RecentPath);
    recentPathName = recentPath.toString();

    // Import/download relay descriptors from the various sources
    new ArchiveWriter(config).run();

    new ReferenceChecker(
        recentPath.toFile(),
        new File(config.getPath(Key.StatsPath).toFile(), "references"),
        new File(config.getPath(Key.StatsPath).toFile(), "references-history")).check();

    // Remove lock file
    lf.releaseLock();

    logger.info("Terminating relay-descriptors module of CollecTor.");
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public ArchiveWriter(Configuration config) throws ConfigurationException {
    this.config = config;
    storedServerDescriptorsFile =
        new File(config.getPath(Key.StatsPath).toFile(), "stored-server-descriptors");
    storedExtraInfoDescriptorsFile =
        new File(config.getPath(Key.StatsPath).toFile(), "stored-extra-info-descriptors");
    storedMicrodescriptorsFile =
        new File(config.getPath(Key.StatsPath).toFile(), "stored-microdescriptors");
  }

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

    File statsDirectory = new File("stats");
    this.outputDirectory = config.getPath(Key.DirectoryArchivesOutputDirectory).toString();
    SimpleDateFormat rsyncCatFormat = new SimpleDateFormat(
        "yyyy-MM-dd-HH-mm-ss");
    rsyncCatFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.rsyncCatString = rsyncCatFormat.format(
        System.currentTimeMillis());
    this.descriptorParser =
        DescriptorSourceFactory.createDescriptorParser();

    this.loadDescriptorDigests();

    // Prepare relay descriptor parser
    RelayDescriptorParser rdp = new RelayDescriptorParser(this);

    RelayDescriptorDownloader rdd = null;
    if (config.getBool(Key.DownloadRelayDescriptors)) {
      String[] dirSources =
          config.getStringArray(Key.DirectoryAuthoritiesAddresses);
      rdd = new RelayDescriptorDownloader(rdp, dirSources,
          config.getStringArray(Key.DirectoryAuthoritiesFingerprintsForVotes),
          config.getBool(Key.DownloadCurrentConsensus),
          config.getBool(Key.DownloadCurrentMicrodescConsensus),
          config.getBool(Key.DownloadCurrentVotes),
          config.getBool(Key.DownloadMissingServerDescriptors),
          config.getBool(Key.DownloadMissingExtraInfoDescriptors),
          config.getBool(Key.DownloadMissingMicrodescriptors),
          config.getBool(Key.DownloadAllServerDescriptors),
          config.getBool(Key.DownloadAllExtraInfoDescriptors),
          config.getBool(Key.CompressRelayDescriptorDownloads));
      rdp.setRelayDescriptorDownloader(rdd);
    }
    if (config.getBool(Key.ImportCachedRelayDescriptors)) {
      new CachedRelayDescriptorReader(rdp,
          config.getStringArray(Key.CachedRelayDescriptorsDirectories), statsDirectory);
      this.intermediateStats("importing relay descriptors from local "
          + "Tor data directories");
    }
    if (config.getBool(Key.ImportDirectoryArchives)) {
      new ArchiveReader(rdp,
                        config.getPath(Key.DirectoryArchivesDirectory).toFile(),
          statsDirectory,
          config.getBool(Key.KeepDirectoryArchiveImportHistory));
      this.intermediateStats("importing relay descriptors from local "
          + "directory");
    }
    if (rdd != null) {
      rdd.downloadDescriptors();
      rdd.writeFile();
      rdd = null;
      this.intermediateStats("downloading relay descriptors from the "
          + "directory authorities");
    }

    this.checkMissingDescriptors();

    this.checkStaledescriptors();

    this.cleanUpRsyncDirectory();

    this.saveDescriptorDigests();
  }

  private void loadDescriptorDigests() {
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    try {
      if (this.storedServerDescriptorsFile.exists()) {
        BufferedReader br = new BufferedReader(new FileReader(
            this.storedServerDescriptorsFile));
        String line;
        while ((line = br.readLine()) != null) {
          String[] parts = line.split(",");
          if (parts.length != 3) {
            this.logger.warn("Could not load server descriptor "
                + "digests because of illegal line '" + line + "'.  We "
                + "might not be able to correctly check descriptors for "
                + "completeness.");
            break;
          }
          long published = dateTimeFormat.parse(parts[0]).getTime();
          if (published < this.now - 48L * 60L * 60L * 1000L) {
            continue;
          }
          if (!this.storedServerDescriptors.containsKey(published)) {
            this.storedServerDescriptors.put(published,
                new HashMap<String, String>());
          }
          String serverDescriptorDigest = parts[1];
          String extraInfoDescriptorDigest = parts[2].equals("NA") ? null
              : parts[2];
          this.storedServerDescriptors.get(published).put(
              serverDescriptorDigest, extraInfoDescriptorDigest);
        }
        br.close();
      }
      if (this.storedExtraInfoDescriptorsFile.exists()) {
        BufferedReader br = new BufferedReader(new FileReader(
            this.storedExtraInfoDescriptorsFile));
        String line;
        while ((line = br.readLine()) != null) {
          String[] parts = line.split(",");
          if (parts.length != 2) {
            this.logger.warn("Could not load extra-info descriptor "
                + "digests because of illegal line '" + line + "'.  We "
                + "might not be able to correctly check descriptors for "
                + "completeness.");
            break;
          }
          long published = dateTimeFormat.parse(parts[0]).getTime();
          if (published < this.now - 48L * 60L * 60L * 1000L) {
            continue;
          }
          if (!this.storedExtraInfoDescriptors.containsKey(published)) {
            this.storedExtraInfoDescriptors.put(published,
                new HashSet<String>());
          }
          String extraInfoDescriptorDigest = parts[1];
          this.storedExtraInfoDescriptors.get(published).add(
              extraInfoDescriptorDigest);
        }
        br.close();
      }
      if (this.storedMicrodescriptorsFile.exists()) {
        BufferedReader br = new BufferedReader(new FileReader(
            this.storedMicrodescriptorsFile));
        String line;
        while ((line = br.readLine()) != null) {
          String[] parts = line.split(",");
          if (parts.length != 2) {
            this.logger.warn("Could not load microdescriptor digests "
                + "because of illegal line '" + line + "'.  We might not "
                + "be able to correctly check descriptors for "
                + "completeness.");
            break;
          }
          long validAfter = dateTimeFormat.parse(parts[0]).getTime();
          if (validAfter < this.now - 40L * 24L * 60L * 60L * 1000L) {
            continue;
          }
          if (!this.storedMicrodescriptors.containsKey(validAfter)) {
            this.storedMicrodescriptors.put(validAfter,
                new HashSet<String>());
          }
          String microdescriptorDigest = parts[1];
          this.storedMicrodescriptors.get(validAfter).add(
              microdescriptorDigest);
        }
        br.close();
      }
    } catch (ParseException e) {
      this.logger.warn("Could not load descriptor "
          + "digests.  We might not be able to correctly check "
          + "descriptors for completeness.", e);
    } catch (IOException e) {
      this.logger.warn("Could not load descriptor "
          + "digests.  We might not be able to correctly check "
          + "descriptors for completeness.", e);
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void intermediateStats(String event) {
    intermediateStats.append("While " + event + ", we stored "
        + this.storedConsensusesCounter + " consensus(es), "
        + this.storedMicrodescConsensusesCounter + " microdesc "
        + "consensus(es), " + this.storedVotesCounter + " vote(s), "
        + this.storedCertsCounter + " certificate(s), "
        + this.storedServerDescriptorsCounter + " server descriptor(s), "
        + this.storedExtraInfoDescriptorsCounter + " extra-info "
        + "descriptor(s), and " + this.storedMicrodescriptorsCounter
        + " microdescriptor(s) to disk.\n");
    this.storedConsensusesCounter = 0;
    this.storedMicrodescConsensusesCounter = 0;
    this.storedVotesCounter = 0;
    this.storedCertsCounter = 0;
    this.storedServerDescriptorsCounter = 0;
    this.storedExtraInfoDescriptorsCounter = 0;
    this.storedMicrodescriptorsCounter = 0;
  }

  private void checkMissingDescriptors() {
    StringBuilder sb = new StringBuilder("Finished writing relay "
        + "descriptors to disk.\n");
    sb.append(intermediateStats.toString());
    sb.append("Statistics on the completeness of written relay "
        + "descriptors:");
    SimpleDateFormat dateTimeFormat =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    Map<String, String> knownServerDescriptors =
        new HashMap<String, String>();
    for (Map<String, String> descriptors :
        this.storedServerDescriptors.values()) {
      knownServerDescriptors.putAll(descriptors);
    }
    Set<String> knownExtraInfoDescriptors = new HashSet<String>();
    for (Set<String> descriptors :
        this.storedExtraInfoDescriptors.values()) {
      knownExtraInfoDescriptors.addAll(descriptors);
    }
    Set<String> knownMicrodescriptors = new HashSet<String>();
    for (Set<String> descriptors : this.storedMicrodescriptors.values()) {
      knownMicrodescriptors.addAll(descriptors);
    }
    boolean missingDescriptors = false;
    boolean missingVotes = false;
    boolean missingMicrodescConsensus = false;
    for (Map.Entry<Long, SortedSet<String>> c :
        this.storedConsensuses.entrySet()) {
      long validAfterMillis = c.getKey();
      String validAfterTime = dateTimeFormat.format(validAfterMillis);
      int foundVotes = 0;
      if (this.storedVotes.containsKey(validAfterMillis)) {
        foundVotes = this.storedVotes.get(validAfterMillis).size();
        for (Map.Entry<String, SortedSet<String>> v :
            this.storedVotes.get(validAfterMillis).entrySet()) {
          int voteFoundServerDescs = 0;
          int voteAllServerDescs = 0;
          int voteFoundExtraInfos = 0;
          int voteAllExtraInfos = 0;
          for (String serverDescriptorDigest : v.getValue()) {
            voteAllServerDescs++;
            if (knownServerDescriptors.containsKey(
                serverDescriptorDigest)) {
              voteFoundServerDescs++;
              if (knownServerDescriptors.get(serverDescriptorDigest)
                  != null) {
                String extraInfoDescriptorDigest =
                    knownServerDescriptors.get(serverDescriptorDigest);
                voteAllExtraInfos++;
                if (knownExtraInfoDescriptors.contains(
                    extraInfoDescriptorDigest)) {
                  voteFoundExtraInfos++;
                }
              }
            }
          }
          sb.append("\nV, " + validAfterTime);
          if (voteAllServerDescs > 0) {
            sb.append(String.format(", %d/%d S (%.1f%%)",
                voteFoundServerDescs, voteAllServerDescs,
                100.0D * (double) voteFoundServerDescs
                / (double) voteAllServerDescs));
          } else {
            sb.append(", 0/0 S");
          }
          if (voteAllExtraInfos > 0) {
            sb.append(String.format(", %d/%d E (%.1f%%)",
                voteFoundExtraInfos, voteAllExtraInfos,
                100.0D * (double) voteFoundExtraInfos
                / (double) voteAllExtraInfos));
          } else {
            sb.append(", 0/0 E");
          }
          String fingerprint = v.getKey();
          /* Ignore turtles when warning about missing descriptors. */
          if (!fingerprint.equalsIgnoreCase(
              "27B6B5996C426270A5C95488AA5BCEB6BCC86956")
              && (voteFoundServerDescs * 1000 < voteAllServerDescs * 995
              || voteFoundExtraInfos * 1000 < voteAllExtraInfos * 995)) {
            missingDescriptors = true;
          }
        }
      }
      int foundServerDescs = 0;
      int allServerDescs = 0;
      int foundExtraInfos = 0;
      int allExtraInfos = 0;
      int foundMicrodescriptors = 0;
      int allMicrodescriptors = 0;
      for (String serverDescriptorDigest : c.getValue()) {
        allServerDescs++;
        if (knownServerDescriptors.containsKey(
            serverDescriptorDigest)) {
          foundServerDescs++;
          if (knownServerDescriptors.get(
              serverDescriptorDigest) != null) {
            allExtraInfos++;
            String extraInfoDescriptorDigest =
                knownServerDescriptors.get(serverDescriptorDigest);
            if (knownExtraInfoDescriptors.contains(
                extraInfoDescriptorDigest)) {
              foundExtraInfos++;
            }
          }
        }
      }
      sb.append("\nC, " + validAfterTime);
      int allVotes = this.expectedVotes.containsKey(validAfterMillis)
          ? this.expectedVotes.get(validAfterMillis) : 0;
      if (allVotes > 0) {
        sb.append(String.format(", %d/%d V (%.1f%%)", foundVotes, allVotes,
            100.0D * (double) foundVotes / (double) allVotes));
      } else {
        sb.append(", 0/0 V");
      }
      if (allServerDescs > 0) {
        sb.append(String.format(", %d/%d S (%.1f%%)", foundServerDescs,
            allServerDescs, 100.0D * (double) foundServerDescs
            / (double) allServerDescs));
      } else {
        sb.append(", 0/0 S");
      }
      if (allExtraInfos > 0) {
        sb.append(String.format(", %d/%d E (%.1f%%)", foundExtraInfos,
            allExtraInfos, 100.0D * (double) foundExtraInfos
            / (double) allExtraInfos));
      } else {
        sb.append(", 0/0 E");
      }
      if (this.storedMicrodescConsensuses.containsKey(validAfterMillis)) {
        for (String microdescriptorDigest :
            this.storedMicrodescConsensuses.get(validAfterMillis)) {
          allMicrodescriptors++;
          if (knownMicrodescriptors.contains(microdescriptorDigest)) {
            foundMicrodescriptors++;
          }
        }
        sb.append("\nM, " + validAfterTime);
        if (allMicrodescriptors > 0) {
          sb.append(String.format(", %d/%d M (%.1f%%)",
              foundMicrodescriptors, allMicrodescriptors,
              100.0D * (double) foundMicrodescriptors
              / (double) allMicrodescriptors));
        } else {
          sb.append(", 0/0 M");
        }
      } else {
        missingMicrodescConsensus = true;
      }
      if (foundServerDescs * 1000 < allServerDescs * 995
          || foundExtraInfos * 1000 < allExtraInfos * 995
          || foundMicrodescriptors * 1000 < allMicrodescriptors * 995) {
        missingDescriptors = true;
      }
      if (foundVotes < allVotes) {
        missingVotes = true;
      }
    }
    this.logger.info(sb.toString());
    if (missingDescriptors) {
      this.logger.debug("We are missing at least 0.5% of server or "
          + "extra-info descriptors referenced from a consensus or "
          + "vote or at least 0.5% of microdescriptors referenced from a "
          + "microdesc consensus.");
    }
    if (missingVotes) {
      /* TODO Shouldn't warn if we're not trying to archive votes at
       * all. */
      this.logger.debug("We are missing at least one vote that was "
          + "referenced from a consensus.");
    }
    if (missingMicrodescConsensus) {
      /* TODO Shouldn't warn if we're not trying to archive microdesc
       * consensuses at all. */
      this.logger.debug("We are missing at least one microdesc "
          + "consensus that was published together with a known "
          + "consensus.");
    }
  }

  private void checkStaledescriptors() {
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    long tooOldMillis = this.now - 330L * 60L * 1000L;
    if (!this.storedConsensuses.isEmpty()
        && this.storedConsensuses.lastKey() < tooOldMillis) {
      this.logger.warn("The last known relay network status "
          + "consensus was valid after "
          + dateTimeFormat.format(this.storedConsensuses.lastKey())
          + ", which is more than 5:30 hours in the past.");
    }
    if (!this.storedMicrodescConsensuses.isEmpty()
        && this.storedMicrodescConsensuses.lastKey() < tooOldMillis) {
      this.logger.warn("The last known relay network status "
          + "microdesc consensus was valid after "
          + dateTimeFormat.format(
          this.storedMicrodescConsensuses.lastKey())
          + ", which is more than 5:30 hours in the past.");
    }
    if (!this.storedVotes.isEmpty()
        && this.storedVotes.lastKey() < tooOldMillis) {
      this.logger.warn("The last known relay network status vote "
          + "was valid after " + dateTimeFormat.format(
          this.storedVotes.lastKey()) + ", which is more than 5:30 hours "
          + "in the past.");
    }
    if (!this.storedServerDescriptors.isEmpty()
        && this.storedServerDescriptors.lastKey() < tooOldMillis) {
      this.logger.warn("The last known relay server descriptor was "
          + "published at "
          + dateTimeFormat.format(this.storedServerDescriptors.lastKey())
          + ", which is more than 5:30 hours in the past.");
    }
    if (!this.storedExtraInfoDescriptors.isEmpty()
        && this.storedExtraInfoDescriptors.lastKey() < tooOldMillis) {
      this.logger.warn("The last known relay extra-info descriptor "
          + "was published at " + dateTimeFormat.format(
          this.storedExtraInfoDescriptors.lastKey())
          + ", which is more than 5:30 hours in the past.");
    }
    if (!this.storedMicrodescriptors.isEmpty()
        && this.storedMicrodescriptors.lastKey() < tooOldMillis) {
      this.logger.warn("The last known relay microdescriptor was "
          + "contained in a microdesc consensus that was valid after "
          + dateTimeFormat.format(this.storedMicrodescriptors.lastKey())
          + ", which is more than 5:30 hours in the past.");
    }
  }

  /** Delete all files from the rsync directory that have not been modified
   * in the last three days (except for microdescriptors which are kept
   * for up to thirty days), and remove the .tmp extension from newly
   * written files. */
  public void cleanUpRsyncDirectory() {
    long cutOffMillis = System.currentTimeMillis()
        - 3L * 24L * 60L * 60L * 1000L;
    long cutOffMicroMillis = cutOffMillis - 27L * 24L * 60L * 60L * 1000L;
    Stack<File> allFiles = new Stack<File>();
    allFiles.add(new File(recentPathName, RELAY_DESCRIPTORS));
    while (!allFiles.isEmpty()) {
      File file = allFiles.pop();
      if (file.isDirectory()) {
        allFiles.addAll(Arrays.asList(file.listFiles()));
      } else if (file.getName().endsWith("-micro")) {
        if (file.lastModified() < cutOffMicroMillis) {
          file.delete();
        }
      } else if (file.lastModified() < cutOffMillis) {
        file.delete();
      } else if (file.getName().endsWith(".tmp")) {
        file.renameTo(new File(file.getParentFile(),
            file.getName().substring(0,
            file.getName().lastIndexOf(".tmp"))));
      }
    }
  }

  private void saveDescriptorDigests() {
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    try {
      this.storedServerDescriptorsFile.getParentFile().mkdirs();
      BufferedWriter bw = new BufferedWriter(new FileWriter(
          this.storedServerDescriptorsFile));
      for (Map.Entry<Long, Map<String, String>> e :
          this.storedServerDescriptors.entrySet()) {
        String published = dateTimeFormat.format(e.getKey());
        for (Map.Entry<String, String> f : e.getValue().entrySet()) {
          String serverDescriptorDigest = f.getKey();
          String extraInfoDescriptorDigest = f.getValue() == null ? "NA"
              : f.getValue();
          bw.write(String.format("%s,%s,%s%n", published,
              serverDescriptorDigest, extraInfoDescriptorDigest));
        }
      }
      bw.close();
      this.storedExtraInfoDescriptorsFile.getParentFile().mkdirs();
      bw = new BufferedWriter(new FileWriter(
          this.storedExtraInfoDescriptorsFile));
      for (Map.Entry<Long, Set<String>> e :
          this.storedExtraInfoDescriptors.entrySet()) {
        String published = dateTimeFormat.format(e.getKey());
        for (String extraInfoDescriptorDigest : e.getValue()) {
          bw.write(String.format("%s,%s%n", published,
              extraInfoDescriptorDigest));
        }
      }
      bw.close();
      this.storedMicrodescriptorsFile.getParentFile().mkdirs();
      bw = new BufferedWriter(new FileWriter(
          this.storedMicrodescriptorsFile));
      for (Map.Entry<Long, Set<String>> e :
          this.storedMicrodescriptors.entrySet()) {
        String validAfter = dateTimeFormat.format(e.getKey());
        for (String microdescriptorDigest : e.getValue()) {
          bw.write(String.format("%s,%s%n", validAfter,
              microdescriptorDigest));
        }
      }
      bw.close();
    } catch (IOException e) {
      this.logger.warn("Could not save descriptor "
          + "digests.  We might not be able to correctly check "
          + "descriptors for completeness in the next run.", e);
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void storeConsensus(byte[] data, long validAfter,
      SortedSet<String> dirSources,
      SortedSet<String> serverDescriptorDigests) {
    SimpleDateFormat printFormat = new SimpleDateFormat(
        "yyyy/MM/dd/yyyy-MM-dd-HH-mm-ss");
    printFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    File tarballFile = Paths.get(this.outputDirectory, "consensus",
        printFormat.format(new Date(validAfter)) + "-consensus").toFile();
    boolean tarballFileExistedBefore = tarballFile.exists();
    File rsyncFile = Paths.get(recentPathName, RELAY_DESCRIPTORS,
        "consensuses", tarballFile.getName()).toFile();
    File[] outputFiles = new File[] { tarballFile, rsyncFile };
    if (this.store(CONSENSUS_ANNOTATION, data, outputFiles, null)) {
      this.storedConsensusesCounter++;
    }
    if (!tarballFileExistedBefore
        && this.now - validAfter < 3L * 60L * 60L * 1000L) {
      this.storedConsensuses.put(validAfter, serverDescriptorDigests);
      this.expectedVotes.put(validAfter, dirSources.size());
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void storeMicrodescConsensus(byte[] data, long validAfter,
      SortedSet<String> microdescriptorDigests) {
    SimpleDateFormat yearMonthDirectoryFormat = new SimpleDateFormat(
        "yyyy/MM");
    yearMonthDirectoryFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    SimpleDateFormat dayDirectoryFileFormat = new SimpleDateFormat(
        "dd/yyyy-MM-dd-HH-mm-ss");
    dayDirectoryFileFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    File tarballFile = Paths.get(this.outputDirectory, MICRODESC,
        yearMonthDirectoryFormat.format(validAfter), CONSENSUS_MICRODESC,
        dayDirectoryFileFormat.format(validAfter) + "-consensus-microdesc").toFile();
    boolean tarballFileExistedBefore = tarballFile.exists();
    File rsyncFile = Paths.get(recentPathName, RELAY_DESCRIPTORS, MICRODESCS,
        CONSENSUS_MICRODESC, tarballFile.getName()).toFile();
    File[] outputFiles = new File[] { tarballFile, rsyncFile };
    if (this.store(MICRODESCCONSENSUS_ANNOTATION, data, outputFiles,
        null)) {
      this.storedMicrodescConsensusesCounter++;
    }
    if (!tarballFileExistedBefore
        && this.now - validAfter < 3L * 60L * 60L * 1000L) {
      this.storedMicrodescConsensuses.put(validAfter,
          microdescriptorDigests);
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void storeVote(byte[] data, long validAfter,
      String fingerprint, String digest,
      SortedSet<String> serverDescriptorDigests) {
    SimpleDateFormat printFormat = new SimpleDateFormat(
        "yyyy/MM/dd/yyyy-MM-dd-HH-mm-ss");
    printFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    File tarballFile = Paths.get(this.outputDirectory, "vote",
        printFormat.format(new Date(validAfter)) + "-vote-"
        + fingerprint + "-" + digest).toFile();
    boolean tarballFileExistedBefore = tarballFile.exists();
    File rsyncFile = Paths.get(recentPathName, RELAY_DESCRIPTORS, "votes",
        tarballFile.getName()).toFile();
    File[] outputFiles = new File[] { tarballFile, rsyncFile };
    if (this.store(VOTE_ANNOTATION, data, outputFiles, null)) {
      this.storedVotesCounter++;
    }
    if (!tarballFileExistedBefore
        && this.now - validAfter < 3L * 60L * 60L * 1000L) {
      if (!this.storedVotes.containsKey(validAfter)) {
        this.storedVotes.put(validAfter,
            new TreeMap<String, SortedSet<String>>());
      }
      this.storedVotes.get(validAfter).put(fingerprint,
          serverDescriptorDigests);
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void storeCertificate(byte[] data, String fingerprint,
      long published) {
    SimpleDateFormat printFormat = new SimpleDateFormat(
        "yyyy-MM-dd-HH-mm-ss");
    printFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    File tarballFile = Paths.get(this.outputDirectory, "certs",
        fingerprint + "-" + printFormat.format(new Date(published))).toFile();
    File[] outputFiles = new File[] { tarballFile };
    if (this.store(CERTIFICATE_ANNOTATION, data, outputFiles, null)) {
      this.storedCertsCounter++;
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void storeServerDescriptor(byte[] data, String digest,
      long published, String extraInfoDigest) {
    SimpleDateFormat printFormat = new SimpleDateFormat("yyyy/MM/");
    printFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    File tarballFile = Paths.get(this.outputDirectory,
        "server-descriptor", printFormat.format(new Date(published)),
        digest.substring(0, 1), digest.substring(1, 2), digest).toFile();
    boolean tarballFileExistedBefore = tarballFile.exists();
    File rsyncCatFile = Paths.get(recentPathName, RELAY_DESCRIPTORS,
        "server-descriptors",
        this.rsyncCatString + "-server-descriptors.tmp").toFile();
    File[] outputFiles = new File[] { tarballFile, rsyncCatFile };
    boolean[] append = new boolean[] { false, true };
    if (this.store(SERVER_DESCRIPTOR_ANNOTATION, data, outputFiles,
        append)) {
      this.storedServerDescriptorsCounter++;
    }
    if (!tarballFileExistedBefore
        && this.now - published < 48L * 60L * 60L * 1000L) {
      if (!this.storedServerDescriptors.containsKey(published)) {
        this.storedServerDescriptors.put(published,
            new HashMap<String, String>());
      }
      this.storedServerDescriptors.get(published).put(digest,
          extraInfoDigest);
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void storeExtraInfoDescriptor(byte[] data,
      String extraInfoDigest, long published) {
    SimpleDateFormat descriptorFormat = new SimpleDateFormat("yyyy/MM/");
    descriptorFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    File tarballFile = Paths.get(this.outputDirectory, "extra-info",
        descriptorFormat.format(new Date(published)),
        extraInfoDigest.substring(0, 1),
        extraInfoDigest.substring(1, 2),
        extraInfoDigest).toFile();
    boolean tarballFileExistedBefore = tarballFile.exists();
    File rsyncCatFile = Paths.get(recentPathName, RELAY_DESCRIPTORS,
        "extra-infos", this.rsyncCatString + "-extra-infos.tmp").toFile();
    File[] outputFiles = new File[] { tarballFile, rsyncCatFile };
    boolean[] append = new boolean[] { false, true };
    if (this.store(EXTRA_INFO_ANNOTATION, data, outputFiles, append)) {
      this.storedExtraInfoDescriptorsCounter++;
    }
    if (!tarballFileExistedBefore
        && this.now - published < 48L * 60L * 60L * 1000L) {
      if (!this.storedExtraInfoDescriptors.containsKey(published)) {
        this.storedExtraInfoDescriptors.put(published,
            new HashSet<String>());
      }
      this.storedExtraInfoDescriptors.get(published).add(extraInfoDigest);
    }
  }

  @SuppressWarnings("checkstyle:javadocmethod")
  public void storeMicrodescriptor(byte[] data,
      String microdescriptorDigest, long validAfter) {
    /* TODO We could check here whether we already stored the
     * microdescriptor in the same valid-after month.  This can happen,
     * e.g., when two relays share the same microdescriptor.  In that case
     * this method gets called twice and the second call overwrites the
     * file written in the first call.  However, this method must be
     * called twice to store the same microdescriptor in two different
     * valid-after months. */
    SimpleDateFormat descriptorFormat = new SimpleDateFormat("yyyy/MM/");
    descriptorFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    File tarballFile = Paths.get(this.outputDirectory, MICRODESC,
        descriptorFormat.format(validAfter), MICRO,
        microdescriptorDigest.substring(0, 1),
        microdescriptorDigest.substring(1, 2),
        microdescriptorDigest).toFile();
    boolean tarballFileExistedBefore = tarballFile.exists();
    File rsyncCatFile = Paths.get(recentPathName, RELAY_DESCRIPTORS,
        MICRODESCS, MICRO, this.rsyncCatString + "-micro.tmp").toFile();
    File[] outputFiles = new File[] { tarballFile, rsyncCatFile };
    boolean[] append = new boolean[] { false, true };
    if (this.store(MICRODESCRIPTOR_ANNOTATION, data, outputFiles,
        append)) {
      this.storedMicrodescriptorsCounter++;
    }
    if (!tarballFileExistedBefore
        && this.now - validAfter < 40L * 24L * 60L * 60L * 1000L) {
      if (!this.storedMicrodescriptors.containsKey(validAfter)) {
        this.storedMicrodescriptors.put(validAfter,
            new HashSet<String>());
      }
      this.storedMicrodescriptors.get(validAfter).add(
          microdescriptorDigest);
    }
  }

  private boolean store(byte[] typeAnnotation, byte[] data,
      File[] outputFiles, boolean[] append) {
    try {
      this.logger.trace("Storing " + outputFiles[0]);
      if (this.descriptorParser.parseDescriptors(data,
          outputFiles[0].getName()).size() != 1) {
        this.logger.info("Relay descriptor file " + outputFiles[0]
            + " doesn't contain exactly one descriptor.  Not storing.");
        return false;
      }
      for (int i = 0; i < outputFiles.length; i++) {
        File outputFile = outputFiles[i];
        boolean appendToFile = append == null ? false : append[i];
        outputFile.getParentFile().mkdirs();
        BufferedOutputStream bos = new BufferedOutputStream(
            new FileOutputStream(outputFile, appendToFile));
        if (data.length > 0 && data[0] != '@') {
          bos.write(typeAnnotation, 0, typeAnnotation.length);
        }
        bos.write(data, 0, data.length);
        bos.close();
      }
      return true;
    } catch (DescriptorParseException e) {
      this.logger.warn("Could not parse relay descriptor "
          + outputFiles[0] + " before storing it to disk.  Skipping.", e);
    } catch (IOException e) {
      this.logger.warn("Could not store relay descriptor "
          + outputFiles[0], e);
    }
    return false;
  }
}
