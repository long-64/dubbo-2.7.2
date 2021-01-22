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
package org.apache.dubbo.rpc.cluster.router.mock;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.util.ArrayList;
import java.util.List;

import static org.apache.dubbo.rpc.cluster.Constants.INVOCATION_NEED_MOCK;
import static org.apache.dubbo.rpc.cluster.Constants.MOCK_PROTOCOL;

/**
 * A specific Router designed to realize mock feature.
 * If a request is configured to use mock, then this router guarantees that only the invokers with protocol MOCK appear in final the invoker list, all other invokers will be excluded.
 *
 *      Dubbo Mock 机制相关的 Router 实现
 */
public class MockInvokersSelector extends AbstractRouter {

    public static final String NAME = "MOCK_ROUTER";
    private static final int MOCK_INVOKERS_DEFAULT_PRIORITY = Integer.MIN_VALUE;

    public MockInvokersSelector() {
        this.priority = MOCK_INVOKERS_DEFAULT_PRIORITY;
    }

    @Override
    public <T> List<Invoker<T>> route(final List<Invoker<T>> invokers,
                                      URL url, final Invocation invocation) throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }

        if (invocation.getAttachments() == null) {

            /**
             * attachments为null，会过滤掉MockInvoker，只返回正常的Invoker对象 {@link #getNormalInvokers(List)}
             */
            return getNormalInvokers(invokers);
        } else {
            String value = invocation.getAttachments().get(INVOCATION_NEED_MOCK);
            if (value == null) {

                /**
                 *  invocation.need.mock为null，会过滤掉MockInvoker，只返回正常的Invoker对象 {@link #getNormalInvokers(List)}
                 */
                return getNormalInvokers(invokers);
            } else if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {

                /**
                 *  这个方法就是将传入的invokers和设置的路由规则匹配，获得符合条件的invokers返回 {@link #getMockedInvokers(List)}
                 */
                return getMockedInvokers(invokers);
            }
        }

        // invocation.need.mock为false，则会将MockInvoker和正常的Invoker一起返回
        return invokers;
    }

    /**
     *
     * 这个方法就是将传入的invokers和设置的路由规则匹配，获得符合条件的invokers返回
     *
     * @param invokers
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> getMockedInvokers(final List<Invoker<T>> invokers) {
        if (!hasMockProviders(invokers)) {
            return null;
        }
        List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(1);
        for (Invoker<T> invoker : invokers) {
            if (invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                sInvokers.add(invoker);
            }
        }
        return sInvokers;
    }

    private <T> List<Invoker<T>> getNormalInvokers(final List<Invoker<T>> invokers) {
        if (!hasMockProviders(invokers)) {
            return invokers;
        } else {
            List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(invokers.size());
            for (Invoker<T> invoker : invokers) {
                if (!invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                    sInvokers.add(invoker);
                }
            }
            return sInvokers;
        }
    }

    private <T> boolean hasMockProviders(final List<Invoker<T>> invokers) {
        boolean hasMockProvider = false;
        for (Invoker<T> invoker : invokers) {
            if (invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                hasMockProvider = true;
                break;
            }
        }
        return hasMockProvider;
    }

}
