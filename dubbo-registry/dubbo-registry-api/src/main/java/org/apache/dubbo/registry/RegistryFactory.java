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
package org.apache.dubbo.registry;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * RegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.support.AbstractRegistryFactory
 *
 *   【 Registry 的工厂接口，负责创建 Registry 对象 】
 *
 *
 *  public class RegistryFactory$Adaptive
 *               implements RegistryFactory {
 *     public Registry getRegistry(org.apache.dubbo.common.URL arg0) {
 *         if (arg0 == null) throw new IllegalArgumentException("...");
 *         org.apache.dubbo.common.URL url = arg0;
 *
 *         // 尝试获取URL的Protocol，如果Protocol为空，则使用默认值"dubbo"
 *         String extName = (url.getProtocol() == null ? "dubbo" :
 *              url.getProtocol());
 *         if (extName == null)
 *             throw new IllegalStateException("...");
 *
 *         // 根据扩展名选择相应的扩展实现，Dubbo SPI的核心原理在下一课时深入分析
 *         RegistryFactory extension = (RegistryFactory) ExtensionLoader
 *           .getExtensionLoader(RegistryFactory.class)
 *                 .getExtension(extName);
 *         return extension.getRegistry(arg0);
 *     }
 *  }
 *
 *
 */
@SPI("dubbo")
public interface RegistryFactory {

    /**
     * Connect to the registry
     * <p>
     * Connecting the registry needs to support the contract: <br>
     * 1. When the check=false is set, the connection is not checked, otherwise the exception is thrown when disconnection <br>
     * 2. Support username:password authority authentication on URL.<br>
     * 3. Support the backup=10.20.153.10 candidate registry cluster address.<br>
     * 4. Support file=registry.cache local disk file cache.<br>
     * 5. Support the timeout=1000 request timeout setting.<br>
     * 6. Support session=60000 session timeout or expiration settings.<br>
     *
     * @param url Registry address, is not allowed to be empty
     * @return Registry reference, never return empty value
     */
    @Adaptive({"protocol"})
    Registry getRegistry(URL url);

}