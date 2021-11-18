package cn.zzs.hystrix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.Future;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;

import rx.Observable;
import rx.Observer;

class CommandGetUserByIdFromUserService extends HystrixCommand<DataResponse<User>> {
    
    private static final Logger LOG = LoggerFactory.getLogger(CommandGetUserByIdFromUserService.class);

    private final String userId;
    
    public CommandGetUserByIdFromUserService(String userId) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserService")) // 相同command group共用一个ThreadPool
                .andCommandKey(HystrixCommandKey.Factory.asKey("UserService_GetUserById"))// 相同command key共用一个CircuitBreaker、requestCache
                //.andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("UserService"))
                .andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter()
                        .withCoreSize(2)
                        .withMaxQueueSize(5)
                        .withQueueSizeRejectionThreshold(5)
                        )
                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                        //.withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)
                        .withCircuitBreakerRequestVolumeThreshold(10)
                        .withCircuitBreakerErrorThresholdPercentage(50)
                        .withMetricsHealthSnapshotIntervalInMilliseconds(1000)
                        .withExecutionTimeoutInMilliseconds(1000)
                        ));
        this.userId = userId;
    }

    /**
     * 执行最终任务，如果继承的是HystrixObservableCommand则重写construct()
     */
    @Override
    protected DataResponse<User> run() {
        LOG.info("执行最终任务，线程为：{}", Thread.currentThread());
        // 手动制造超时
        /*try {
            Thread.sleep(1000);
        } catch(InterruptedException e) {
            e.printStackTrace();
        }*/
        // 手动制造异常
        //throw new RuntimeException("");
        return UserService.instance().getUserById(userId);
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
        LOG.info("执行fall back");
        return DataResponse.buildFailure("fail or timeout");
    }
    
    
    /*@Override
    protected String getCacheKey() {
        return userId;
    }*/

}

public class UserServiceTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(UserServiceTest.class);
    
    
    /**
     * <pre>
     * K             value   = command.execute();         //=queue().get()
     * Future<K>     fValue  = command.queue();           //=toObservable().toBlocking().toFuture()
     * Observable<K> ohValue = command.observe();         //hot observable
     * Observable<K> ocValue = command.toObservable();    //cold observable
     * </pre>
     * @author zzs
     * @date 2021年11月11日 上午9:09:23
     * @throws Exception
     */
    @Test
    public void testExecuteWays() throws Exception {
        
        DataResponse<User> response = new CommandGetUserByIdFromUserService("1").execute();// execute()=queue().get()
        LOG.info("command.execute():{}", response);
        
        
        Future<DataResponse<User>> future = new CommandGetUserByIdFromUserService("1").queue();//queue()=toObservable().toBlocking().toFuture()
        LOG.info("command.queue().get():{}", future.get());
        
        //Observable<DataResponse<User>> observable = new CommandGetUserByIdFromUserService("1").observe();//hot observable
        Observable<DataResponse<User>> observable = new CommandGetUserByIdFromUserService("1").toObservable();//cold observable
        
        // blocking
        LOG.info("blocking command.toObservable():{}", observable.toBlocking().single());
        
        // non-blocking 
        observable.subscribe(x -> LOG.info("non-blocking command.toObservable():{}", x));
        /*observable.subscribe(new Observer<DataResponse<User>>() {
            @Override
            public void onCompleted() {
                // nothing needed here
            }
            
            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
            }
            
            @Override
            public void onNext(DataResponse<User> v) {
                LOG.info("non-blocking command.toObservable():{}", v);
            }
        });*/
    }
    
    /**
     * 首先，需要重写com.netflix.hystrix.AbstractCommand.getCacheKey()方法
     * <pre>
     * @Override
     * protected String getCacheKey() {
     *    return userId;
     * }
     * </pre>
     * 还有，用到缓存(HystrixRequestCache)、请求日志(HystrixRequestLog)、批处理（HystrixCollapser）时需要初始化HystrixRequestContext，按以下格式调用
     * <pre>
     * public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
     *      HystrixRequestContext context = HystrixRequestContext.initializeContext();
     *      try {
     *           chain.doFilter(request, response);
     *      } finally {
     *           context.shutdown();
     *      }
     * }
     * </pre>
     * @author zzs
     * @date 2021年11月11日 上午10:20:49 void
     */
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
    
    /**
     * 这里在重写的com.netflix.hystrix.HystrixCommand.run()方法中手动制造fail或time out。
     * time out的情况下执行任务的线程会被InterruptedException中断
     * <pre>
     * @Override
     * protected DataResponse<User> run() {
     *      LOG.info("执行最终任务，线程为：{}", Thread.currentThread());
     *      //try {
     *      //     Thread.sleep(1200);
     *      //} catch(InterruptedException e) {
     *      //     e.printStackTrace();
     *      //}
     *      throw new RuntimeException("");
     *      //return UserService.instance().getUserById(userId);
     * }
     * </pre>
     * 可以通过andCommandPropertiesDefaults来控制断路器的行为
     * <pre>
     *  public CommandGetUserByIdFromUserService(String userId) {
     *      super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserService")) // 相同command group共用一个ThreadPool
     *              .andCommandKey(HystrixCommandKey.Factory.asKey("UserService_GetUserById"))// 相同command key共用一个CircuitBreaker、requestCache
     *              .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
     *                    .withCircuitBreakerRequestVolumeThreshold(10)
     *                    .withCircuitBreakerErrorThresholdPercentage(50)
     *                    .withMetricsHealthSnapshotIntervalInMilliseconds(500)
     *                    .withExecutionTimeoutInMilliseconds(1000)
     *                      ));
     *      this.userId = userId;
     *  }
     * </pre>
     * @author zzs
     * @date 2021年11月11日 上午10:29:20 void
     */
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
    
    /**
     * 可以通过andThreadPoolPropertiesDefaults来控制线程池和队列的大小
     * <pre>
        public CommandGetUserByIdFromUserService(String userId) {
            super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("UserService")) // 相同command group共用一个ThreadPool
                    .andCommandKey(HystrixCommandKey.Factory.asKey("UserService_GetUserById"))// 相同command key共用一个CircuitBreaker、requestCache
                    //.andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("UserService"))
                    .andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter()
                            .withCoreSize(2)
                            .withMaxQueueSize(5)
                            .withQueueSizeRejectionThreshold(5)
                            )
                    );
            this.userId = userId;
        }
        </pre>
     * @author zzs
     * @date 2021年11月11日 上午11:11:31
     * @throws InterruptedException void
     */
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
}

