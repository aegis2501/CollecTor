/* Copyright 2016 The Tor Project
 * See LICENSE for licensing information */
package org.torproject.collector.cron;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.torproject.collector.conf.Key;
import org.torproject.collector.conf.Configuration;
import org.torproject.collector.cron.Scheduler;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class SchedulerTest {

  private static final String runConfigProperties =
    "TorperfActivated=true\nTorperfPeriodMinutes=1\nTorperfOffsetMinutes=0\n"
    + "RelaydescsActivated=true\nRelaydescsPeriodMinutes=1"
    + "\nRelaydescsOffsetMinutes=0\n"
    + "ExitlistsActivated=true\nExitlistsPeriodMinutes=1\n"
    + "ExitlistsOffsetMinutes=0\n"
    + "UpdateindexActivated=true\nUpdateindexPeriodMinutes=1\n"
    + "UpdateindexOffsetMinutes=0\n"
    + "BridgedescsActivated=true\nBridgedescsPeriodMinutes=1\n"
    + "BridgedescsOffsetMinutes=0\n";

  @Test()
  public void testSimpleSchedule() throws Exception {
    Map<Key, Class<? extends CollecTorMain>> ctms = new HashMap<>();
    Configuration conf = new Configuration();
    conf.load(new ByteArrayInputStream(runConfigProperties.getBytes()));
    ctms.put(Key.TorperfActivated, Dummy.class);
    ctms.put(Key.BridgedescsActivated, Dummy.class);
    ctms.put(Key.RelaydescsActivated, Dummy.class);
    ctms.put(Key.ExitlistsActivated, Dummy.class);
    ctms.put(Key.UpdateindexActivated, Dummy.class);
    Field schedulerField = Scheduler.class.getDeclaredField("scheduler");
    schedulerField.setAccessible(true);
    ScheduledThreadPoolExecutor stpe = (ScheduledThreadPoolExecutor)
        schedulerField.get(Scheduler.getInstance());
    assertTrue(stpe.getQueue().isEmpty());
    Scheduler.getInstance().scheduleModuleRuns(ctms, conf);
    Scheduler.getInstance().shutdownScheduler();
  }

}
