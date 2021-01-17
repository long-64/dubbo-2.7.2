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

import org.apache.dubbo.common.Node;
import org.apache.dubbo.common.URL;

/**
 * Invoker. (API/SPI, Prototype, ThreadSafe)
 *
 * @see org.apache.dubbo.rpc.Protocol#refer(Class, org.apache.dubbo.common.URL)
 * @see org.apache.dubbo.rpc.InvokerListener
 * @see org.apache.dubbo.rpc.protocol.AbstractInvoker
 *
 *  dubbo 核心模型，代表可执行体。
 *
 *  它是 Dubbo 的核心模型，其它模型都向它靠扰，或转换成它，它代表一个可执行体，可向它发起 invoke 调用，它有可能是一个本地的实现，也可能是一个远程的实现，也可能一个集群实现。
 *
 *   在服务提供方，Invoker 用于调用服务提供类 （一个Wapper类）
 *      {@link org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory#getInvoker(Object, Class, URL)}
 *      
 *   在服务消费方，Invoker 用于执行远程调用  （是一个 Netty 客户端）
 *      {@link org.apache.dubbo.rpc.protocol.AbstractProtocol#refer(Class, URL)}
 */
public interface Invoker<T> extends Node {

    /**
     * get service interface.
     *
     * @return service interface.
     *
     *  服务接口
     */
    Class<T> getInterface();

    /**
     * invoke.
     *
     * @param invocation
     * @return result
     * @throws RpcException
     *
     *  进行一次调用，也有人称之为一次"会话"，你可以理解为一次调用
     */
    Result invoke(Invocation invocation) throws RpcException;

}