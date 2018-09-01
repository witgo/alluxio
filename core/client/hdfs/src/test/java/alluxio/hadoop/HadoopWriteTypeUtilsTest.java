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

import static org.junit.Assert.assertEquals;

import alluxio.Configuration;
import alluxio.ConfigurationTestUtils;
import alluxio.PropertyKey;
import alluxio.client.WriteType;

import org.junit.After;
import org.junit.Test;

public class HadoopWriteTypeUtilsTest {
  @After
  public void after() {
    ConfigurationTestUtils.resetConfiguration();
  }

  /**
   * Test for the {@link HadoopWriteTypeUtils#getSpecifiedWriteType(String)} method.
   */
  @Test
  public void getSpecifiedWriteTypeWithScheme() {
    assertEquals(Configuration.getEnum(PropertyKey.USER_FILE_WRITE_TYPE_DEFAULT, WriteType.class),
        HadoopWriteTypeUtils.getSpecifiedWriteType("/a/b/c/"));
    Configuration.set(PropertyKey.USER_HDFS_WRITE_TYPE_DEFAULT,
        "/a/b:NONE,/a/:MUST_CACHE,/:ASYNC_THROUGH");
    assertEquals(WriteType.NONE,
        HadoopWriteTypeUtils.getSpecifiedWriteType("/a/b/c/"));
    assertEquals(WriteType.MUST_CACHE,
        HadoopWriteTypeUtils.getSpecifiedWriteType("alluxio:///a/d"));
    assertEquals(WriteType.MUST_CACHE,
        HadoopWriteTypeUtils.getSpecifiedWriteType("alluxio://hacluster/a"));
    assertEquals(WriteType.ASYNC_THROUGH,
        HadoopWriteTypeUtils.getSpecifiedWriteType("alluxio://hacluster/b"));
    assertEquals(WriteType.ASYNC_THROUGH,
        HadoopWriteTypeUtils.getSpecifiedWriteType("/"));
  }
}
