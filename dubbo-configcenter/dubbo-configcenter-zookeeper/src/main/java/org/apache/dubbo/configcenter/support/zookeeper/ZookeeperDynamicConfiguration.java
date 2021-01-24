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
package org.apache.dubbo.configcenter.support.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.configcenter.ConfigurationListener;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.apache.dubbo.configcenter.Constants.CONFIG_NAMESPACE_KEY;

/**
 *
 */
public class ZookeeperDynamicConfiguration implements DynamicConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(ZookeeperDynamicConfiguration.class);

    // 用于执行监听器的线程池
    private Executor executor;
    // The final root path would be: /configRootPath/"config"

    // 以 Zookeeper 作为配置中心时，配置也是以 ZNode 形式存储的，rootPath 记录了所有配置节点的根路径。
    private String rootPath;

    // 与 Zookeeper 集群交互的客户端。
    private final ZookeeperClient zkClient;
    private CountDownLatch initializedLatch;

    // 用于监听配置变化的监听器
    private CacheListener cacheListener;
    private URL url;


    ZookeeperDynamicConfiguration(URL url, ZookeeperTransporter zookeeperTransporter) {
        this.url = url;

        // 根据URL中的config.namespace参数(默认值为dubbo)，确定配置中心ZNode的根路径
        rootPath = "/" + url.getParameter(CONFIG_NAMESPACE_KEY, DEFAULT_GROUP) + "/config";

        // 在cacheListener注册成功之后，会调用cacheListener.countDown()方法
        initializedLatch = new CountDownLatch(1);
        this.cacheListener = new CacheListener(rootPath, initializedLatch);

        // 初始化executor字段，用于执行监听器的逻辑
        this.executor = Executors.newFixedThreadPool(1, new NamedThreadFactory(this.getClass().getSimpleName(), true));

        // 初始化Zookeeper客户端
        zkClient = zookeeperTransporter.connect(url);
        zkClient.addDataListener(rootPath, cacheListener, executor);
        try {
            // Wait for connection
            this.initializedLatch.await();
        } catch (InterruptedException e) {
            logger.warn("Failed to build local cache for config center (zookeeper)." + url);
        }
    }

    /**
     * @param key e.g., {service}.configurators, {service}.tagrouters, {group}.dubbo.properties
     * @return
     */
    @Override
    public Object getInternalProperty(String key) {
        return zkClient.getContent(key);
    }

    /**
     * For service governance, multi group is not supported by this implementation. So group is not used at present.
     */
    @Override
    public void addListener(String key, String group, ConfigurationListener listener) {
        cacheListener.addListener(key, listener);
    }

    @Override
    public void removeListener(String key, String group, ConfigurationListener listener) {
        cacheListener.removeListener(key, listener);
    }

    @Override
    public String getConfig(String key, String group, long timeout) throws IllegalStateException {
        /**
         * when group is not null, we are getting startup configs from Config Center, for example:
         * group=dubbo, key=dubbo.properties
         */
        if (StringUtils.isNotEmpty(group)) {
            key = group + "/" + key;
        }
        /**
         * when group is null, we are fetching governance rules, for example:
         * 1. key=org.apache.dubbo.DemoService.configurators
         * 2. key = org.apache.dubbo.DemoService.condition-router
         */
        else {
            int i = key.lastIndexOf(".");
            key = key.substring(0, i) + "/" + key.substring(i + 1);
        }

        return (String) getInternalProperty(rootPath + "/" + key);
    }

    /**
     * For zookeeper, {@link #getConfig(String, String, long)} and {@link #getConfigs(String, String, long)} have the same meaning.
     *
     * @param key
     * @param group
     * @param timeout
     * @return
     * @throws IllegalStateException
     */
    @Override
    public String getConfigs(String key, String group, long timeout) throws IllegalStateException {
        return (String) getConfig(key, group, timeout);
    }
}
