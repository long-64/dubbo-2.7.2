/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL statistics. (API, Cached, ThreadSafe)
 *
 * @see org.apache.dubbo.rpc.filter.ActiveLimitFilter
 * @see org.apache.dubbo.rpc.filter.ExecuteLimitFilter
 * @see org.apache.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalance
 */
public class RpcStatus {

    private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String, RpcStatus>();

    private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS = new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();
    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();

    // 当前并发度。这也是 ActiveLimitFilter 中关注的并发度
    private final AtomicInteger active = new AtomicInteger();

    // 调用的总数
    private final AtomicLong total = new AtomicLong();

    // 失败的调用数。
    private final AtomicInteger failed = new AtomicInteger();

    // 所有调用的总耗时
    private final AtomicLong totalElapsed = new AtomicLong();

    // 所有失败调用的总耗时
    private final AtomicLong failedElapsed = new AtomicLong();

    // 所有调用中最长的耗时。
    private final AtomicLong maxElapsed = new AtomicLong();

    // 所有失败调用中最长的耗时
    private final AtomicLong failedMaxElapsed = new AtomicLong();

    // 所有成功调用中最长的耗时
    private final AtomicLong succeededMaxElapsed = new AtomicLong();

    private RpcStatus() {
    }

    /**
     * @param url
     * @return status
     */
    public static RpcStatus getStatus(URL url) {
        String uri = url.toIdentityString();
        RpcStatus status = SERVICE_STATISTICS.get(uri);
        if (status == null) {
            SERVICE_STATISTICS.putIfAbsent(uri, new RpcStatus());
            status = SERVICE_STATISTICS.get(uri);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url) {
        String uri = url.toIdentityString();
        SERVICE_STATISTICS.remove(uri);
    }

    /**
     * @param url
     * @param methodName
     * @return status
     *
     *  获取方法对应 RpcStatus
     */
    public static RpcStatus getStatus(URL url, String methodName) {
        String uri = url.toIdentityString();

        /**
         *
         * METHOD_STATISTICS (并发安全的缓存)
         *  Key：服务接口
         *  value: 服务接口中所有方法的缓存。
         */
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map == null) {
            METHOD_STATISTICS.putIfAbsent(uri, new ConcurrentHashMap<String, RpcStatus>());
            map = METHOD_STATISTICS.get(uri);
        }

        /*
         * 获取方法对应的 RpcStatus。
         *  若没有，则创建一个。
         */
        RpcStatus status = map.get(methodName);
        if (status == null) {
            map.putIfAbsent(methodName, new RpcStatus());
            status = map.get(methodName);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map != null) {
            map.remove(methodName);
        }
    }

    /**
     * 递增方法对应的激活并发数
     * @param url
     * @param methodName
     */
    public static void beginCount(URL url, String methodName) {
        beginCount(url, methodName, Integer.MAX_VALUE);
    }

    /**
     * @param url
     */
    public static boolean beginCount(URL url, String methodName, int max) {
        max = (max <= 0) ? Integer.MAX_VALUE : max;

        /**
         * 获取对应 RpcStatus
         */
        RpcStatus appStatus = getStatus(url);

        /**
         * 获取方法对应 RpcStatus {@link #getStatus(URL, String)}
         */
        RpcStatus methodStatus = getStatus(url, methodName);

        /*
         * 原子性递增方法对应激活并发数，
         *  若超过最大限制，则返回 false、否则返回 ture.
         */
        if (methodStatus.active.incrementAndGet() > max) {
            methodStatus.active.decrementAndGet();
            return false;
        } else {
            appStatus.active.incrementAndGet();
            return true;
        }
    }

    /**
     * @param url
     * @param elapsed
     * @param succeeded
     *
     *  原子性的递减方法对应的激活并发数
     */
    public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
        endCount(getStatus(url), elapsed, succeeded);
        endCount(getStatus(url, methodName), elapsed, succeeded);
    }

    private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {

        // 原子性递减激活并发数。
        status.active.decrementAndGet();

        // 调用总次数增加
        status.total.incrementAndGet();

        // 调用总耗时增加
        status.totalElapsed.addAndGet(elapsed);

        // 更新最大耗时
        if (status.maxElapsed.get() < elapsed) {
            status.maxElapsed.set(elapsed);
        }

        // 如果此次调用成功，则会更新成功调用的最大耗时
        if (succeeded) {
            if (status.succeededMaxElapsed.get() < elapsed) {
                status.succeededMaxElapsed.set(elapsed);
            }
        } else {

            // 如果此次调用失败，则会更新失败调用的最大耗时
            status.failed.incrementAndGet();
            status.failedElapsed.addAndGet(elapsed);
            if (status.failedMaxElapsed.get() < elapsed) {
                status.failedMaxElapsed.set(elapsed);
            }
        }
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * get active.
     *
     * @return active
     */
    public int getActive() {
        return active.get();
    }

    /**
     * get total.
     *
     * @return total
     */
    public long getTotal() {
        return total.longValue();
    }

    /**
     * get total elapsed.
     *
     * @return total elapsed
     */
    public long getTotalElapsed() {
        return totalElapsed.get();
    }

    /**
     * get average elapsed.
     *
     * @return average elapsed
     */
    public long getAverageElapsed() {
        long total = getTotal();
        if (total == 0) {
            return 0;
        }
        return getTotalElapsed() / total;
    }

    /**
     * get max elapsed.
     *
     * @return max elapsed
     */
    public long getMaxElapsed() {
        return maxElapsed.get();
    }

    /**
     * get failed.
     *
     * @return failed
     */
    public int getFailed() {
        return failed.get();
    }

    /**
     * get failed elapsed.
     *
     * @return failed elapsed
     */
    public long getFailedElapsed() {
        return failedElapsed.get();
    }

    /**
     * get failed average elapsed.
     *
     * @return failed average elapsed
     */
    public long getFailedAverageElapsed() {
        long failed = getFailed();
        if (failed == 0) {
            return 0;
        }
        return getFailedElapsed() / failed;
    }

    /**
     * get failed max elapsed.
     *
     * @return failed max elapsed
     */
    public long getFailedMaxElapsed() {
        return failedMaxElapsed.get();
    }

    /**
     * get succeeded.
     *
     * @return succeeded
     */
    public long getSucceeded() {
        return getTotal() - getFailed();
    }

    /**
     * get succeeded elapsed.
     *
     * @return succeeded elapsed
     */
    public long getSucceededElapsed() {
        return getTotalElapsed() - getFailedElapsed();
    }

    /**
     * get succeeded average elapsed.
     *
     * @return succeeded average elapsed
     */
    public long getSucceededAverageElapsed() {
        long succeeded = getSucceeded();
        if (succeeded == 0) {
            return 0;
        }
        return getSucceededElapsed() / succeeded;
    }

    /**
     * get succeeded max elapsed.
     *
     * @return succeeded max elapsed.
     */
    public long getSucceededMaxElapsed() {
        return succeededMaxElapsed.get();
    }

    /**
     * Calculate average TPS (Transaction per second).
     *
     * @return tps
     */
    public long getAverageTps() {
        if (getTotalElapsed() >= 1000L) {
            return getTotal() / (getTotalElapsed() / 1000L);
        }
        return getTotal();
    }


}
