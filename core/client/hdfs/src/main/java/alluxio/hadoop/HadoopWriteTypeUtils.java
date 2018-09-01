/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.hadoop;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.PropertyKey;
import alluxio.client.WriteType;
import alluxio.util.io.PathUtils;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for setting WriteType with Hadoop.
 */
public class HadoopWriteTypeUtils {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopWriteTypeUtils.class);

  /**
   * Given a {@code String} path, returns a hadoop specified WriteType.
   *
   * @param path the path to parse
   * @return  the write type
   */
  public static WriteType getSpecifiedWriteType(String path) {
    String rawValue = Configuration.get(PropertyKey.USER_HDFS_WRITE_TYPE_DEFAULT);
    if (!Strings.isNullOrEmpty(rawValue)) {
      try {
        path = new AlluxioURI(path).getPath();
        for (String maps : rawValue.split(",")) {
          String[] mapping = maps.split(":");
          if (mapping.length == 2 && PathUtils.hasPrefix(path, mapping[0])) {
            return Enum.valueOf(WriteType.class, mapping[1]);
          }
        }
      } catch (Exception e) {
        LOG.error("Get hadoop specified WriteType failed, using the value of "
            + PropertyKey.USER_FILE_WRITE_TYPE_DEFAULT.getName(), e);
      }
    }
    return Configuration.getEnum(PropertyKey.USER_FILE_WRITE_TYPE_DEFAULT, WriteType.class);
  }
}
