/* Copyright 2011 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.ernie.test;

import org.torproject.ernie.db.*;

import java.io.*;

import org.junit.*;
import org.junit.rules.*;
import static org.junit.Assert.*;

public class BridgeSnapshotReaderTest {

  private File tempBridgeDirectoriesDirectory;
  private File tempStatsDirectory;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void createTempDirectories() {
    this.tempBridgeDirectoriesDirectory = folder.newFolder("bridges");
    this.tempStatsDirectory = folder.newFolder("stats");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBridgeDescriptorParserNull() {
    new BridgeSnapshotReader(null, this.tempBridgeDirectoriesDirectory,
        this.tempStatsDirectory);
  }
}
