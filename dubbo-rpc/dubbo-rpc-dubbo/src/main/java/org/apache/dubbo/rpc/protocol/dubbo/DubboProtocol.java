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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;


/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;

    /**
     * <host:port,Exchanger>
     */
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<>();
    /**
     * <host:port,Exchanger>
     */
    private final Map<String, List<ReferenceCountExchangeClient>> referenceClientMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> optimizers = new ConcurrentHashSet<>();
    /**
     * consumer side export a stub service for dispatching event
     * servicekey-stubmethods
     */
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<>();

    /*
     * DubboProtocol 暴露的 ProtocolServer 收到请求时，经过一系列解码处理，最终会到达 DubboProtocol.requestHandler 这个 ExchangeHandler 对象中，
     *      该 ExchangeHandler 对象会从 exporterMap 集合中取出请求的 Invoker，并调用其 invoke() 方法处理请求。
     */
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        /**
         *
         *  received 委托给 `reply` 方法来执行。
         *      如果执行结果已经完成，则直接将结果写回消费端。否则使用异步回调方式（避免当前线程被阻塞）等执行完毕并拿到结果再把结果写回消费端。
         *
         * @param channel
         * @param message
         * @return
         * @throws RemotingException
         */
        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            // 这里省略了检查message类型的逻辑，通过前面Handler的处理，这里收到的 message 必须是 Invocation 类型的对象
            Invocation inv = (Invocation) message;

            /**
             *  获取调用方对应的 Invoker {@link DubboProtocol#getInvoker(Channel, Invocation)}
             */
            Invoker<?> invoker = getInvoker(channel, inv);
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }

            // 将客户端的地址记录到RpcContext中
            RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());

            /**
             *  【 真正调用 】执行 Invoker 调用链 {@link org.apache.dubbo.rpc.proxy.AbstractProxyInvoker#invoke(Invocation)}
             */
            Result result = invoker.invoke(inv);

            // 返回结果
            return result.completionFuture().thenApply(Function.identity());
        }

        /**
         *
         *  received 事件被传递到线程池后进行异步处理。
         *
         * @param channel
         * @param message
         * @throws RemotingException
         */
        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {

                /**
                 *  【 reply 】 {@link #reply(ExchangeChannel, Object)}
                 */
                reply((ExchangeChannel) channel, message);

            } else {

                /**
                 *  线程池任务被激活后调用 {@link org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeHandler#received(Channel, Object)}
                 */
                super.received(channel, message);
            }
        }

        /**
         *  Dubbo 服务端提供方，如何处理请求（Netty # connected）
         *
         * @param channel
         * @throws RemotingException
         */
        @Override
        public void connected(Channel channel) throws RemotingException {

            /**
             * 核心实现 调用 {@link #invoke(Channel, String)}
             */
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {

            /**
             * 创建 `Invocation` 对象，{@link #createInvocation(Channel, URL, String)}
             */
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {

                    /**
                     * {@link #received(Channel, Object)}
                     */
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private Invocation createInvocation(Channel channel, URL url, String methodKey) {

            // URL 中不包含 key 直接返回
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            // 根据 method 创建 RpcInvocation.
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getParameter(GROUP_KEY));
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getParameter(VERSION_KEY));
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            // load
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
        }

        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = inv.getAttachments().get(PATH_KEY);

        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }

        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(port, path, inv.getAttachments().get(VERSION_KEY), inv.getAttachments().get(GROUP_KEY));
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);
        }

        // 查找不到相应的 DubboExporter 对象时，会直接抛出异常，这里省略了这个检测
        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     *  Dubbo 协议 invoker 转 Exporter
     *
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();

        // export service.
        //  KEY 格式：  ${group}/copm.gupaoedu.practice.dubbo.ISayHelloService:${version}:20880
        String key = serviceKey(url);

        /**
         * 将上层传入的Invoker对象封装成 DubboExporter对象，然后记录到 `exporterMap` 集合中 {@link DubboExporter#DubboExporter(Invoker, String, Map)}
         */
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);

        /**
         * 记录 `exporterMap` {@link AbstractProtocol#exporterMap}
         */
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }

        /**
         * 同一机器的不同服务导出只会开启一个 NettyServer {@link #openServer(URL)}
         */
        openServer(url);

        /**
         *  进行序列化的优化处理。{@link #optimizeSerialization(URL)}
         */
        optimizeSerialization(url);

        return exporter;
    }

    /**
     * 启动 Netty 服务。(同一个机器,的不同服务, 只会开启一个 Netty-Server
     * @param url
     */
    private void openServer(URL url) {
        // find server.

        // 提供者机器的地址 ip:port
        String key = url.getAddress();
        //client can export a service which's only for server to invoke

        // client 也可以暴露一个只有server可以调用的服务。
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (isServer) {

            //是否在serverMap中缓存了
            ExchangeServer server = serverMap.get(key);
            if (server == null) {

                // DoubleCheck，防止并发问题
                synchronized (this) {

                    //是否在serverMap中缓存了
                    server = serverMap.get(key);
                    if (server == null) {

                        /**
                         * 【核心】创建服务器实例 {@link #createServer(URL)}
                         */
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // server supports reset, use together with override

                /**
                 * 如果已有 ProtocolServer 实例，则尝试根据URL信息重置 ProtocolServer {@link org.apache.dubbo.remoting.exchange.support.header.HeaderExchangeServer#reset(URL)}
                 */
                server.reset(url);
            }
        }
    }

    /**
     * 每台机器的 ip + port 是唯一的，所以多个不同服务启动时，第一个会被创建，后面服务直接从缓存中获取。
     * @param url
     * @return
     */
    private ExchangeServer createServer(URL url) {

        //组装url，在url中添加心跳时间、编解码参数
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default

                //默认开启server关闭时发送readonly事件
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default

                // 启动心跳配置 （默认: 60s ）
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))

                /**
                 * codes_key: dubbo
                 *
                 *  第一个是创建 ExchangeServer 时 使用 {@link DubboCountCodec#decode(Channel, ChannelBuffer)} 进行解码。
                 *
                 *  获取解码器。[ 构造函数 codec ] {@link org.apache.dubbo.remoting.transport.AbstractEndpoint#AbstractEndpoint(URL, ChannelHandler)}
                 */
                .addParameter(CODEC_KEY, DubboCodec.NAME)
                .build();
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);

        // 通过 SPI 检测是否存在 server 参数所代表的 Transporter 拓展，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {

            /**
             *  【 通过 Exchangers门面类，创建 ExchangeServer 对象 】{@link Exchangers#bind(URL, ExchangeHandler)}
             */
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        str = url.getParameter(CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        return server;
    }

    private void optimizeSerialization(URL url) throws RpcException {

        // 根据URL中的optimizer参数值，确定SerializationOptimizer接口的实现类
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            // 创建SerializationOptimizer实现类的对象
            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            // 调用getSerializableClasses()方法获取需要注册的类
            for (Class c : optimizer.getSerializableClasses()) {

                /**
                 * 注册 {@link SerializableClassRegistry#registerClass(Class)}
                 */
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    /**
     * 该方法底层的 ExchangeClient 集合封装成 DubboInvoker，然后由上层逻辑封装成代理对象，这样业务层就可以像调用本地 Bean 一样，完成远程调用。
     */
    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {

        /**
         * 进行序列化优化，注册需要优化的类 {@link #optimizeSerialization(URL)}
         */
        optimizeSerialization(url);

        // create rpc invoker.
        /**
         *  {@link #getClients(URL)}  方法创建服务消费端 NettyClient 对象。
         *
         *   {@link DubboInvoker#DubboInvoker(Class, URL, ExchangeClient[])} 创建 `DubboInvoker` 对象
         */
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);

        // 创建 `DubboInvoker` 对象添加到invoker集合之中
        invokers.add(invoker);

        return invoker;
    }

    /**
     *  注意三点
     *  1、由于同一服务提供者可以提供多个服务，那么消费者机器需要与同一个服务提供者机器提供的多个服务共享链接。还是与每个服务都建立一个连接。
     *  2、消费端启动时就与服务提供者机器建立好连接？
     *      1、服务是否是惰性连接 `lazy`, false: 是及时连接。
     *  3、每个服务消费端与服务提供者集群中的所有集群都有连接？
     *
     *
     * @param url
     * @return
     */
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection

        // 不同服务是否共享链接
        boolean useShareConnect = false;

        // CONNECTIONS_KEY参数值决定了后续建立连接的数量
        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        List<ReferenceCountExchangeClient> shareClients = null;
        // if not configured, connection is shared, otherwise, one connection for one service

        // 如果没有配置，则默认链接是共享的，否则每个服务单独有自己的链接。
        if (connections == 0) {
            useShareConnect = true;

            /**
             * The xml configuration should have a higher priority than properties.
             *
             * 确定建立共享连接的条数，默认只建立一条共享连接
             */
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);

            /**
             *
             * 共享连接:
             *
             *  Provider 1 暴露了多个服务，Consumer 引用了 Provider 1 中的多个服务，
             *      共享连接是说 Consumer 调用 Provider 1 中的多个服务时，是通过固定数量的共享 TCP 长连接进行数据传输，这样就可以达到减少服务端连接数的目的。
             *
             *
             * 获取共享 NettyClient {@link #getSharedClient(URL, int)}
             */
            shareClients = getSharedClient(url, connections);
        }

        // 整理要返回的 ExchangeClient 集合
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            // 共享则返回已经存在
            if (useShareConnect) {
                clients[i] = shareClients.get(i);

            } else {

                /**
                 *  创建独享连接 {@link #initClient(URL)}
                 *  最终调用 {@link org.apache.dubbo.remoting.transport.netty4.NettyClient#NettyClient(URL, ChannelHandler)}
                 */
                clients[i] = initClient(url);
            }
        }

        return clients;
    }

    /**
     * Get shared connection
     *
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {

        // 获取对端的地址(host:port)
        String key = url.getAddress();

        // 获取带有“引用计数”功能的 ExchangeClient
        List<ReferenceCountExchangeClient> clients = referenceClientMap.get(key);

        // checkClientCanUse()方法中会检测 clients 集合中的客户端是否全部可用
        if (checkClientCanUse(clients)) {

            // 客户端全部可用时
            batchClientRefIncr(clients);
            return clients;
        }

        locks.putIfAbsent(key, new Object());

        // 针对指定地址的客户端进行加锁，分区加锁可以提高并发度
        synchronized (locks.get(key)) {
            clients = referenceClientMap.get(key);
            // double check，再次检测客户端是否全部可用
            if (checkClientCanUse(clients)) {

                /**
                 * 增加应用Client的次数 {@link #batchClientRefIncr(List)}
                 */
                batchClientRefIncr(clients);
                return clients;
            }

            // connectNum must be greater than or equal to 1
            // 至少一个共享连接
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is

            // 如果当前Clients集合为空，则直接通过initClient()方法初始化所有共享客户端
            if (CollectionUtils.isEmpty(clients)) {

                /**
                 *  最终实现类 {@link ReferenceCountExchangeClient#ReferenceCountExchangeClient(ExchangeClient)}
                 */
                clients = buildReferenceCountExchangeClientList(url, connectNum);
                referenceClientMap.put(key, clients);

            } else {

                // 如果只有部分共享客户端不可用，则只需要处理这些不可用的客户端
                for (int i = 0; i < clients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = clients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        clients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    // 增加引用次数, 在 close() 方法中则会减少引用次数。
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }

            /**
             * I understand that the purpose of the remove operation here is to avoid the expired url key
             * always occupying this memory space.
             *
             *  清理locks集合中的锁对象，防止内存泄漏，如果 key对应的服务宕机或是下线，
             *  这里不进行清理的话，这个用于加锁的 `Object` 对象是无法被GC的，从而出现内存泄漏
             */
            locks.remove(key);

            return clients;
        }
    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Add client references in bulk
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        ExchangeClient exchangeClient = initClient(url);

        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * Create new connection
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.

        // 获取客户端类型，默认为 netty
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        // 添加编解码和心跳包参数到 url 中
        url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.

        // 检测客户端类型是否存在，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy

            // 如果配置了延迟创建连接的特性，则创建 LazyConnectExchangeClient
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {

                /**
                 * 创建延迟连接 {@link LazyConnectExchangeClient#LazyConnectExchangeClient(URL, ExchangeHandler)}
                 */
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {

                /**
                 *  未使用延迟连接功能，则直接创建 HeaderExchangeClient {@link Exchangers#connect(String, ExchangeHandler)}
                 */
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);

            if (server == null) {
                continue;
            }

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                // 在close()方法中，发送ReadOnly请求、阻塞指定时间、关闭底层的定时任务、关闭相关线程池，最终，会断开所有连接，关闭Server。
                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            List<ReferenceCountExchangeClient> clients = referenceClientMap.remove(key);

            if (CollectionUtils.isEmpty(clients)) {
                continue;
            }

            for (ReferenceCountExchangeClient client : clients) {
                closeReferenceCountExchangeClient(client);
            }
        }

        stubServiceMethodsMap.clear();
        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /**
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }
}
