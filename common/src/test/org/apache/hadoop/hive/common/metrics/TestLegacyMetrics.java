/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.common.metrics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.common.metrics.LegacyMetrics.MetricsScope;
import org.apache.hadoop.hive.conf.HiveConf;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestLegacyMetrics {

  private static final String scopeName = "foo";
  private static final long periodMs = 50L;
  private static LegacyMetrics metrics;

  @Before
  public void before() throws Exception {
    MetricsFactory.close();
    HiveConf conf = new HiveConf();
    conf.setVar(HiveConf.ConfVars.HIVE_METRICS_CLASS, LegacyMetrics.class.getCanonicalName());
    MetricsFactory.init(conf);
    metrics = (LegacyMetrics) MetricsFactory.getInstance();
  }

  @After
  public void after() throws Exception {
    MetricsFactory.close();
  }

  @Test
  public void testMetricsMBean() throws Exception {
    MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
    final ObjectName oname = new ObjectName(
        "org.apache.hadoop.hive.common.metrics:type=MetricsMBean");
    MBeanInfo mBeanInfo = mbs.getMBeanInfo(oname);
    // check implementation class:
    assertEquals(MetricsMBeanImpl.class.getName(), mBeanInfo.getClassName());

    // check reset operation:
    MBeanOperationInfo[] oops = mBeanInfo.getOperations();
    boolean resetFound = false;
    for (MBeanOperationInfo op : oops) {
      if ("reset".equals(op.getName())) {
        resetFound = true;
        break;
      }
    }
    assertTrue(resetFound);

    // add metric with a non-null value:
    Attribute attr = new Attribute("fooMetric", Long.valueOf(-77));
    mbs.setAttribute(oname, attr);

    mBeanInfo = mbs.getMBeanInfo(oname);
    MBeanAttributeInfo[] attrinuteInfos = mBeanInfo.getAttributes();
    assertEquals(1, attrinuteInfos.length);
    boolean attrFound = false;
    for (MBeanAttributeInfo info : attrinuteInfos) {
      if ("fooMetric".equals(info.getName())) {
        assertEquals("java.lang.Long", info.getType());
        assertTrue(info.isReadable());
        assertTrue(info.isWritable());
        assertFalse(info.isIs());

        attrFound = true;
        break;
      }
    }
    assertTrue(attrFound);

    // check metric value:
    Object v = mbs.getAttribute(oname, "fooMetric");
    assertEquals(Long.valueOf(-77), v);

    // reset the bean:
    Object result = mbs.invoke(oname, "reset", new Object[0], new String[0]);
    assertNull(result);

    // the metric value must be zeroed:
    v = mbs.getAttribute(oname, "fooMetric");
    assertEquals(Long.valueOf(0), v);
  }

  private <T> void expectIOE(Callable<T> c) throws Exception {
    try {
      T t = c.call();
      fail("IOE expected but ["+t+"] was returned.");
    } catch (IOException ioe) {
      // ok, expected
    }
  }

  @Test
  public void testScopeSingleThread() throws Exception {
    metrics.startScope(scopeName);
    final MetricsScope fooScope = metrics.getScope(scopeName);
    // the time and number counters become available only after the 1st
    // scope close:
    expectIOE(new Callable<Long>() {
      @Override
      public Long call() throws Exception {
        Long num = fooScope.getNumCounter();
        return num;
      }
    });
    expectIOE(new Callable<Long>() {
      @Override
      public Long call() throws Exception {
        Long time = fooScope.getTimeCounter();
        return time;
      }
    });
    // cannot open scope that is already open:
    expectIOE(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        fooScope.open();
        return null;
      }
    });

    assertSame(fooScope, metrics.getScope(scopeName));
    Thread.sleep(periodMs+ 1);
    // 1st close:
    // closing of open scope should be ok:
    metrics.endScope(scopeName);
    expectIOE(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        metrics.endScope(scopeName); // closing of closed scope not allowed
        return null;
      }
    });

    assertEquals(Long.valueOf(1), fooScope.getNumCounter());
    final long t1 = fooScope.getTimeCounter().longValue();
    assertTrue(t1 > periodMs);

    assertSame(fooScope, metrics.getScope(scopeName));

   // opening allowed after closing:
    metrics.startScope(scopeName);
    // opening of already open scope not allowed:
    expectIOE(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        metrics.startScope(scopeName);
        return null;
      }
    });

    assertEquals(Long.valueOf(1), fooScope.getNumCounter());
    assertEquals(t1, fooScope.getTimeCounter().longValue());

    assertSame(fooScope, metrics.getScope(scopeName));
    Thread.sleep(periodMs + 1);
    // Reopening (close + open) allowed in opened state:
    fooScope.reopen();

    assertEquals(Long.valueOf(2), fooScope.getNumCounter());
    assertTrue(fooScope.getTimeCounter().longValue() > 2 * periodMs);

    Thread.sleep(periodMs + 1);
    // 3rd close:
    fooScope.close();

    assertEquals(Long.valueOf(3), fooScope.getNumCounter());
    assertTrue(fooScope.getTimeCounter().longValue() > 3 * periodMs);
    Double avgT = (Double) metrics.get("foo.avg_t");
    assertTrue(avgT.doubleValue() > periodMs);
  }

  @Test
  public void testScopeConcurrency() throws Exception {
    metrics.startScope(scopeName);
    MetricsScope fooScope = metrics.getScope(scopeName);
    final int threads = 10;
    ExecutorService executorService = Executors.newFixedThreadPool(threads);
    for (int i=0; i<threads; i++) {
      final int n = i;
      executorService.submit(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          testScopeImpl(n);
          return null;
        }
      });
    }
    executorService.shutdown();
    assertTrue(executorService.awaitTermination(periodMs * 3 * threads, TimeUnit.MILLISECONDS));

    fooScope = metrics.getScope(scopeName);
    assertEquals(Long.valueOf(3 * threads), fooScope.getNumCounter());
    assertTrue(fooScope.getTimeCounter().longValue() > 3 * periodMs * threads);
    Double avgT = (Double) metrics.get("foo.avg_t");
    assertTrue(avgT.doubleValue() > periodMs);
    metrics.endScope(scopeName);
  }

  void testScopeImpl(int n) throws Exception {
    metrics.startScope(scopeName);
    final MetricsScope fooScope = metrics.getScope(scopeName);
      // cannot open scope that is already open:
    expectIOE(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        fooScope.open();
        return null;
      }
    });

    assertSame(fooScope, metrics.getScope(scopeName));
    Thread.sleep(periodMs+ 1);
    // 1st close:
    metrics.endScope(scopeName); // closing of open scope should be ok.

    assertTrue(fooScope.getNumCounter().longValue() >= 1);
    final long t1 = fooScope.getTimeCounter().longValue();
    assertTrue(t1 > periodMs);

    expectIOE(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        metrics.endScope(scopeName); // closing of closed scope not allowed
        return null;
      }
    });

    assertSame(fooScope, metrics.getScope(scopeName));

   // opening allowed after closing:
    metrics.startScope(scopeName);

    assertTrue(fooScope.getNumCounter().longValue() >= 1);
    assertTrue(fooScope.getTimeCounter().longValue() >= t1);

   // opening of already open scope not allowed:
    expectIOE(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        metrics.startScope(scopeName);
        return null;
      }
    });

    assertSame(fooScope, metrics.getScope(scopeName));
    Thread.sleep(periodMs + 1);
    // Reopening (close + open) allowed in opened state:
    fooScope.reopen();

    assertTrue(fooScope.getNumCounter().longValue() >= 2);
    assertTrue(fooScope.getTimeCounter().longValue() > 2 * periodMs);

    Thread.sleep(periodMs + 1);
    // 3rd close:
    fooScope.close();

    assertTrue(fooScope.getNumCounter().longValue() >= 3);
    assertTrue(fooScope.getTimeCounter().longValue() > 3 * periodMs);
    Double avgT = (Double) metrics.get("foo.avg_t");
    assertTrue(avgT.doubleValue() > periodMs);
  }
}
