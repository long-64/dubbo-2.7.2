#### SPI 扩展
- `ExtensionLoader` 


##### 动态编译
- `Compiler` 默认实现类 `JavassistCompiler` 


### 服务提供方
#### 服务提供方入口
- `ServiceConfig # export()`

#### 网络连接
##### 消费端，发起的 TCP 连接
- `AbstractServer # doOpen()` 模板方法



##### 接收用户发来的请求
- `NettyServer# connected()`
    - `最终实现类: AbstractServer # connected()`