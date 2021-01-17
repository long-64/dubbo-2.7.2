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
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import static org.apache.dubbo.rpc.Constants.PROXY_KEY;

/**
 * ProxyFactory. (API/SPI, Singleton, ThreadSafe)
 *
 *  三个方法，都添加了 @Adaptive 的自适应扩展点，所以如果调用 getInvoker 方法，都会返回一个 `ProxyFactory$Adpative`
 *
 *  【 动态生成的 ProxyFactory$Adpative 类 】
 *
 * package com.alibaba.dubbo.rpc;
 * import com.alibaba.dubbo.common.extension.ExtensionLoader;
 *
 * public class ProxyFactory$Adpative implements com.alibaba.dubbo.rpc.ProxyFactory {
 *     public com.alibaba.dubbo.rpc.Invoker getInvoker(java.lang.Object arg0, java.lang.Class arg1, com.alibaba.dubbo.common.URL arg2) throws com.alibaba.dubbo.rpc.RpcException {
 *         if (arg2 == null) throw new IllegalArgumentException("url == null");
 *         com.alibaba.dubbo.common.URL url = arg2;
 *         String extName = url.getParameter("proxy", "javassist");
 *         if(extName == null) throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.ProxyFactory) name from url(" + url.toString() + ") use keys([proxy])");
 *         com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory)ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);
 *         return extension.getInvoker(arg0, arg1, arg2);
 *     }
 *     public java.lang.Object getProxy(com.alibaba.dubbo.rpc.Invoker arg0) throws com.alibaba.dubbo.rpc.RpcException {
 *         if (arg0 == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");
 *         if (arg0.getUrl() == null) throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");com.alibaba.dubbo.common.URL url = arg0.getUrl();
 *         String extName = url.getParameter("proxy", "javassist");
 *         if(extName == null) throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.rpc.ProxyFactory) name from url(" + url.toString() + ") use keys([proxy])");
 *         com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory)ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);
 *         return extension.getProxy(arg0);
 *     }
 * }
 *
 */
@SPI("javassist")
public interface ProxyFactory {

    /**
     * create proxy.
     *
     * @param invoker
     * @return proxy
     *
     *  为传入的Invoker对象创建代理对象
     */
    @Adaptive({PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker) throws RpcException;

    /**
     * create proxy.
     *
     * @param invoker
     * @return proxy
     */
    @Adaptive({PROXY_KEY})
    <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException;

    /**
     * create invoker.
     *
     * @param <T>
     * @param proxy
     * @param type
     * @param url
     * @return invoker
     *
     *  将传入的代理对象封装成Invoker对象
     *
     */
    @Adaptive({PROXY_KEY})
    <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException;

}