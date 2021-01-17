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
package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * InvokerHandler
 */
public class InvokerInvocationHandler implements InvocationHandler {
    private static final Logger logger = LoggerFactory.getLogger(InvokerInvocationHandler.class);

    // Invoker 不是一个简单的 DubboInvoker 对象，而是在 DubboInvoker 之上经过一系列装饰器修饰的 Invoker 对象
    private final Invoker<?> invoker;

    public InvokerInvocationHandler(Invoker<?> handler) {
        this.invoker = handler;
    }

    /**
     * 如果是tostring、hashcode、equals，就直接返回
     * @param proxy
     * @param method
     * @param args
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();

        // 对于Object中定义的方法，直接调用Invoker对象的相应方法即可
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(invoker, args);
        }
        if ("toString".equals(methodName) && parameterTypes.length == 0) {
            return invoker.toString();
        }
        if ("hashCode".equals(methodName) && parameterTypes.length == 0) {
            return invoker.hashCode();
        }
        if ("equals".equals(methodName) && parameterTypes.length == 1) {
            return invoker.equals(args[0]);
        }

        /**
         *  1、是否客户端强制配置了mock调用，那么在这种场景中主要可以用来解决服务端还没开发好的时候直接使用本地数据进行测试
         *  2、是否出现了异常，如果出现异常则使用配置好的Mock类来实现服务的降级
         *
         * 【 core】{@link org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker#invoke(Invocation)}
         *
         *  ( RpcInvocation ) 是将所有请求参数都会转换为RpcInvocation
         */
        return invoker.invoke(new RpcInvocation(method, args)).recreate();
    }
}
