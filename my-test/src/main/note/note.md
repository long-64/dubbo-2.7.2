#### SPI 扩展
- `ExtensionLoader` 


##### 动态编译
- `Compiler` 默认实现类 `JavassistCompiler` 


#### Dubbo、Spring 整合
- `DubboNamespaceHandler` 扩展 Schema

### 服务提供方 （服务暴露）
#### 服务提供方入口
- `ServiceBean` 实现 `ApplicationListener` 接口。
    - 兼容容器刷新事件 `onApplicationEvent(ContextRefreshedEvent event)`
- `ServiceConfig # export()`
- `ServiceConfig # exportLocal()` 本地服务暴露流程。

##### 接收用户发来的请求
- `NettyServer# connected()`
    - `最终实现类: AbstractServer # connected()`

##### 消费方接收服务端发送的执行结果。
- `NettyHandler# messageReceived()`


#### 网络连接
##### 消费端，发起的 TCP 连接
- `AbstractServer # doOpen()` 模板方法
 
 ### 服务消费方
 ##### 服务消费方入口
 - `ReferenceConfig # get()`



#### 集群容错（Cluster）
- `FailfastClusterInvoker` 快速失败策略。



#### 负载均衡（LoadBalance）
- `RandomLoadBalance` 随机选择

#### 线程模式
- `Dispatcher 接口` 默认: `AllDispatcher`

#### 线程池
- `ThreadPool 接口` 默认：


#### 泛化
- 服务消费端泛化 `GenericImplFilter`
- 服务提供端泛化 `GenericFilter`

#### 并发控制
- 服务消费端并发控制 ActiveLimitFilter
- 服务台消费端并发控制 ExecuteLimitFilter 


#### 消费方编码原理
- `NettyCodecAdapter # encode()`


#### 时间轮
- TimerTask.
- `核心` HashedWheelTimer$Worker#run()
