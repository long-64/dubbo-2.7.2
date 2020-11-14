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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * random load balance.
 *
 *  【 随机选择策略 】
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers

        // 服务提供者个数
        int length = invokers.size();
        // Every invoker has the same weight?

        // 所有服务提供者的权重是否都一样。
        boolean sameWeight = true;
        // the weight of every invokers

        // 存放所有服务提供者的权重
        int[] weights = new int[length];
        // the first invoker's weight

        /**
         * 第一个服务提供者的权重。 {@link #getWeight(Invoker, Invocation)}
         */
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // The sum of weights

        // 所有服务提供者权重之和
        int totalWeight = firstWeight;

        // 累加所有服务提供者设置的权重。
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use

            // 保存
            weights[i] = weight;
            // Sum

            // 求和
            totalWeight += weight;
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }

        /**
         * 如果所有服务提供者权重并不是都一样，并且至少有一个提供者的权重大于0，则基于总权重随机选择一个。
         */
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.

            // 基于随机值返回一个 Invoker。
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }

        /**
         * 如果所有服务提供者权重都一样。
         *  这里并没有使用 `Random` 因为 Random 在高并发下会导致大量线程竞争同一个原子变量。导致大量线程，原子自旋。从而浪费CPU 资源。
         */
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
