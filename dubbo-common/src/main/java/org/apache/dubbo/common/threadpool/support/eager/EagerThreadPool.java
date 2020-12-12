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

package org.apache.dubbo.common.threadpool.support.eager;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ALIVE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CORE_THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_ALIVE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_CORE_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_QUEUES;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREAD_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.QUEUES_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

/**
 * EagerThreadPool
 * When the core threads are all in busy,
 * create new thread instead of putting task into blocking queue.
 *
 *   在这个线程池中，当所有核心线程都处于忙碌状态时，将创建新的线程来执行新任务。而不把任务放入线程池阻塞队列
 */
public class EagerThreadPool implements ThreadPool {

    @Override
    public Executor getExecutor(URL url) {

        // 线程名字
        String name = url.getParameter(THREAD_NAME_KEY, DEFAULT_THREAD_NAME);

        // 核心线程数
        int cores = url.getParameter(CORE_THREADS_KEY, DEFAULT_CORE_THREADS);

        // 最大线程数
        int threads = url.getParameter(THREADS_KEY, Integer.MAX_VALUE);

        // 队列大小
        int queues = url.getParameter(QUEUES_KEY, DEFAULT_QUEUES);

        // 获取线程池队列线程空闲多少时间被回收
        int alive = url.getParameter(ALIVE_KEY, DEFAULT_ALIVE);

        // init queue and executor

        /**
         * 初始化 自定义线程池和队列
         */
        TaskQueue<Runnable> taskQueue = new TaskQueue<Runnable>(queues <= 0 ? 1 : queues);

        /**
         *  EagerThreadPoolExecutor 和 ThreadPoolExecutor 的区别
         *
         *  【core】 {@link EagerThreadPoolExecutor#EagerThreadPoolExecutor(int, int, long, TimeUnit, TaskQueue, ThreadFactory, RejectedExecutionHandler)}
         */
        EagerThreadPoolExecutor executor = new EagerThreadPoolExecutor(cores,
                threads,
                alive,
                TimeUnit.MILLISECONDS,
                taskQueue,
                new NamedInternalThreadFactory(name, true),
                new AbortPolicyWithReport(name, url));

        /**
         * {@link TaskQueue#setExecutor(EagerThreadPoolExecutor)}
         */
        taskQueue.setExecutor(executor);
        return executor;
    }
}
