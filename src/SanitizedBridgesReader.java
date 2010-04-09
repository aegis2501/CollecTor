import java.io.*;
import java.util.*;
import java.util.logging.*;

public class SanitizedBridgesReader {
  public SanitizedBridgesReader(BridgeDescriptorParser bdp,
      String bridgesDir, SortedSet<String> countries) {
    Logger logger =
        Logger.getLogger(SanitizedBridgesReader.class.getName());
    if (new File(bridgesDir).exists()) {
      logger.fine("Importing files in directory " + bridgesDir + "/...");
      Stack<File> filesInInputDir = new Stack<File>();
      filesInInputDir.add(new File(bridgesDir));
      List<File> problems = new ArrayList<File>();
      while (!filesInInputDir.isEmpty()) {
        File pop = filesInInputDir.pop();
        if (pop.isDirectory()) {
          for (File f : pop.listFiles()) {
            filesInInputDir.add(f);
          }
          continue;
        } else {
          try {
            BufferedInputStream bis = new BufferedInputStream(
                new FileInputStream(pop));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int len;
            byte[] data = new byte[1024];
            while ((len = bis.read(data, 0, 1024)) >= 0) {
              baos.write(data, 0, len);
            }
            bis.close();
            byte[] allData = baos.toByteArray();
            String fn = pop.getName();
            String dateTime = fn.substring(0, 4) + "-" + fn.substring(4, 6)
                + "-" + fn.substring(6, 8) + " " + fn.substring(9, 11)
                + ":" + fn.substring(11, 13) + ":" + fn.substring(13, 15);
            bdp.parse(allData, dateTime, true);
          } catch (IOException e) {
            problems.add(pop);
            if (problems.size() > 3) {
              break;
            }
          }
        }
      }
      if (problems.isEmpty()) {
        logger.fine("Finished importing files in directory " + bridgesDir
            + "/.");
      } else {
        StringBuilder sb = new StringBuilder("Failed importing files in "
            + "directory " + bridgesDir + "/:");
        int printed = 0;
        for (File f : problems) {
          sb.append("\n  " + f.getAbsolutePath());
          if (++printed >= 3) {
            sb.append("\n  ... more");
            break;
          }
        }
        logger.warning(sb.toString());
      }
    }
  }
}

