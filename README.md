# 什么是Hystrix

前面已经讲完了 Feign 和 Ribbon，今天我们来研究 Netflix 团队开发的另一个类库--Hystrix。

从抽象层面看，**Hystrix 是一个保护器**。它可以保护我们的应用不会因为某个依赖的故障而 down 掉。

目前，官方已不再迭代 Hystrix，一方面是认为 Hystrix 已经足够稳定了，另一方面是转向了更具弹性的保护器（而不是根据预先配置来启用保护），例如 resilience4j。当然，停止迭代并不是说 Hystrix 已经没有价值，它的很多思想仍值得学习和借鉴。

![zzs_hystrix_001.png](https://img2020.cnblogs.com/blog/1731892/202111/1731892-20211117150832774-1264329362.png)

和之前一样，本文研究的 Hystrix 是原生的，而不是被 Spring 层层封装的。

# Hystrix解决了什么问题

关于这个问题，官方已经给了详细的答案（见文末链接的官方 wiki）。这里我结合着给出自己的一些理解（下面的图也是借用官方的）。

我们的应用经常需要去调用某些依赖。这里说的依赖，一般是远程服务，那为什么不直接说远程服务呢？因为 Hystrix 适用的场景要更宽泛一些，当我们学完 Hystrix 就会发现，即使是应用里调用的普通方法也可以算是依赖。

![zzs_hystrix_002.png](https://img2020.cnblogs.com/blog/1731892/202111/1731892-20211117150850005-863043249.png)

调用这些依赖，有可能会遇到异常：**调用失败或调用超时**。

先说说调用失败。当某个依赖 down 掉时，我们的应用调用它都会失败。针对这种情况，我们会考虑快速失败，从而减少大量调用失败的开销。

![zzs_hystrix_003.png](https://img2020.cnblogs.com/blog/1731892/202111/1731892-20211117150905726-1976053032.png)

再说说调用超时。不同于调用失败，这个时候依赖还是可用的，只是需要花费更多的时间来获取我们想要的东西。当流量比较大时，线程池将很快被耗尽。在大型的项目中，一个依赖的超时带来的影响会被放大，甚至会导致整个系统瘫痪。所以，调用失败也需要快速失败。

![zzs_hystrix_004.png](https://img2020.cnblogs.com/blog/1731892/202111/1731892-20211117150918604-2058501856.png)

针对上面说的的异常，Hystrix 可以及时将故障的依赖隔离开，后续的调用都会快速失败，直到依赖恢复正常。

# 如何实现

调用失败或超时到达一定的阈值后，Hystrix 的保护器将被触发开启。

调用依赖之前，Hystrix 会检查保护器是否开启，如果开启会直接走 fall back，如果没有开启，才会执行调用操作。

另外，Hystrix 会定时地去检查依赖是否已经恢复，当依赖恢复时，将关闭保护器，整个调用链路又恢复正常。

当然，实际流程要更复杂一些，还涉及到了缓存、线程池等。官方提供了一张图，并给出了较为详细的描述。![zzs_hystrix_005.png](https://img2020.cnblogs.com/blog/1731892/202111/1731892-20211117150934837-2096935625.png)

# 如何使用

这里我用具体例子来说明各个节点的逻辑，项目代码见文末链接。

## 包装为command

首先，要使用 Hystrix，我们需要将对某个依赖的调用请求包装成一个 command，具体通过继承`HystrixCommand` 或 `HystrixObservableCommand`进行包装。继承后我们需要做三件事：

1. **在构造中指定 commandKey 和 commandGroupKey**。需要注意的是，**相同 commandGroupKey 的 command 会共用一个线程池，相同 commandKey 的会共用一个保护器和缓存**。例如，我们需要根据用户 id 从 UC 服务获取用户对象，可以让所有 UC 接口共用一个 commandGroupKey，而不同的接口采用不同的 commandKey。
2. **重写 run 或 construct 方法**。这个方法里放的是我们调用某个依赖的代码。我可以放调用远程服务的代码，也可以随便打印一句话，因此，我前面说过，依赖的定义可以更宽泛一些，而不仅限于远程服务。
3. **重写 getFallback 方法**。当快速失败时，就会走这个方法。

```java
public class CommandGetUserByIdFromUserService extends HystrixCommand<DataResponse<User>> {
    
    private final String userId;
    
    public CommandGetUserByIdFromUserService(String userId) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserService")) // 相同command group共用一个ThreadPool
                .andCommandKey(HystrixCommandKey.Factory.asKey("UserService_GetUserById"))// 相同command key共用一个CircuitBreaker、requestCache
               );
        this.userId = userId;
    }

    /**
     * 执行最终任务，如果继承的是HystrixObservableCommand则重写construct()
     */
    @Override
    protected DataResponse<User> run() {
        return userService.getUserById(userId);
    }
    
    /**
     * 该方法在以下场景被调用
     * 1. 最终任务执行时抛出异常；
     * 2. 最终任务执行超时;
     * 3. 断路器开启时，请求短路；
     * 4. 连接池、队列或信号量耗尽
     */
    @Override
    protected DataResponse<User> getFallback() {
        return DataResponse.buildFailure("fail or timeout");
    }
}
```

## 执行command

然后，只有执行 command，上面的图就“动起来”了。有四种方法执行 command，调用 execute() 或 observe() 会马上执行，而调用 queue() 或 toObservable() 不会马上执行，要等 future.get() 或 observable.subscribe() 时才会被执行。

```java
    @Test
    public void testExecuteWays() throws Exception {
        
        DataResponse<User> response = new CommandGetUserByIdFromUserService("1").execute();// execute()=queue().get() 同步
        LOG.info("command.execute():{}", response);
        
        Future<DataResponse<User>> future = new CommandGetUserByIdFromUserService("1").queue();//queue()=toObservable().toBlocking().toFuture() 同步
        LOG.info("command.queue().get():{}", future.get());
        
        Observable<DataResponse<User>> observable = new CommandGetUserByIdFromUserService("1").observe();//hot observable 异步
        
        observable.subscribe(x -> LOG.info("command.observe():{}", x));
        
        Observable<DataResponse<User>> observable2 = new CommandGetUserByIdFromUserService("1").toObservable();//cold observable 异步
        
        observable2.subscribe(x -> LOG.info("command.toObservable():{}", x));
    }
```

## 是否使用缓存

接着，进入 command 的逻辑后，Hystrix 会先判断是否使用缓存。

默认情况下，缓存是禁用的，我们可以通过重写 command 的 getCacheKey() 来开启（只要返回非空，都会开启）。

```java
    @Override
    protected String getCacheKey() {
        return userId;
    }
```

需要注意一点，用到缓存(HystrixRequestCache)、请求日志(HystrixRequestLog)、批处理（HystrixCollapser）时需要初始化HystrixRequestContext，并按以下 try...finally 格式调用：

```java
    @Test
    public void testCache() {
        HystrixRequestContext context = HystrixRequestContext.initializeContext();
        try {
            CommandGetUserByIdFromUserService command1 = new CommandGetUserByIdFromUserService("1");
            command1.execute();
            // 第一次调用时缓存里没有
            assertFalse(command1.isResponseFromCache());
            
            CommandGetUserByIdFromUserService command2 = new CommandGetUserByIdFromUserService("1");
            command2.execute();
            // 第二次调用直接从缓存拿结果
            assertTrue(command2.isResponseFromCache());
        } finally {
            context.shutdown();
        }
        // zzs001
    }
```

## 保护器是否开启

接着，Hystrix 会判断保护器是否开启。

这里我在 command 的 run 方法中手动制造 fail 或 time out。另外，我们可以通过 HystrixCommandProperties 调整保护器开启的阈值。

```java
    public CommandGetUserByIdFromUserService(String userId) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserService")) // 相同command group共用一个ThreadPool
                .andCommandKey(HystrixCommandKey.Factory.asKey("UserService_GetUserById"))// 相同command key共用一个CircuitBreaker、requestCache
                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                        .withCircuitBreakerRequestVolumeThreshold(10)
                        .withCircuitBreakerErrorThresholdPercentage(50)
                        .withMetricsHealthSnapshotIntervalInMilliseconds(1000)
                        .withExecutionTimeoutInMilliseconds(1000)
                        ));
        this.userId = userId;
    }
    @Override
    protected DataResponse<User> run() {
        LOG.info("执行最终任务，线程为：{}", Thread.currentThread());
        // 手动制造超时
        /*try {
            Thread.sleep(1200);
        } catch(InterruptedException e) {
            e.printStackTrace();
        }*/
        // 手动制造异常
        throw new RuntimeException("");
        //return UserService.instance().getUserById(userId);
    }
```

这个时候，当调用失败达到一定阈值后，保护器被触发开启，后续的请求都会直接走 fall back。

```java
    @Test
    public void testCircuitBreaker() {
        CommandGetUserByIdFromUserService command;
        int count = 1;
        do {
            command = new CommandGetUserByIdFromUserService("1");
            command.execute();
            count++;
        } while(!command.isCircuitBreakerOpen());
        LOG.info("调用{}次之后，断路器开启", count);
        
        // 这个时候再去调用，会直接走fall back
        command = new CommandGetUserByIdFromUserService("1");
        command.execute();
        assertTrue(command.isCircuitBreakerOpen());
    }
```

## 连接池、队列或信号量是否耗尽

即使保护器是关闭状态，我们也不能马上调用依赖，需要先检查连接池或信号量是否耗尽（通过 HystrixCommandProperties 可以配置使用线程池还是信号量）。

因为默认的线程池比较大，所以，这里我通过 HystrixThreadPoolProperties 调小了线程池。

```java
    public CommandGetUserByIdFromUserService(String userId) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserService")) // 相同command group共用一个ThreadPool
                .andCommandKey(HystrixCommandKey.Factory.asKey("UserService_GetUserById"))// 相同command key共用一个CircuitBreaker、requestCache
                .andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter()
                        .withCoreSize(2)
                        .withMaxQueueSize(5)
                        .withQueueSizeRejectionThreshold(5)
                        ));
        this.userId = userId;
    }
```

这个时候，当线程池耗尽后，后续的请求都会直接走 fall back，而保护器并没有开启。

```java
    @Test
    public void testThreadPoolFull() throws InterruptedException {
        
        int maxRequest = 100;
        
        int i = 0;
        do {
            CommandGetUserByIdFromUserService command = new CommandGetUserByIdFromUserService("1");
            command.toObservable().subscribe(v -> LOG.info("non-blocking command.toObservable():{}", v));
            LOG.info("是否线程池、队列或信号量耗尽：{}", command.isResponseRejected());
            
        } while(i++ < maxRequest - 1);
        
        
        // 这个时候再去调用，会直接走fall back
        CommandGetUserByIdFromUserService command = new CommandGetUserByIdFromUserService("1");
        command.execute();
        // 线程池、队列或信号量耗尽
        assertTrue(command.isResponseRejected());
        assertFalse(command.isCircuitBreakerOpen());
        
        Thread.sleep(10000);
        // zzs001
    }
```

# 结语

以上简单地讲完了 Hystrix。阅读官方的 wiki，再结合上面的几个例子，相信大家可以对 Hystrix 有较深的了解。

最后，感谢阅读，欢迎私信交流。

# 参考资料

[Home · Netflix/Hystrix Wiki · GitHub](https://github.com/Netflix/Hystrix/wiki)

> 相关源码请移步：https://github.com/ZhangZiSheng001/hystrix-demo

> 本文为原创文章，转载请附上原文出处链接：https://www.cnblogs.com/ZhangZiSheng001/p/15567420.html