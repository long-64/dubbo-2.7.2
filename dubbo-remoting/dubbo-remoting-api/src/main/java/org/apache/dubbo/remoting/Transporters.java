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
package org.apache.dubbo.remoting;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.transport.ChannelHandlerAdapter;
import org.apache.dubbo.remoting.transport.ChannelHandlerDispatcher;

/**
 * Transporter facade. (API, Static, ThreadSafe)
 *
 *  门面类。
 */
public class Transporters {

    static {
        // check duplicate jar package
        Version.checkDuplicate(Transporters.class);
        Version.checkDuplicate(RemotingException.class);
    }

    private Transporters() {
    }

    public static Server bind(String url, ChannelHandler... handler) throws RemotingException {
        return bind(URL.valueOf(url), handler);
    }

    public static Server bind(URL url, ChannelHandler... handlers) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handlers == null || handlers.length == 0) {
            throw new IllegalArgumentException("handlers == null");
        }
        ChannelHandler handler;
        if (handlers.length == 1) {
            handler = handlers[0];
        } else {

            /**
             * 1、如果 handlers 元素数量大于1，则创建 ChannelHandler 分发器,
             * 2、ChannelHandlerDispatcher 也是 ChannelHandler 接口的实现类之一，
             *     维护了一个 CopyOnWriteArraySet 集合，它所有的 ChannelHandler 接口实现都会调用其中每个 ChannelHandler 元素的相应方法。
             *
             *  {@link ChannelHandlerDispatcher#ChannelHandlerDispatcher(ChannelHandler...)}
             */
            handler = new ChannelHandlerDispatcher(handlers);
        }

        /**
         *
         *  `getTransporter`
         *
         *
         *  都是创建 `NettyServer` 默认走 `Netty-4`
         *
         *  Netty-4 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter#bind(URL, ChannelHandler)}
         *  Netty-3 {@link org.apache.dubbo.remoting.transport.netty.NettyTransporter#bind(URL, ChannelHandler)}
         */
        return getTransporter().bind(url, handler);
    }

    public static Client connect(String url, ChannelHandler... handler) throws RemotingException {
        return connect(URL.valueOf(url), handler);
    }

    public static Client connect(URL url, ChannelHandler... handlers) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        ChannelHandler handler;
        if (handlers == null || handlers.length == 0) {
            handler = new ChannelHandlerAdapter();
        } else if (handlers.length == 1) {
            handler = handlers[0];
        } else {
            handler = new ChannelHandlerDispatcher(handlers);
        }

        /**
         *  创建 NettyClient 对象 {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter#connect(URL, ChannelHandler)}
         */
        return getTransporter().connect(url, handler);
    }

    /**
     *  获取 `Transporter`
     * @return
     */
    public static Transporter getTransporter() {

        /**
         *  `默认 netty4` {@link org.apache.dubbo.remoting.transport.netty4.NettyTransporter}
         */
        return ExtensionLoader.getExtensionLoader(Transporter.class).getAdaptiveExtension();
    }

}