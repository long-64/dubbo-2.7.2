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
import org.apache.dubbo.rpc.support.RpcUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * ConsistentHashLoadBalance
 *
 *  一致哈希，负载均衡
 *   引入hash 槽，避免大量的请求到同一个点，其他节点只收到少许请求，数据倾斜。
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "consistenthash";

    /**
     * Hash nodes name
     */
    public static final String HASH_NODES = "hash.nodes";

    /**
     * Hash arguments name
     */
    public static final String HASH_ARGUMENTS = "hash.arguments";

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        // 获取调用的方法名称
        String methodName = RpcUtils.getMethodName(invocation);

        // 将ServiceKey和方法拼接起来，构成一个key
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;

        // 注意：这是为了在invokers列表发生变化时都会重新生成ConsistentHashSelector对象
        int identityHashCode = System.identityHashCode(invokers);

        // 根据key获取对应的ConsistentHashSelector对象
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        if (selector == null || selector.identityHashCode != identityHashCode) {

            /**
             *  没有找到，则进入创建 `ConsistentHashSelector` {@link ConsistentHashSelector#ConsistentHashSelector(List, String, int)}
             */
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }

        /**
         *  选择一个服务提供者 {@link ConsistentHashSelector#select(Invocation)}
         */
        return selector.select(invocation);
    }

    private static final class ConsistentHashSelector<T> {

        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        private final int replicaNumber;

        private final int identityHashCode;

        private final int[] argumentIndex;

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {

            // 初始化virtualInvokers字段，也就是虚拟Hash槽
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();

            // 记录Invoker集合的hashCode，用该hashCode值来判断Provider列表是否发生了变化
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();

            // 获取设置的虚拟节点个数，默认为 160
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);

            // 获取参与Hash计算的参数下标值，默认对第一个参数进行Hash运算
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }

            // 外层轮询40次，内层轮询4次，共40*4=160次，也就是同一节点虚拟出160个槽位
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {

                    // 对address + i进行md5运算，得到一个长度为16的字节数组
                    byte[] digest = md5(address + i);
                    for (int h = 0; h < 4; h++) {

                        /***************************************************
                         *
                         *  h = 0 时，取 digest 中下标为 0~3 的 4 个字节进行位运算
                         *  h = 1 时，取 digest 中下标为 4~7 的 4 个字节进行位运算
                         *  h = 2 和 h = 3时，过程同上
                         */
                        long m = hash(digest, h);

                        /**
                         *  维护 hash节点与 Invoker 关系，（使用 TreeMap 为排序）
                         */
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        /**
         * 选择一个服务提供者
         * @param invocation
         * @return
         */
        public Invoker<T> select(Invocation invocation) {

            // 获取参与一致性 Hash 算法的 key, 默认是第一个参数。
            String key = toKey(invocation.getArguments());

            // 根据具体算法计算，改 key 对应的Md5 值。
            byte[] digest = md5(key);

            // 计算改 key, 对应 Hash 环上哪一个点，并选择该点对应的 服务提供者。
            return selectForKey(hash(digest, 0));
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {

            // 从virtualInvokers集合（TreeMap是按照Key排序的）中查找第一个节点值大于或等于传入Hash值的Invoker对象
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);

            // 如果Hash值大于Hash环中的所有Invoker，则回到Hash环的开头，返回第一个Invoker对象
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue();
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }

}
