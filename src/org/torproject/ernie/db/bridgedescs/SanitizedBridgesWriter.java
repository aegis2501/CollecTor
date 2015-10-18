/* Copyright 2010--2012 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.ernie.db.bridgedescs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.Stack;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.torproject.ernie.db.main.Configuration;
import org.torproject.ernie.db.main.LockFile;
import org.torproject.ernie.db.main.LoggingConfiguration;

/**
 * Sanitizes bridge descriptors, i.e., removes all possibly sensitive
 * information from them, and writes them to a local directory structure.
 * During the sanitizing process, all information about the bridge
 * identity or IP address are removed or replaced. The goal is to keep the
 * sanitized bridge descriptors useful for statistical analysis while not
 * making it easier for an adversary to enumerate bridges.
 *
 * There are three types of bridge descriptors: bridge network statuses
 * (lists of all bridges at a given time), server descriptors (published
 * by the bridge to advertise their capabilities), and extra-info
 * descriptors (published by the bridge, mainly for statistical analysis).
 */
public class SanitizedBridgesWriter extends Thread {

  public static void main(String[] args) {

    /* Initialize logging configuration. */
    new LoggingConfiguration("bridge-descriptors");
    Logger logger = Logger.getLogger(
        SanitizedBridgesWriter.class.getName());
    logger.info("Starting bridge-descriptors module of ERNIE.");

    // Initialize configuration
    Configuration config = new Configuration();

    // Use lock file to avoid overlapping runs
    LockFile lf = new LockFile("bridge-descriptors");
    if (!lf.acquireLock()) {
      logger.severe("Warning: ERNIE is already running or has not exited "
          + "cleanly! Exiting!");
      System.exit(1);
    }

    // Sanitize bridge descriptors
    new SanitizedBridgesWriter(config).run();

    // Remove lock file
    lf.releaseLock();

    logger.info("Terminating bridge-descriptors module of ERNIE.");
  }

  private Configuration config;

  /**
   * Initializes this class.
   */
  public SanitizedBridgesWriter(Configuration config) {
    this.config = config;
  }

  /**
   * Logger for this class.
   */
  private Logger logger;

  private String rsyncCatString;

  private File bridgeDirectoriesDirectory;

  /**
   * Output directory for writing sanitized bridge descriptors.
   */
  private File sanitizedBridgesDirectory;

  private boolean replaceIPAddressesWithHashes;

  private boolean persistenceProblemWithSecrets;

  private SortedMap<String, byte[]> secretsForHashingIPAddresses;

  private String bridgeSanitizingCutOffTimestamp;

  private boolean haveWarnedAboutInterval;

  private File bridgeIpSecretsFile;

  private SecureRandom secureRandom;

  public void run() {

    File bridgeDirectoriesDirectory =
        new File(config.getBridgeSnapshotsDirectory());
    File sanitizedBridgesDirectory =
        new File(config.getSanitizedBridgesWriteDirectory());
    boolean replaceIPAddressesWithHashes =
        config.getReplaceIPAddressesWithHashes();
    long limitBridgeSanitizingInterval =
        config.getLimitBridgeDescriptorMappings();
    File statsDirectory = new File("stats");

    if (bridgeDirectoriesDirectory == null ||
        sanitizedBridgesDirectory == null || statsDirectory == null) {
      throw new IllegalArgumentException();
    }

    /* Memorize argument values. */
    this.bridgeDirectoriesDirectory = bridgeDirectoriesDirectory;
    this.sanitizedBridgesDirectory = sanitizedBridgesDirectory;
    this.replaceIPAddressesWithHashes = replaceIPAddressesWithHashes;

    /* Initialize logger. */
    this.logger = Logger.getLogger(
        SanitizedBridgesWriter.class.getName());

    SimpleDateFormat rsyncCatFormat = new SimpleDateFormat(
        "yyyy-MM-dd-HH-mm-ss");
    rsyncCatFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    this.rsyncCatString = rsyncCatFormat.format(
        System.currentTimeMillis());

    /* Initialize secure random number generator if we need it. */
    if (this.replaceIPAddressesWithHashes) {
      try {
        this.secureRandom = SecureRandom.getInstance("SHA1PRNG", "SUN");
      } catch (GeneralSecurityException e) {
        this.logger.log(Level.WARNING, "Could not initialize secure "
            + "random number generator! Not calculating any IP address "
            + "hashes in this execution!", e);
        this.persistenceProblemWithSecrets = true;
      }
    }

    /* Read hex-encoded secrets for replacing IP addresses with hashes
     * from disk. */
    this.secretsForHashingIPAddresses = new TreeMap<String, byte[]>();
    this.bridgeIpSecretsFile = new File(statsDirectory,
        "bridge-ip-secrets");
    if (this.bridgeIpSecretsFile.exists()) {
      try {
        BufferedReader br = new BufferedReader(new FileReader(
            this.bridgeIpSecretsFile));
        String line;
        while ((line = br.readLine()) != null) {
          String[] parts = line.split(",");
          if ((line.length() != ("yyyy-MM,".length() + 31 * 2) &&
              line.length() != ("yyyy-MM,".length() + 50 * 2)) ||
              parts.length != 2) {
            this.logger.warning("Invalid line in bridge-ip-secrets file "
                + "starting with '" + line.substring(0, 7) + "'! "
                + "Not calculating any IP address hashes in this "
                + "execution!");
            this.persistenceProblemWithSecrets = true;
            break;
          }
          String month = parts[0];
          byte[] secret = Hex.decodeHex(parts[1].toCharArray());
          this.secretsForHashingIPAddresses.put(month, secret);
        }
        br.close();
        if (!this.persistenceProblemWithSecrets) {
          this.logger.fine("Read "
              + this.secretsForHashingIPAddresses.size() + " secrets for "
              + "hashing bridge IP addresses.");
        }
      } catch (DecoderException e) {
        this.logger.log(Level.WARNING, "Failed to decode hex string in "
            + this.bridgeIpSecretsFile + "! Not calculating any IP "
            + "address hashes in this execution!", e);
        this.persistenceProblemWithSecrets = true;
      } catch (IOException e) {
        this.logger.log(Level.WARNING, "Failed to read "
            + this.bridgeIpSecretsFile + "! Not calculating any IP "
            + "address hashes in this execution!", e);
        this.persistenceProblemWithSecrets = true;
      }
    }

    /* If we're configured to keep secrets only for a limited time, define
     * the cut-off day and time. */
    if (limitBridgeSanitizingInterval >= 0L) {
      SimpleDateFormat formatter = new SimpleDateFormat(
          "yyyy-MM-dd HH:mm:ss");
      formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
      this.bridgeSanitizingCutOffTimestamp = formatter.format(
          System.currentTimeMillis() - 24L * 60L * 60L * 1000L
          * limitBridgeSanitizingInterval);
    } else {
      this.bridgeSanitizingCutOffTimestamp = "1999-12-31 23:59:59";
    }

    // Prepare bridge descriptor parser
    BridgeDescriptorParser bdp = new BridgeDescriptorParser(this);

    // Import bridge descriptors
    new BridgeSnapshotReader(bdp, this.bridgeDirectoriesDirectory,
        statsDirectory);

    // Finish writing sanitized bridge descriptors to disk
    this.finishWriting();

    this.checkStaleDescriptors();

    this.cleanUpRsyncDirectory();
  }

  private String scrubOrAddress(String orAddress, byte[] fingerprintBytes,
      String published) throws IOException {
    if (!orAddress.contains(":")) {
      /* Malformed or-address or a line. */
      return null;
    }
    String addressPart = orAddress.substring(0,
        orAddress.lastIndexOf(":"));
    String portPart = orAddress.substring(orAddress.lastIndexOf(":") + 1);
    String scrubbedAddressPart = null;
    if (addressPart.startsWith("[")) {
      scrubbedAddressPart = this.scrubIpv6Address(addressPart,
          fingerprintBytes, published);
    } else {
      scrubbedAddressPart = this.scrubIpv4Address(addressPart,
          fingerprintBytes, published);
    }
    return (scrubbedAddressPart == null ? null :
          scrubbedAddressPart + ":" + portPart);
  }

  private String scrubIpv4Address(String address, byte[] fingerprintBytes,
      String published) throws IOException {
    if (this.replaceIPAddressesWithHashes) {
      if (this.persistenceProblemWithSecrets) {
        /* There's a persistence problem, so we shouldn't scrub more IP
         * addresses in this execution. */
        return null;
      }
      byte[] hashInput = new byte[4 + 20 + 31];
      String[] ipParts = address.split("\\.");
      for (int i = 0; i < 4; i++) {
        hashInput[i] = (byte) Integer.parseInt(ipParts[i]);
      }
      System.arraycopy(fingerprintBytes, 0, hashInput, 4, 20);
      String month = published.substring(0, "yyyy-MM".length());
      byte[] secret = this.getSecretForMonth(month);
      System.arraycopy(secret, 0, hashInput, 24, 31);
      byte[] hashOutput = DigestUtils.sha256(hashInput);
      String hashedAddress = "10."
          + (((int) hashOutput[0] + 256) % 256) + "."
          + (((int) hashOutput[1] + 256) % 256) + "."
          + (((int) hashOutput[2] + 256) % 256);
      return hashedAddress;
    } else {
      return "127.0.0.1";
    }
  }

  private String scrubIpv6Address(String address, byte[] fingerprintBytes,
      String published) throws IOException {
    StringBuilder sb = new StringBuilder("[fd9f:2e19:3bcf::");
    if (this.replaceIPAddressesWithHashes) {
      if (this.persistenceProblemWithSecrets) {
        /* There's a persistence problem, so we shouldn't scrub more IP
         * addresses in this execution. */
        return null;
      }
      byte[] hashInput = new byte[16 + 20 + 19];
      String[] doubleColonSeparatedParts = address.substring(1,
          address.length() - 1).split("::", -1);
      if (doubleColonSeparatedParts.length > 2) {
        /* Invalid IPv6 address. */
        return null;
      }
      List<String> hexParts = new ArrayList<String>();
      for (String doubleColonSeparatedPart : doubleColonSeparatedParts) {
        StringBuilder hexPart = new StringBuilder();
        String[] parts = doubleColonSeparatedPart.split(":", -1);
        if (parts.length < 1 || parts.length > 8) {
          /* Invalid IPv6 address. */
          return null;
        }
        for (int i = 0; i < parts.length; i++) {
          String part = parts[i];
          if (part.contains(".")) {
            String[] ipParts = part.split("\\.");
            byte[] ipv4Bytes = new byte[4];
            if (ipParts.length != 4) {
              /* Invalid IPv4 part in IPv6 address. */
              return null;
            }
            for (int m = 0; m < 4; m++) {
              ipv4Bytes[m] = (byte) Integer.parseInt(ipParts[m]);
            }
            hexPart.append(Hex.encodeHexString(ipv4Bytes));
          } else if (part.length() > 4) {
            /* Invalid IPv6 address. */
            return null;
          } else {
            for (int k = part.length(); k < 4; k++) {
              hexPart.append("0");
            }
            hexPart.append(part);
          }
        }
        hexParts.add(hexPart.toString());
      }
      StringBuilder hex = new StringBuilder();
      hex.append(hexParts.get(0));
      if (hexParts.size() == 2) {
        for (int i = 32 - hexParts.get(0).length()
            - hexParts.get(1).length(); i > 0; i--) {
          hex.append("0");
        }
        hex.append(hexParts.get(1));
      }
      byte[] ipBytes = null;
      try {
        ipBytes = Hex.decodeHex(hex.toString().toCharArray());
      } catch (DecoderException e) {
        /* TODO Invalid IPv6 address. */
        return null;
      }
      if (ipBytes.length != 16) {
        /* TODO Invalid IPv6 address. */
        return null;
      }
      System.arraycopy(ipBytes, 0, hashInput, 0, 16);
      System.arraycopy(fingerprintBytes, 0, hashInput, 16, 20);
      String month = published.substring(0, "yyyy-MM".length());
      byte[] secret = this.getSecretForMonth(month);
      System.arraycopy(secret, 31, hashInput, 36, 19);
      String hashOutput = DigestUtils.sha256Hex(hashInput);
      sb.append(hashOutput.substring(hashOutput.length() - 6,
          hashOutput.length() - 4));
      sb.append(":");
      sb.append(hashOutput.substring(hashOutput.length() - 4));
    }
    sb.append("]");
    return sb.toString();
  }

  private byte[] getSecretForMonth(String month) throws IOException {
    if (!this.secretsForHashingIPAddresses.containsKey(month) ||
        this.secretsForHashingIPAddresses.get(month).length == 31) {
      byte[] secret = new byte[50];
      this.secureRandom.nextBytes(secret);
      if (this.secretsForHashingIPAddresses.containsKey(month)) {
        System.arraycopy(this.secretsForHashingIPAddresses.get(month), 0,
            secret, 0, 31);
      }
      if (month.compareTo(
          this.bridgeSanitizingCutOffTimestamp) < 0) {
        this.logger.warning("Generated a secret that we won't make "
            + "persistent, because it's outside our bridge descriptor "
            + "sanitizing interval.");
      } else {
        /* Append secret to file on disk immediately before using it, or
         * we might end with inconsistently sanitized bridges. */
        try {
          if (!this.bridgeIpSecretsFile.exists()) {
            this.bridgeIpSecretsFile.getParentFile().mkdirs();
          }
          BufferedWriter bw = new BufferedWriter(new FileWriter(
              this.bridgeIpSecretsFile,
              this.bridgeIpSecretsFile.exists()));
          bw.write(month + "," + Hex.encodeHexString(secret) + "\n");
          bw.close();
        } catch (IOException e) {
          this.logger.log(Level.WARNING, "Could not store new secret "
              + "to disk! Not calculating any IP address hashes in "
              + "this execution!", e);
          this.persistenceProblemWithSecrets = true;
          throw new IOException(e);
        }
      }
      this.secretsForHashingIPAddresses.put(month, secret);
    }
    return this.secretsForHashingIPAddresses.get(month);
  }

  private String maxNetworkStatusPublishedTime = "1970-01-01 00:00:00";

  /**
   * Sanitizes a network status and writes it to disk.
   */
  public void sanitizeAndStoreNetworkStatus(byte[] data,
      String publicationTime) {

    if (this.persistenceProblemWithSecrets) {
      /* There's a persistence problem, so we shouldn't scrub more IP
       * addresses in this execution. */
      return;
    }

    if (publicationTime.compareTo(maxNetworkStatusPublishedTime) > 0) {
      maxNetworkStatusPublishedTime = publicationTime;
    }

    if (this.bridgeSanitizingCutOffTimestamp.
        compareTo(publicationTime) > 0) {
      this.logger.log(!this.haveWarnedAboutInterval ? Level.WARNING
          : Level.FINE, "Sanitizing and storing network status with "
          + "publication time outside our descriptor sanitizing "
          + "interval.");
      this.haveWarnedAboutInterval = true;
    }

    /* Parse the given network status line by line. */
    StringBuilder header = new StringBuilder();
    SortedMap<String, String> scrubbedLines =
        new TreeMap<String, String>();
    try {
      StringBuilder scrubbed = new StringBuilder();
      BufferedReader br = new BufferedReader(new StringReader(new String(
          data, "US-ASCII")));
      String line = null;
      String mostRecentDescPublished = null;
      byte[] fingerprintBytes = null;
      String descPublicationTime = null;
      String hashedBridgeIdentityHex = null;
      while ((line = br.readLine()) != null) {

        /* Use publication time from "published" line instead of the
         * file's last-modified time.  Don't copy over the line, because
         * we're going to write a "published" line below. */
        if (line.startsWith("published ")) {
          publicationTime = line.substring("published ".length());

        /* Additional header lines don't have to be cleaned up. */
        } else if (line.startsWith("flag-thresholds ")) {
          header.append(line + "\n");

        /* r lines contain sensitive information that needs to be removed
         * or replaced. */
        } else if (line.startsWith("r ")) {

          /* Clear buffer from previously scrubbed lines. */
          if (scrubbed.length() > 0) {
            String scrubbedLine = scrubbed.toString();
            scrubbedLines.put(hashedBridgeIdentityHex, scrubbedLine);
            scrubbed = new StringBuilder();
          }

          /* Parse the relevant parts of this r line. */
          String[] parts = line.split(" ");
          String nickname = parts[1];
          fingerprintBytes = Base64.decodeBase64(parts[2] + "==");
          String descriptorIdentifier = parts[3];
          descPublicationTime = parts[4] + " " + parts[5];
          String address = parts[6];
          String orPort = parts[7];
          String dirPort = parts[8];

          /* Determine most recent descriptor publication time. */
          if (descPublicationTime.compareTo(publicationTime) <= 0 &&
              (mostRecentDescPublished == null ||
              descPublicationTime.compareTo(
              mostRecentDescPublished) > 0)) {
            mostRecentDescPublished = descPublicationTime;
          }

          /* Write scrubbed r line to buffer. */
          byte[] hashedBridgeIdentity = DigestUtils.sha(fingerprintBytes);
          String hashedBridgeIdentityBase64 = Base64.encodeBase64String(
              hashedBridgeIdentity).substring(0, 27);
          hashedBridgeIdentityHex = Hex.encodeHexString(
              hashedBridgeIdentity);
          String hashedDescriptorIdentifier = Base64.encodeBase64String(
              DigestUtils.sha(Base64.decodeBase64(descriptorIdentifier
              + "=="))).substring(0, 27);
          String scrubbedAddress = scrubIpv4Address(address,
              fingerprintBytes,
              descPublicationTime);
          scrubbed.append("r " + nickname + " "
              + hashedBridgeIdentityBase64 + " "
              + hashedDescriptorIdentifier + " " + descPublicationTime
              + " " + scrubbedAddress + " " + orPort + " " + dirPort
              + "\n");

        /* Sanitize any addresses in a lines using the fingerprint and
         * descriptor publication time from the previous r line. */
        } else if (line.startsWith("a ")) {
          String scrubbedOrAddress = scrubOrAddress(
              line.substring("a ".length()), fingerprintBytes,
              descPublicationTime);
          if (scrubbedOrAddress != null) {
            scrubbed.append("a " + scrubbedOrAddress + "\n");
          } else {
            this.logger.warning("Invalid address in line '" + line
                + "' in bridge network status.  Skipping line!");
          }

        /* Nothing special about s, w, and p lines; just copy them. */
        } else if (line.startsWith("s ") || line.equals("s") ||
            line.startsWith("w ") || line.equals("w") ||
            line.startsWith("p ") || line.equals("p")) {
          scrubbed.append(line + "\n");

        /* There should be nothing else but r, w, p, and s lines in the
         * network status.  If there is, we should probably learn before
         * writing anything to the sanitized descriptors. */
        } else {
          this.logger.fine("Unknown line '" + line + "' in bridge "
              + "network status. Not writing to disk!");
          return;
        }
      }
      br.close();
      if (scrubbed.length() > 0) {
        String scrubbedLine = scrubbed.toString();
        scrubbedLines.put(hashedBridgeIdentityHex, scrubbedLine);
        scrubbed = new StringBuilder();
      }

      /* Check if we can tell from the descriptor publication times
       * whether this status is possibly stale. */
      SimpleDateFormat formatter = new SimpleDateFormat(
          "yyyy-MM-dd HH:mm:ss");
      formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
      if (formatter.parse(publicationTime).getTime() -
          formatter.parse(mostRecentDescPublished).getTime() >
          60L * 60L * 1000L) {
        this.logger.warning("The most recent descriptor in the bridge "
            + "network status published at " + publicationTime + " was "
            + "published at " + mostRecentDescPublished + " which is "
            + "more than 1 hour before the status. This is a sign for "
            + "the status being stale. Please check!");
      }
    } catch (ParseException e) {
      this.logger.log(Level.WARNING, "Could not parse timestamp in "
          + "bridge network status.", e);
      return;
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "Could not parse bridge network "
          + "status.", e);
      return;
    }

    /* Write the sanitized network status to disk. */
    try {
      String syear = publicationTime.substring(0, 4);
      String smonth = publicationTime.substring(5, 7);
      String sday = publicationTime.substring(8, 10);
      String stime = publicationTime.substring(11, 13)
          + publicationTime.substring(14, 16)
          + publicationTime.substring(17, 19);
      File tarballFile = new File(
          this.sanitizedBridgesDirectory.getAbsolutePath() + "/" + syear
          + "/" + smonth + "/statuses/" + sday + "/" + syear + smonth
          + sday + "-" + stime + "-"
          + "4A0CCD2DDC7995083D73F5D667100C8A5831F16D");
      File rsyncFile = new File("recent/bridge-descriptors/statuses/"
          + tarballFile.getName());
      File[] outputFiles = new File[] { tarballFile, rsyncFile };
      for (File outputFile : outputFiles) {
        outputFile.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            outputFile));
        bw.write("@type bridge-network-status 1.0\n");
        bw.write("published " + publicationTime + "\n");
        bw.write(header.toString());
        for (String scrubbed : scrubbedLines.values()) {
          bw.write(scrubbed);
        }
        bw.close();
      }
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "Could not write sanitized bridge "
          + "network status to disk.", e);
      return;
    }
  }

  private String maxServerDescriptorPublishedTime = "1970-01-01 00:00:00";

  /**
   * Sanitizes a bridge server descriptor and writes it to disk.
   */
  public void sanitizeAndStoreServerDescriptor(byte[] data) {

    if (this.persistenceProblemWithSecrets) {
      /* There's a persistence problem, so we shouldn't scrub more IP
       * addresses in this execution. */
      return;
    }

    /* Parse descriptor to generate a sanitized version. */
    String scrubbedDesc = null, published = null,
        masterKeyEd25519FromIdentityEd25519 = null;
    try {
      BufferedReader br = new BufferedReader(new StringReader(
          new String(data, "US-ASCII")));
      StringBuilder scrubbed = new StringBuilder();
      String line = null, hashedBridgeIdentity = null, address = null,
          routerLine = null, scrubbedAddress = null,
          masterKeyEd25519 = null;
      List<String> orAddresses = null, scrubbedOrAddresses = null;
      boolean skipCrypto = false;
      while ((line = br.readLine()) != null) {

        /* Skip all crypto parts that might be used to derive the bridge's
         * identity fingerprint. */
        if (skipCrypto && !line.startsWith("-----END ")) {
          continue;

        /* Store the router line for later processing, because we may need
         * the bridge identity fingerprint for replacing the IP address in
         * the scrubbed version.  */
        } else if (line.startsWith("router ")) {
          address = line.split(" ")[2];
          routerLine = line;

        /* Store or-address parts in a list and sanitize them when we have
         * read the fingerprint. */
        } else if (line.startsWith("or-address ")) {
          if (orAddresses == null) {
            orAddresses = new ArrayList<String>();
          }
          orAddresses.add(line.substring("or-address ".length()));

        /* Parse the publication time to see if we're still inside the
         * sanitizing interval. */
        } else if (line.startsWith("published ")) {
          published = line.substring("published ".length());
          if (published.compareTo(maxServerDescriptorPublishedTime) > 0) {
            maxServerDescriptorPublishedTime = published;
          }
          if (this.bridgeSanitizingCutOffTimestamp.
              compareTo(published) > 0) {
            this.logger.log(!this.haveWarnedAboutInterval
                ? Level.WARNING : Level.FINE, "Sanitizing and storing "
                + "server descriptor with publication time outside our "
                + "descriptor sanitizing interval.");
            this.haveWarnedAboutInterval = true;
          }
          scrubbed.append(line + "\n");

        /* Parse the fingerprint to determine the hashed bridge
         * identity. */
        } else if (line.startsWith("opt fingerprint ") ||
            line.startsWith("fingerprint ")) {
          String fingerprint = line.substring(line.startsWith("opt ") ?
              "opt fingerprint".length() : "fingerprint".length()).
              replaceAll(" ", "").toLowerCase();
          byte[] fingerprintBytes = Hex.decodeHex(
              fingerprint.toCharArray());
          hashedBridgeIdentity = DigestUtils.shaHex(fingerprintBytes).
              toLowerCase();
          try {
            scrubbedAddress = scrubIpv4Address(address, fingerprintBytes,
                published);
            if (orAddresses != null) {
              scrubbedOrAddresses = new ArrayList<String>();
              for (String orAddress : orAddresses) {
                String scrubbedOrAddress = scrubOrAddress(orAddress,
                    fingerprintBytes, published);
                if (scrubbedOrAddress != null) {
                  scrubbedOrAddresses.add(scrubbedOrAddress);
                } else {
                  this.logger.warning("Invalid address in line "
                      + "'or-address " + orAddress + "' in bridge server "
                      + "descriptor.  Skipping line!");
                }
              }
            }
          } catch (IOException e) {
            /* There's a persistence problem, so we shouldn't scrub more
             * IP addresses in this execution. */
            this.persistenceProblemWithSecrets = true;
            return;
          }
          scrubbed.append((line.startsWith("opt ") ? "opt " : "")
              + "fingerprint");
          for (int i = 0; i < hashedBridgeIdentity.length() / 4; i++)
            scrubbed.append(" " + hashedBridgeIdentity.substring(4 * i,
                4 * (i + 1)).toUpperCase());
          scrubbed.append("\n");

        /* Replace the contact line (if present) with a generic one. */
        } else if (line.startsWith("contact ")) {
          scrubbed.append("contact somebody\n");

        /* When we reach the signature, we're done. Write the sanitized
         * descriptor to disk below. */
        } else if (line.startsWith("router-signature")) {
          String[] routerLineParts = routerLine.split(" ");
          scrubbedDesc = "router " + routerLineParts[1] + " "
              + scrubbedAddress + " " + routerLineParts[3] + " "
              + routerLineParts[4] + " " + routerLineParts[5] + "\n";
          if (scrubbedOrAddresses != null) {
            for (String scrubbedOrAddress : scrubbedOrAddresses) {
              scrubbedDesc = scrubbedDesc += "or-address "
                  + scrubbedOrAddress + "\n";
            }
          }
          scrubbedDesc += scrubbed.toString();
          break;

        /* Replace extra-info digest with the hashed digest of the
         * non-scrubbed descriptor. */
        } else if (line.startsWith("opt extra-info-digest ") ||
            line.startsWith("extra-info-digest ")) {
          String[] parts = line.split(" ");
          if (line.startsWith("opt ")) {
            scrubbed.append("opt ");
            parts = line.substring(4).split(" ");
          }
          scrubbed.append("extra-info-digest " + DigestUtils.shaHex(
              Hex.decodeHex(parts[1].toCharArray())).toUpperCase());
          if (parts.length > 2) {
            scrubbed.append(" " + Base64.encodeBase64String(
                DigestUtils.sha256(Base64.decodeBase64(parts[2]))).
                replaceAll("=", ""));
          }
          scrubbed.append("\n");

        /* Possibly sanitize reject lines if they contain the bridge's own
         * IP address. */
        } else if (line.startsWith("reject ")) {
          if (address != null && line.startsWith("reject " + address)) {
            scrubbed.append("reject " + scrubbedAddress
                + line.substring("reject ".length() + address.length())
                + "\n");
          } else {
            scrubbed.append(line + "\n");
          }

        /* Extract master-key-ed25519 from identity-ed25519. */
        } else if (line.equals("identity-ed25519")) {
          StringBuilder sb = new StringBuilder();
          while ((line = br.readLine()) != null &&
              !line.equals("-----END ED25519 CERT-----")) {
            if (line.equals("-----BEGIN ED25519 CERT-----")) {
              continue;
            }
            sb.append(line);
          }
          masterKeyEd25519FromIdentityEd25519 =
              this.parseMasterKeyEd25519FromIdentityEd25519(
              sb.toString());
          String sha256MasterKeyEd25519 = Base64.encodeBase64String(
              DigestUtils.sha256(Base64.decodeBase64(
              masterKeyEd25519FromIdentityEd25519 + "="))).
              replaceAll("=", "");
          scrubbed.append("master-key-ed25519 " + sha256MasterKeyEd25519
              + "\n");
          if (masterKeyEd25519 != null && !masterKeyEd25519.equals(
              masterKeyEd25519FromIdentityEd25519)) {
            this.logger.warning("Mismatch between identity-ed25519 and "
                + "master-key-ed25519.  Skipping.");
            return;
          }

        /* Verify that identity-ed25519 and master-key-ed25519 match. */
        } else if (line.startsWith("master-key-ed25519 ")) {
          masterKeyEd25519 = line.substring(line.indexOf(" ") + 1);
          if (masterKeyEd25519FromIdentityEd25519 != null &&
              !masterKeyEd25519FromIdentityEd25519.equals(
              masterKeyEd25519)) {
            this.logger.warning("Mismatch between identity-ed25519 and "
                + "master-key-ed25519.  Skipping.");
            return;
          }

        /* Write the following lines unmodified to the sanitized
         * descriptor. */
        } else if (line.startsWith("accept ")
            || line.startsWith("platform ")
            || line.startsWith("opt protocols ")
            || line.startsWith("protocols ")
            || line.startsWith("uptime ")
            || line.startsWith("bandwidth ")
            || line.startsWith("opt hibernating ")
            || line.startsWith("hibernating ")
            || line.startsWith("ntor-onion-key ")
            || line.equals("opt hidden-service-dir")
            || line.equals("hidden-service-dir")
            || line.equals("opt caches-extra-info")
            || line.equals("caches-extra-info")
            || line.equals("opt allow-single-hop-exits")
            || line.equals("allow-single-hop-exits")
            || line.startsWith("ipv6-policy ")
            || line.equals("tunnelled-dir-server")) {
          scrubbed.append(line + "\n");

        /* Replace node fingerprints in the family line with their hashes
         * and leave nicknames unchanged. */
        } else if (line.startsWith("family ")) {
          StringBuilder familyLine = new StringBuilder("family");
          for (String s : line.substring(7).split(" ")) {
            if (s.startsWith("$")) {
              familyLine.append(" $" + DigestUtils.shaHex(Hex.decodeHex(
                  s.substring(1).toCharArray())).toUpperCase());
            } else {
              familyLine.append(" " + s);
            }
          }
          scrubbed.append(familyLine.toString() + "\n");

        /* Skip the purpose line that the bridge authority adds to its
         * cached-descriptors file. */
        } else if (line.startsWith("@purpose ")) {
          continue;

        /* Skip all crypto parts that might leak the bridge's identity
         * fingerprint. */
        } else if (line.startsWith("-----BEGIN ")
            || line.equals("onion-key") || line.equals("signing-key") ||
            line.equals("onion-key-crosscert") ||
            line.startsWith("ntor-onion-key-crosscert ")) {
          skipCrypto = true;

        /* Stop skipping lines when the crypto parts are over. */
        } else if (line.startsWith("-----END ")) {
          skipCrypto = false;

        /* Skip the ed25519 signature; we'll include a SHA256 digest of
         * the SHA256 descriptor digest in router-digest-sha256. */
        } else if (line.startsWith("router-sig-ed25519 ")) {
          continue;

        /* If we encounter an unrecognized line, stop parsing and print
         * out a warning. We might have overlooked sensitive information
         * that we need to remove or replace for the sanitized descriptor
         * version. */
        } else {
          this.logger.warning("Unrecognized line '" + line
              + "'. Skipping.");
          return;
        }
      }
      br.close();
    } catch (Exception e) {
      this.logger.log(Level.WARNING, "Could not parse server "
          + "descriptor.", e);
      return;
    }

    /* Determine filename of sanitized server descriptor. */
    String descriptorDigest = null;
    try {
      String ascii = new String(data, "US-ASCII");
      String startToken = "router ";
      String sigToken = "\nrouter-signature\n";
      int start = ascii.indexOf(startToken);
      int sig = ascii.indexOf(sigToken) + sigToken.length();
      if (start >= 0 && sig >= 0 && sig > start) {
        byte[] forDigest = new byte[sig - start];
        System.arraycopy(data, start, forDigest, 0, sig - start);
        descriptorDigest = DigestUtils.shaHex(DigestUtils.sha(forDigest));
      }
    } catch (UnsupportedEncodingException e) {
      /* Handle below. */
    }
    if (descriptorDigest == null) {
      this.logger.log(Level.WARNING, "Could not calculate server "
          + "descriptor digest.");
      return;
    }
    String descriptorDigestSha256Base64 = null;
    if (masterKeyEd25519FromIdentityEd25519 != null) {
      try {
        String ascii = new String(data, "US-ASCII");
        String startToken = "router ";
        String sigToken = "\n-----END SIGNATURE-----\n";
        int start = ascii.indexOf(startToken);
        int sig = ascii.indexOf(sigToken) + sigToken.length();
        if (start >= 0 && sig >= 0 && sig > start) {
          byte[] forDigest = new byte[sig - start];
          System.arraycopy(data, start, forDigest, 0, sig - start);
          descriptorDigestSha256Base64 = Base64.encodeBase64String(
              DigestUtils.sha256(DigestUtils.sha256(forDigest))).
              replaceAll("=", "");
        }
      } catch (UnsupportedEncodingException e) {
        /* Handle below. */
      }
      if (descriptorDigestSha256Base64 == null) {
        this.logger.log(Level.WARNING, "Could not calculate server "
            + "descriptor SHA256 digest.");
        return;
      }
    }
    String dyear = published.substring(0, 4);
    String dmonth = published.substring(5, 7);
    File tarballFile = new File(
        this.sanitizedBridgesDirectory.getAbsolutePath() + "/"
        + dyear + "/" + dmonth + "/server-descriptors/"
        + "/" + descriptorDigest.charAt(0) + "/"
        + descriptorDigest.charAt(1) + "/"
        + descriptorDigest);
    File rsyncCatFile = new File("recent/bridge-descriptors/"
        + "server-descriptors/" + this.rsyncCatString
        + "-server-descriptors.tmp");
    File[] outputFiles = new File[] { tarballFile, rsyncCatFile };
    boolean[] append = new boolean[] { false, true };
    try {
      for (int i = 0; i < outputFiles.length; i++) {
        File outputFile = outputFiles[i];
        boolean appendToFile = append[i];
        if (outputFile.exists() && !appendToFile) {
          /* We already stored this descriptor to disk before, so let's
           * not store it yet another time. */
          break;
        }
        outputFile.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            outputFile, appendToFile));
        bw.write("@type bridge-server-descriptor 1.1\n");
        bw.write(scrubbedDesc);
        if (descriptorDigestSha256Base64 != null) {
          bw.write("router-digest-sha256 " + descriptorDigestSha256Base64
              + "\n");
        }
        bw.write("router-digest " + descriptorDigest.toUpperCase()
            + "\n");
        bw.close();
      }
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "Could not write sanitized server "
          + "descriptor to disk.", e);
      return;
    }
  }

  private String parseMasterKeyEd25519FromIdentityEd25519(
      String identityEd25519Base64) {
    byte[] identityEd25519 = Base64.decodeBase64(identityEd25519Base64);
    if (identityEd25519[0] != 0x01) {
      this.logger.warning("Unknown version in identity-ed25519: "
          + identityEd25519[0]);
    } else if (identityEd25519[1] != 0x04) {
      this.logger.warning("Unknown cert type in identity-ed25519: "
          + identityEd25519[1]);
    } else if (identityEd25519[6] != 0x01) {
      this.logger.warning("Unknown certified key type in "
          + "identity-ed25519: " + identityEd25519[1]);
    } else if (identityEd25519[39] == 0x00) {
      this.logger.warning("No extensions in identity-ed25519 (which "
          + "would contain the encoded master-key-ed25519): "
          + identityEd25519[39]);
    } else {
      int extensionStart = 40;
      for (int i = 0; i < (int) identityEd25519[39]; i++) {
        if (identityEd25519.length < extensionStart + 4) {
          this.logger.warning("Invalid extension with id " + i
              + " in identity-ed25519.");
          break;
        }
        int extensionLength = identityEd25519[extensionStart];
        extensionLength <<= 8;
        extensionLength += identityEd25519[extensionStart + 1];
        int extensionType = identityEd25519[extensionStart + 2];
        if (extensionLength == 32 && extensionType == 4) {
          if (identityEd25519.length < extensionStart + 4 + 32) {
            this.logger.warning("Invalid extension with id " + i
                + " in identity-ed25519.");
            break;
          }
          byte[] masterKeyEd25519 = new byte[32];
          System.arraycopy(identityEd25519, extensionStart + 4,
              masterKeyEd25519, 0, masterKeyEd25519.length);
          String masterKeyEd25519Base64 = Base64.encodeBase64String(
              masterKeyEd25519);
          String masterKeyEd25519Base64NoTrailingEqualSigns =
              masterKeyEd25519Base64.replaceAll("=", "");
          return masterKeyEd25519Base64NoTrailingEqualSigns;
        }
        extensionStart += 4 + extensionLength;
      }
    }
    this.logger.warning("Unable to locate master-key-ed25519 in "
        + "identity-ed25519.");
    return null;
  }

  private String maxExtraInfoDescriptorPublishedTime =
      "1970-01-01 00:00:00";

  /**
   * Sanitizes an extra-info descriptor and writes it to disk.
   */
  public void sanitizeAndStoreExtraInfoDescriptor(byte[] data) {

    /* Parse descriptor to generate a sanitized version. */
    String scrubbedDesc = null, published = null,
        masterKeyEd25519FromIdentityEd25519 = null;
    try {
      BufferedReader br = new BufferedReader(new StringReader(new String(
          data, "US-ASCII")));
      String line = null;
      StringBuilder scrubbed = null;
      String hashedBridgeIdentity = null, masterKeyEd25519 = null;
      while ((line = br.readLine()) != null) {

        /* Parse bridge identity from extra-info line and replace it with
         * its hash in the sanitized descriptor. */
        String[] parts = line.split(" ");
        if (line.startsWith("extra-info ")) {
          hashedBridgeIdentity = DigestUtils.shaHex(Hex.decodeHex(
              parts[2].toCharArray())).toLowerCase();
          scrubbed = new StringBuilder("extra-info " + parts[1] + " "
              + hashedBridgeIdentity.toUpperCase() + "\n");

        /* Parse the publication time to determine the file name. */
        } else if (line.startsWith("published ")) {
          scrubbed.append(line + "\n");
          published = line.substring("published ".length());
          if (published.compareTo(maxExtraInfoDescriptorPublishedTime)
              > 0) {
            maxExtraInfoDescriptorPublishedTime = published;
          }

        /* Remove everything from transport lines except the transport
         * name. */
        } else if (line.startsWith("transport ")) {
          if (parts.length < 3) {
            this.logger.fine("Illegal line in extra-info descriptor: '"
                + line + "'.  Skipping descriptor.");
            return;
          }
          scrubbed.append("transport " + parts[1] + "\n");

        /* Skip transport-info lines entirely. */
        } else if (line.startsWith("transport-info ")) {

        /* Extract master-key-ed25519 from identity-ed25519. */
        } else if (line.equals("identity-ed25519")) {
          StringBuilder sb = new StringBuilder();
          while ((line = br.readLine()) != null &&
              !line.equals("-----END ED25519 CERT-----")) {
            if (line.equals("-----BEGIN ED25519 CERT-----")) {
              continue;
            }
            sb.append(line);
          }
          masterKeyEd25519FromIdentityEd25519 =
              this.parseMasterKeyEd25519FromIdentityEd25519(
              sb.toString());
          String sha256MasterKeyEd25519 = Base64.encodeBase64String(
              DigestUtils.sha256(Base64.decodeBase64(
              masterKeyEd25519FromIdentityEd25519 + "="))).
              replaceAll("=", "");
          scrubbed.append("master-key-ed25519 " + sha256MasterKeyEd25519
              + "\n");
          if (masterKeyEd25519 != null && !masterKeyEd25519.equals(
              masterKeyEd25519FromIdentityEd25519)) {
            this.logger.warning("Mismatch between identity-ed25519 and "
                + "master-key-ed25519.  Skipping.");
            return;
          }

        /* Verify that identity-ed25519 and master-key-ed25519 match. */
        } else if (line.startsWith("master-key-ed25519 ")) {
          masterKeyEd25519 = line.substring(line.indexOf(" ") + 1);
          if (masterKeyEd25519FromIdentityEd25519 != null &&
              !masterKeyEd25519FromIdentityEd25519.equals(
              masterKeyEd25519)) {
            this.logger.warning("Mismatch between identity-ed25519 and "
                + "master-key-ed25519.  Skipping.");
            return;
          }

        /* Write the following lines unmodified to the sanitized
         * descriptor. */
        } else if (line.startsWith("write-history ")
            || line.startsWith("read-history ")
            || line.startsWith("geoip-start-time ")
            || line.startsWith("geoip-client-origins ")
            || line.startsWith("geoip-db-digest ")
            || line.startsWith("geoip6-db-digest ")
            || line.startsWith("conn-bi-direct ")
            || line.startsWith("bridge-")
            || line.startsWith("dirreq-")
            || line.startsWith("cell-")
            || line.startsWith("entry-")
            || line.startsWith("exit-")) {
          scrubbed.append(line + "\n");

        /* When we reach the signature, we're done. Write the sanitized
         * descriptor to disk below. */
        } else if (line.startsWith("router-signature")) {
          scrubbedDesc = scrubbed.toString();
          break;

        /* Skip the ed25519 signature; we'll include a SHA256 digest of
         * the SHA256 descriptor digest in router-digest-sha256. */
        } else if (line.startsWith("router-sig-ed25519 ")) {
          continue;

        /* If we encounter an unrecognized line, stop parsing and print
         * out a warning. We might have overlooked sensitive information
         * that we need to remove or replace for the sanitized descriptor
         * version. */
        } else {
          this.logger.warning("Unrecognized line '" + line
              + "'. Skipping.");
          return;
        }
      }
      br.close();
    } catch (IOException e) {
      this.logger.log(Level.WARNING, "Could not parse extra-info "
          + "descriptor.", e);
      return;
    } catch (DecoderException e) {
      this.logger.log(Level.WARNING, "Could not parse extra-info "
          + "descriptor.", e);
      return;
    }

    /* Determine filename of sanitized extra-info descriptor. */
    String descriptorDigest = null;
    try {
      String ascii = new String(data, "US-ASCII");
      String startToken = "extra-info ";
      String sigToken = "\nrouter-signature\n";
      int start = ascii.indexOf(startToken);
      int sig = ascii.indexOf(sigToken) + sigToken.length();
      if (start >= 0 && sig >= 0 && sig > start) {
        byte[] forDigest = new byte[sig - start];
        System.arraycopy(data, start, forDigest, 0, sig - start);
        descriptorDigest = DigestUtils.shaHex(DigestUtils.sha(forDigest));
      }
    } catch (UnsupportedEncodingException e) {
      /* Handle below. */
    }
    if (descriptorDigest == null) {
      this.logger.log(Level.WARNING, "Could not calculate extra-info "
          + "descriptor digest.");
      return;
    }
    String descriptorDigestSha256Base64 = null;
    if (masterKeyEd25519FromIdentityEd25519 != null) {
      try {
        String ascii = new String(data, "US-ASCII");
        String startToken = "extra-info ";
        String sigToken = "\n-----END SIGNATURE-----\n";
        int start = ascii.indexOf(startToken);
        int sig = ascii.indexOf(sigToken) + sigToken.length();
        if (start >= 0 && sig >= 0 && sig > start) {
          byte[] forDigest = new byte[sig - start];
          System.arraycopy(data, start, forDigest, 0, sig - start);
          descriptorDigestSha256Base64 = Base64.encodeBase64String(
              DigestUtils.sha256(DigestUtils.sha256(forDigest))).
              replaceAll("=", "");
        }
      } catch (UnsupportedEncodingException e) {
        /* Handle below. */
      }
      if (descriptorDigestSha256Base64 == null) {
        this.logger.log(Level.WARNING, "Could not calculate extra-info "
            + "descriptor SHA256 digest.");
        return;
      }
    }
    String dyear = published.substring(0, 4);
    String dmonth = published.substring(5, 7);
    File tarballFile = new File(
        this.sanitizedBridgesDirectory.getAbsolutePath() + "/"
        + dyear + "/" + dmonth + "/extra-infos/"
        + descriptorDigest.charAt(0) + "/"
        + descriptorDigest.charAt(1) + "/"
        + descriptorDigest);
    File rsyncCatFile = new File("recent/bridge-descriptors/"
        + "extra-infos/" + this.rsyncCatString + "-extra-infos.tmp");
    File[] outputFiles = new File[] { tarballFile, rsyncCatFile };
    boolean[] append = new boolean[] { false, true };
    try {
      for (int i = 0; i < outputFiles.length; i++) {
        File outputFile = outputFiles[i];
        boolean appendToFile = append[i];
        if (outputFile.exists() && !appendToFile) {
          /* We already stored this descriptor to disk before, so let's
           * not store it yet another time. */
          break;
        }
        outputFile.getParentFile().mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            outputFile, appendToFile));
        bw.write("@type bridge-extra-info 1.3\n");
        bw.write(scrubbedDesc);
        if (descriptorDigestSha256Base64 != null) {
          bw.write("router-digest-sha256 " + descriptorDigestSha256Base64
              + "\n");
        }
        bw.write("router-digest " + descriptorDigest.toUpperCase()
            + "\n");
        bw.close();
      }
    } catch (Exception e) {
      this.logger.log(Level.WARNING, "Could not write sanitized "
          + "extra-info descriptor to disk.", e);
    }
  }

  /**
   * Rewrite all network statuses that might contain references to server
   * descriptors we added or updated in this execution. This applies to
   * all statuses that have been published up to 24 hours after any added
   * or updated server descriptor.
   */
  public void finishWriting() {

    /* Delete secrets that we don't need anymore. */
    if (!this.secretsForHashingIPAddresses.isEmpty() &&
        this.secretsForHashingIPAddresses.firstKey().compareTo(
        this.bridgeSanitizingCutOffTimestamp) < 0) {
      try {
        int kept = 0, deleted = 0;
        BufferedWriter bw = new BufferedWriter(new FileWriter(
            this.bridgeIpSecretsFile));
        for (Map.Entry<String, byte[]> e :
            this.secretsForHashingIPAddresses.entrySet()) {
          if (e.getKey().compareTo(
              this.bridgeSanitizingCutOffTimestamp) < 0) {
            deleted++;
          } else {
            bw.write(e.getKey() + "," + Hex.encodeHexString(e.getValue())
                + "\n");
            kept++;
          }
        }
        bw.close();
        this.logger.info("Deleted " + deleted + " secrets that we don't "
            + "need anymore and kept " + kept + ".");
      } catch (IOException e) {
        this.logger.log(Level.WARNING, "Could not store reduced set of "
            + "secrets to disk! This is a bad sign, better check what's "
            + "going on!", e);
      }
    }
  }

  private void checkStaleDescriptors() {
    SimpleDateFormat dateTimeFormat = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss");
    dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    long tooOldMillis = System.currentTimeMillis() - 330L * 60L * 1000L;
    try {
      long maxNetworkStatusPublishedMillis =
          dateTimeFormat.parse(maxNetworkStatusPublishedTime).getTime();
      if (maxNetworkStatusPublishedMillis > 0L &&
          maxNetworkStatusPublishedMillis < tooOldMillis) {
        this.logger.warning("The last known bridge network status was "
            + "published " + maxNetworkStatusPublishedTime + ", which is "
            + "more than 5:30 hours in the past.");
      }
      long maxServerDescriptorPublishedMillis =
          dateTimeFormat.parse(maxServerDescriptorPublishedTime).
          getTime();
      if (maxServerDescriptorPublishedMillis > 0L &&
          maxServerDescriptorPublishedMillis < tooOldMillis) {
        this.logger.warning("The last known bridge server descriptor was "
            + "published " + maxServerDescriptorPublishedTime + ", which "
            + "is more than 5:30 hours in the past.");
      }
      long maxExtraInfoDescriptorPublishedMillis =
          dateTimeFormat.parse(maxExtraInfoDescriptorPublishedTime).
          getTime();
      if (maxExtraInfoDescriptorPublishedMillis > 0L &&
          maxExtraInfoDescriptorPublishedMillis < tooOldMillis) {
        this.logger.warning("The last known bridge extra-info descriptor "
            + "was published " + maxExtraInfoDescriptorPublishedTime
            + ", which is more than 5:30 hours in the past.");
      }
    } catch (ParseException e) {
      this.logger.log(Level.WARNING, "Unable to parse timestamp for "
          + "stale check.", e);
    }
  }

  /* Delete all files from the rsync directory that have not been modified
   * in the last three days, and remove the .tmp extension from newly
   * written files. */
  public void cleanUpRsyncDirectory() {
    long cutOffMillis = System.currentTimeMillis()
        - 3L * 24L * 60L * 60L * 1000L;
    Stack<File> allFiles = new Stack<File>();
    allFiles.add(new File("recent/bridge-descriptors"));
    while (!allFiles.isEmpty()) {
      File file = allFiles.pop();
      if (file.isDirectory()) {
        allFiles.addAll(Arrays.asList(file.listFiles()));
      } else if (file.lastModified() < cutOffMillis) {
        file.delete();
      } else if (file.getName().endsWith(".tmp")) {
        file.renameTo(new File(file.getParentFile(),
            file.getName().substring(0,
            file.getName().lastIndexOf(".tmp"))));
      }
    }
  }
}

