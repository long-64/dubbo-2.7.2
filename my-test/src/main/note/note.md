#### SPI 扩展
- `ExtensionLoader` 


##### 动态编译
- `Compiler` 默认实现类 `JavassistCompiler` 


### 服务提供方
#### 服务提供方入口
- `ServiceConfig # export()`
- `ServiceConfig # exportLocal()` 本地服务暴露流程。

#### 网络连接
##### 消费端，发起的 TCP 连接
- `AbstractServer # doOpen()` 模板方法



##### 接收用户发来的请求
- `NettyServer# connected()`
    - `最终实现类: AbstractServer # connected()`
    
    
 ### 服务消费方
 ##### 服务消费方入口
 - `ReferenceConfig # get()`
 

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
- NettyCodecAdapter