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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcStatus;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.Constants.ACTIVES_KEY;

/**
 * ActiveLimitFilter restrict the concurrent client invocation for a service or service's method from client side.
 * To use active limit filter, configured url with <b>actives</b> and provide valid >0 integer value.
 * <pre>
 *     e.g. <dubbo:reference id="demoService" check="false" interface="org.apache.dubbo.demo.DemoService" "actives"="2"/>
 *      In the above example maximum 2 concurrent invocation is allowed.
 *      If there are more than configured (in this example 2) is trying to invoke remote method, then rest of invocation
 *      will wait for configured timeout(default is 0 second) before invocation gets kill by dubbo.
 * </pre>
 *
 * @see Filter
 *
 *   Dubbo 服务消费端~并发控制
 *
 *
 *    如果当激活并发量达到指定值后，当前客户端请求线程会被挂起。如果在等待超时期间激活并发请求量少了，那么阻塞的线程会被激活，然后发送请求到服务提供方；
 *    如果等待超时了，则直接抛出异常，这时服务根本就没有发送到服务提供方服务器
 *
 *
 *    备注：服务提供方并发控制 {@link ExecuteLimitFilter}
 */
@Activate(group = CONSUMER, value = ACTIVES_KEY)
public class ActiveLimitFilter extends ListenableFilter {

    private static final String ACTIVELIMIT_FILTER_START_TIME = "activelimit_filter_start_time";

    public ActiveLimitFilter() {
        super.listener = new ActiveLimitListener();
    }

    /**
     *
     *  DubboInvoker 的 invoke() 真正向远端发起 RPC 请求前，
     *      请求会先经过 ActiveLimitFilter # invoke() 方法。
     *
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        // 获取 URL 和 调用的方法名称
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();

        // 获取设置 actives 值，默认是0.和最大可用并发数。
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

        /**
         * 根据 URL 和方法名获取对应的状态对象 {@link RpcStatus#getStatus(URL, String)}
         */
        RpcStatus rpcStatus = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());

        /**
         * 判断是否超过并发限制
         *  {@link RpcStatus#beginCount(URL, String, int)}
         *      返回true,说明当前方法的激活并发数没有达到最大值。
         *      false: 已达最大值。
         */
        if (!RpcStatus.beginCount(url, methodName, max)) {
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), TIMEOUT_KEY, 0);
            long start = System.currentTimeMillis();
            long remain = timeout;

            // 过程并发限制则阻塞，当前线程 timeout 时间
            synchronized (rpcStatus) {

                /**
                 *   递增方法对应的激活并发数 {@link RpcStatus#beginCount(URL, String, int)}
                 *   false: 已达最大值。
                 */
                while (!RpcStatus.beginCount(url, methodName, max)) {
                    try {

                        /*
                         * 将当前线程，挂起 `timeout` 时间
                         */
                        rpcStatus.wait(remain);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    remain = timeout - elapsed;

                    /*
                     * 超时了还没有被唤醒则抛出异常。
                     */
                    if (remain <= 0) {
                        throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  " + invoker.getInterface().getName() + ", method: " + invocation.getMethodName() + ", elapsed: " + elapsed + ", timeout: " + timeout + ". concurrent invokes: " + rpcStatus.getActive() + ". max concurrent invoke limit: " + max);
                    }
                }
            }
        }

        invocation.setAttachment(ACTIVELIMIT_FILTER_START_TIME, String.valueOf(System.currentTimeMillis()));

        /*
         * 向下调用
         */
        return invoker.invoke(invocation);
    }

    static class ActiveLimitListener implements Listener {
        @Override
        public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
            String methodName = invocation.getMethodName();
            URL url = invoker.getUrl();
            int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

            RpcStatus.endCount(url, methodName, getElapsed(invocation), true);
            notifyFinish(RpcStatus.getStatus(url, methodName), max);
        }

        @Override
        public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
            String methodName = invocation.getMethodName();
            URL url = invoker.getUrl();
            int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

            RpcStatus.endCount(url, methodName, getElapsed(invocation), false);
            notifyFinish(RpcStatus.getStatus(url, methodName), max);
        }

        private long getElapsed(Invocation invocation) {
            String beginTime = invocation.getAttachment(ACTIVELIMIT_FILTER_START_TIME);
            return StringUtils.isNotEmpty(beginTime) ? System.currentTimeMillis() - Long.parseLong(beginTime) : 0;
        }

        private void notifyFinish(RpcStatus rpcStatus, int max) {
            if (max > 0) {
                synchronized (rpcStatus) {
                    rpcStatus.notifyAll();
                }
            }
        }
    }
}
