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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 *
 *  最少活跃调用策略
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers  服务提供者个数
        int length = invokers.size();
        // The least active value of all invokers

        // 临时变量，用来暂存被调用的最少次数
        int leastActive = -1;
        // The number of invokers having the same least active value (leastActive)

        // 记录调用次数等于最小调用次数的服务提供者个数
        int leastCount = 0;
        // The index of invokers having the same least active value (leastActive)

        // 记录调用次数等于最小调用者服务提供者下标。
        int[] leastIndexes = new int[length];
        // the weight of every invokers

        // 记录每个服务提供者的权重
        int[] weights = new int[length];
        // The sum of the warmup weights of all the least active invokes

        // 记录每次次数等于最小调用次数的服务提供者的权重和
        int totalWeight = 0;
        // The weight of the first least active invoke

        // 第一次调用次数等于最小调用次数的服务提供者的权重。
        int firstWeight = 0;
        // Every least active invoker has the same weight value?

        // 所有调用次数等于最小调用次数的服务提供者的权重是否一样。
        boolean sameWeight = true;


        // Filter out all the least active invokers

        // 过滤出所有调用次数等于最小调用次数的服务提供者。
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // Get the active number of the invoke

            /**
             * 获取当前服务提供者被调用次数 {@link RpcStatus#getStatus(URL, String)}
             */
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // Get the weight of the invoke configuration. The default value is 100.

            /**
             * 计算当前服务提供者的权重，默认 100.{@link AbstractLoadBalance#getWeight(Invoker, Invocation)}
             */
            int afterWarmup = getWeight(invoker, invocation);
            // save for later use

            // 保存当前服务提供者的权重。
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number

            /*
             * 如果是第一个服务提供者，或则当前服务提供者的调用次数小于当前最小调用次数。
             */
            if (leastActive == -1 || active < leastActive) {
                // Reset the active number of the current invoker to the least active number
                leastActive = active;
                // Reset the number of least active invokers
                leastCount = 1;
                // Put the first least active invoker first in leastIndexes
                leastIndexes[0] = i;
                // Reset totalWeight
                totalWeight = afterWarmup;
                // Record the weight the first least active invoker
                firstWeight = afterWarmup;
                // Each invoke has the same weight (only one invoker here)
                sameWeight = true;
                // If current invoker's active value equals with leaseActive, then accumulating.

                /*
                 * 如果当前提供者的被调用次数，等于当前最小调用次数
                 */
            } else if (active == leastActive) {
                // Record the index of the least active invoker in leastIndexes order

                // 记录调用次数等于最小调用次数的服务提供者的小标
                leastIndexes[leastCount++] = i;
                // Accumulate the total weight of the least active invoker

                // 累加所有的权重。
                totalWeight += afterWarmup;
                // If every invoker has the same weight?

                // 所有 Invoker 是否权重都一样。
                if (sameWeight && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // Choose an invoker from all the least active invokers

        // 如果是一个最小调用次数的 Invoker 则直接返回。
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }

        /*
         * 如果最小调用次数，Invoker 有多个 并且权重不一样。
         */
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on 
            // totalWeight.
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.

        /*
         * 如果最小调用次数的 invoker 有多个并且权重一样。在多个最少调用次数 Invoker 中随机一个。
         */
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}