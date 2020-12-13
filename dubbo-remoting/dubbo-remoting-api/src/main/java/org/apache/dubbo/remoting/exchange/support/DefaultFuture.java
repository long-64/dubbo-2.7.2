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
package org.apache.dubbo.remoting.exchange.support;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * DefaultFuture.
 */
public class DefaultFuture extends CompletableFuture<Object> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);

    // 通道缓存
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<>();

    // future 缓存
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<>();

    // 超时检查定时器
    public static final Timer TIME_OUT_TIMER = new HashedWheelTimer(
            new NamedThreadFactory("dubbo-future-timeout", true),
            30,
            TimeUnit.MILLISECONDS);

    // invoke id.
    private final Long id;
    private final Channel channel;
    private final Request request;
    private final int timeout;
    private final long start = System.currentTimeMillis();
    private volatile long sent;
    private Timeout timeoutCheckTask;

    private DefaultFuture(Channel channel, Request request, int timeout) {

        // 保存通信通道
        this.channel = channel;

        // 保存请求内容
        this.request = request;

        // 保存请求ID
        this.id = request.getId();

        // 保存超时时间
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
        // put into waiting map.

        // 当前对象保存到 缓存 FUTURES 中
        FUTURES.put(id, this);

        // 把当前 通道保存到 缓存 CHANNELS 中。
        CHANNELS.put(id, channel);
    }

    /**
     * check time out of the future
     */
    private static void timeoutCheck(DefaultFuture future) {

        /**
         * 创建一个任务 {@link TimeoutCheckTask#TimeoutCheckTask(Long)}
         *
         *  core function {@link TimeoutCheckTask#run(Timeout)}
         */
        TimeoutCheckTask task = new TimeoutCheckTask(future.getId());
        future.timeoutCheckTask = TIME_OUT_TIMER.newTimeout(task, future.getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * init a DefaultFuture
     * 1.init a DefaultFuture
     * 2.timeout check
     *
     * @param channel channel
     * @param request the request
     * @param timeout timeout
     * @return a new DefaultFuture
     */
    public static DefaultFuture newFuture(Channel channel, Request request, int timeout) {

        /**
         * 创建 defaultFuture 实例 {@link DefaultFuture#DefaultFuture(Channel, Request, int)}
         */
        final DefaultFuture future = new DefaultFuture(channel, request, timeout);
        // timeout check

        /**
         * 超时检查
         */
        timeoutCheck(future);
        return future;
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    public static void sent(Channel channel, Request request) {
        DefaultFuture future = FUTURES.get(request.getId());
        if (future != null) {
            future.doSent();
        }
    }

    /**
     * close a channel when a channel is inactive
     * directly return the unfinished requests.
     *
     * @param channel channel to close
     */
    public static void closeChannel(Channel channel) {
        for (Map.Entry<Long, Channel> entry : CHANNELS.entrySet()) {
            if (channel.equals(entry.getValue())) {
                DefaultFuture future = getFuture(entry.getKey());
                if (future != null && !future.isDone()) {
                    Response disconnectResponse = new Response(future.getId());
                    disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
                    disconnectResponse.setErrorMessage("Channel " +
                            channel +
                            " is inactive. Directly return the unFinished request : " +
                            future.getRequest());
                    DefaultFuture.received(channel, disconnectResponse);
                }
            }
        }
    }

    public static void received(Channel channel, Response response) {
        received(channel, response, false);
    }

    public static void received(Channel channel, Response response, boolean timeout) {
        try {

            // 从 FUTURES 中移除ID，对应的 future 对象。
            DefaultFuture future = FUTURES.remove(response.getId());
            if (future != null) {
                Timeout t = future.timeoutCheckTask;
                if (!timeout) {
                    // decrease Time
                    t.cancel();
                }

                /**
                 *  把响应结果设置到 DefaultFuture 结果变量 response {@link DefaultFuture#doReceived(Response)}
                 */
                future.doReceived(response);
            } else {
                logger.warn("The timeout response finally returned at "
                        + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                        + ", response " + response
                        + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
                        + " -> " + channel.getRemoteAddress()));
            }
        } finally {

            // 从缓存 CHANNELS 中移除请求ID，对应的通道
            CHANNELS.remove(response.getId());
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        Response errorResult = new Response(id);
        errorResult.setStatus(Response.CLIENT_ERROR);
        errorResult.setErrorMessage("request future has been canceled.");
        this.doReceived(errorResult);
        FUTURES.remove(id);
        CHANNELS.remove(id);
        return true;
    }

    public void cancel() {
        this.cancel(true);
    }


    private void doReceived(Response res) {
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }

        // 调用成功
        if (res.getStatus() == Response.OK) {
            this.complete(res.getResult());
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            this.completeExceptionally(new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage()));
        } else {
            this.completeExceptionally(new RemotingException(channel, res.getErrorMessage()));
        }
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())) + ","
                + (sent > 0 ? " client elapsed: " + (sent - start)
                + " ms, server elapsed: " + (nowTimestamp - sent)
                : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: "
                + timeout + " ms, request: " + request + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    private static class TimeoutCheckTask implements TimerTask {

        private final Long requestID;

        TimeoutCheckTask(Long requestID) {
            this.requestID = requestID;
        }

        @Override
        public void run(Timeout timeout) {

            // 如果 future 为 null，或者 Future 任务已经完成，则返回。
            DefaultFuture future = DefaultFuture.getFuture(requestID);
            if (future == null || future.isDone()) {
                return;
            }
            // create exception response.

            // 创建相应对象。
            Response timeoutResponse = new Response(future.getId());
            // set timeout status.

            // 设置超时状态
            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
            // handle response.

            /**
             * 超时响应信息设置到 future 内的通道 {@link DefaultFuture#received(Channel, Response, boolean)}
             */
            DefaultFuture.received(future.getChannel(), timeoutResponse, true);

        }
    }
}
