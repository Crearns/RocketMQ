# RocketMQ Broker 启动流程分析

## RocketMQ-Broker
代理服务器（Broker Server）消息中转角色，负责存储消息、转发消息。代理服务器在RocketMQ系统中负责接收从生产者发送来的消息并存储、同时为消费者的拉取请求作准备。代理服务器也存储消息相关的元数据，包括消费者组、消费进度偏移和主题和队列消息等。

BrokerServer：Broker主要负责消息的存储、投递和查询以及服务高可用保证，为了实现这些功能，Broker包含了以下几个重要子模块。

* Remoting Module：整个Broker的实体，负责处理来自clients端的请求。
* Client Manager：负责管理客户端(Producer/Consumer)和维护Consumer的Topic订阅信息
* Store Service：提供方便简单的API接口处理消息存储到物理硬盘和查询功能。
* HA Service：高可用服务，提供Master Broker 和 Slave Broker之间的数据同步功能。
* Index Service：根据特定的Message key对投递到Broker的消息进行索引服务，以提供消息的快速查询。

![rocketmq_architecture_2.png](https://i.loli.net/2020/03/20/L7cUsm5YpzXthKq.png)


## 启动分析

首先通过 org.apache.rocketmq.broker.BrokerStartup#createBrokerController 方法创建一个BrokerController

这个阶段主要是通过命令行参数指定配置，主要配合如下
```java
// broker配置
final BrokerConfig brokerConfig = new BrokerConfig();
// netty server配置
final NettyServerConfig nettyServerConfig = new NettyServerConfig();
// netty client配置
final NettyClientConfig nettyClientConfig = new NettyClientConfig();


// TLS
nettyClientConfig.setUseTLS(Boolean.parseBoolean(System.getProperty(TLS_ENABLE,
    String.valueOf(TlsSystemConfig.tlsMode == TlsMode.ENFORCING))));

// netty server 监听10911
nettyServerConfig.setListenPort(10911);
// 消息存储配置
final MessageStoreConfig messageStoreConfig = new MessageStoreConfig();
```

从参数的路径下加载配置，然后转成指定的config对象，接着以这些config对象为参数调用BrokerController的构造方法，创建出BrokerController对象后对对象进行初始化。
```java
// 创建brokerController
final BrokerController controller = new BrokerController(
    brokerConfig,
    nettyServerConfig,
    nettyClientConfig,
    messageStoreConfig);
// remember all configs to prevent discard 注册配置
controller.getConfiguration().registerConfig(properties);

// Broker初始化
boolean initResult = controller.initialize();
// 初始化失败 退出
if (!initResult) {
    controller.shutdown();
    System.exit(-3);
}
```

BrokerController里几个重要的成员变量
```java
// 管理消费者的消费消息的进度 主要结构为 ConcurrentHashMap key为 topic@group
private final ConsumerOffsetManager consumerOffsetManager;
// 管理Consumer缓存，ConcurrentHashMap key为group value为ConsumerGroupInfo
private final ConsumerManager consumerManager;
// 管理Producer缓存 ConcurrentHashMap
private final ProducerManager producerManager;
// 管理Pull消息请求
private final PullMessageProcessor pullMessageProcessor;
// 管理Pull消息请求
private final PullRequestHoldService pullRequestHoldService;
// 管理Pull消息请求
private final MessageArrivingListener messageArrivingListener;
// 管理 topic和topicConfig的消息，结构为 ConcurrentHashMap
private TopicConfigManager topicConfigManager;
```


下面分析一下初始化方法。

```java
public boolean initialize() throws CloneNotSupportedException {
    //加载topic config/topics.json=》
    boolean result = this.topicConfigManager.load();
    //加载客户消费的offset config/consumerOffset.json
    result = result && this.consumerOffsetManager.load();
    //加载订阅组信息 config/subscriptionGroup.json
    result = result && this.subscriptionGroupManager.load();
    //加载客户filter config/consumerFilter.json
    result = result && this.consumerFilterManager.load();

    if (result) {
        try {
            // 创建messageStore，这个对象是存储的核心对象
            this.messageStore =
                new DefaultMessageStore(this.messageStoreConfig, this.brokerStatsManager, this.messageArrivingListener,
                    this.brokerConfig);
            // DLeger 进行高可用
            if (messageStoreConfig.isEnableDLegerCommitLog()) {
                DLedgerRoleChangeHandler roleChangeHandler = new DLedgerRoleChangeHandler(this, (DefaultMessageStore) messageStore);
                ((DLedgerCommitLog)((DefaultMessageStore) messageStore).getCommitLog()).getdLedgerServer().getdLedgerLeaderElector().addRoleChangeHandler(roleChangeHandler);
            }
            this.brokerStats = new BrokerStats((DefaultMessageStore) this.messageStore);
            //load plugin
            MessageStorePluginContext context = new MessageStorePluginContext(messageStoreConfig, brokerStatsManager, messageArrivingListener, brokerConfig);
            this.messageStore = MessageStoreFactory.build(context, this.messageStore);
            this.messageStore.getDispatcherList().addFirst(new CommitLogDispatcherCalcBitMap(this.brokerConfig, this.consumerFilterManager));
        } catch (IOException e) {
            result = false;
            log.error("Failed to initialize", e);
        }
    }

    //从消息存储中加载=》
    result = result && this.messageStore.load();

    if (result) {
        // netty server
        this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.clientHousekeepingService);
        // 克隆一份netty配置 todo: vip
        NettyServerConfig fastConfig = (NettyServerConfig) this.nettyServerConfig.clone();
        // 监听 10909  VIP端口
        fastConfig.setListenPort(nettyServerConfig.getListenPort() - 2);
        // netty server
        this.fastRemotingServer = new NettyRemotingServer(fastConfig, this.clientHousekeepingService);
        /*
            * ******************以下为线程池定义，用于broker不同服务******************
            */
        // 发送消息线程池
        this.sendMessageExecutor = new BrokerFixedThreadPoolExecutor(
            this.brokerConfig.getSendMessageThreadPoolNums(),
            this.brokerConfig.getSendMessageThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.sendThreadPoolQueue,
            new ThreadFactoryImpl("SendMessageThread_"));
        // 拉取消息线程池
        this.pullMessageExecutor = new BrokerFixedThreadPoolExecutor(
            this.brokerConfig.getPullMessageThreadPoolNums(),
            this.brokerConfig.getPullMessageThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.pullThreadPoolQueue,
            new ThreadFactoryImpl("PullMessageThread_"));
        // 查询消息线程池
        this.queryMessageExecutor = new BrokerFixedThreadPoolExecutor(
            this.brokerConfig.getQueryMessageThreadPoolNums(),
            this.brokerConfig.getQueryMessageThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.queryThreadPoolQueue,
            new ThreadFactoryImpl("QueryMessageThread_"));

        // admin线程池 命令行？
        this.adminBrokerExecutor =
            Executors.newFixedThreadPool(this.brokerConfig.getAdminBrokerThreadPoolNums(), new ThreadFactoryImpl(
                "AdminBrokerThread_"));

        // 客户端管理线程池
        this.clientManageExecutor = new ThreadPoolExecutor(
            this.brokerConfig.getClientManageThreadPoolNums(),
            this.brokerConfig.getClientManageThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.clientManagerThreadPoolQueue,
            new ThreadFactoryImpl("ClientManageThread_"));

        // 心跳线程池
        this.heartbeatExecutor = new BrokerFixedThreadPoolExecutor(
            this.brokerConfig.getHeartbeatThreadPoolNums(),
            this.brokerConfig.getHeartbeatThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.heartbeatThreadPoolQueue,
            new ThreadFactoryImpl("HeartbeatThread_", true));

        // 事务相关线程池
        this.endTransactionExecutor = new BrokerFixedThreadPoolExecutor(
            this.brokerConfig.getEndTransactionThreadPoolNums(),
            this.brokerConfig.getEndTransactionThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.endTransactionThreadPoolQueue,
            new ThreadFactoryImpl("EndTransactionThread_"));

        // 消费者管理线程池
        this.consumerManageExecutor =
            Executors.newFixedThreadPool(this.brokerConfig.getConsumerManageThreadPoolNums(), new ThreadFactoryImpl(
                "ConsumerManageThread_"));

        // 注册码以及处理器的注册
        this.registerProcessor();


        // 计算离明天早上0点的时间，意思就是以下操作都在0点开始重复运行
        final long initialDelay = UtilAll.computeNextMorningTimeMillis() - System.currentTimeMillis();
        // 一天时间
        final long period = 1000 * 60 * 60 * 24;
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    // 每天打印Broker的状态
                    BrokerController.this.getBrokerStats().record();
                } catch (Throwable e) {
                    log.error("schedule record error.", e);
                }
            }
        }, initialDelay, period, TimeUnit.MILLISECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    // FlushConsumerOffset 定时向consumerOffset.json文件中写入消费者偏移量
                    // 10秒后开始 默认5秒刷新一次
                    BrokerController.this.consumerOffsetManager.persist();
                } catch (Throwable e) {
                    log.error("schedule persist consumerOffset error.", e);
                }
            }
        }, 1000 * 10, this.brokerConfig.getFlushConsumerOffsetInterval(), TimeUnit.MILLISECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    // 每10s 定时向consumerFilter.json文件写入消费者过滤器信息
                    BrokerController.this.consumerFilterManager.persist();
                } catch (Throwable e) {
                    log.error("schedule persist consumer filter error.", e);
                }
            }
        }, 1000 * 10, 1000 * 10, TimeUnit.MILLISECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    // 定时禁用消费慢的consumer，保护Broker，需要设置disableConsumeIfConsumerReadSlowly属性，默认false
                    BrokerController.this.protectBroker();
                } catch (Throwable e) {
                    log.error("protectBroker error.", e);
                }
            }
        }, 3, 3, TimeUnit.MINUTES);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    // 每1秒定时打印Send、Pull、Query、Transaction队列信息
                    BrokerController.this.printWaterMark();
                } catch (Throwable e) {
                    log.error("printWaterMark error.", e);
                }
            }
        }, 10, 1, TimeUnit.SECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    // 每60s定时打印已存储在提交日志中但尚未调度到消费队列的字节数
                    log.info("dispatch behind commit log {} bytes", BrokerController.this.getMessageStore().dispatchBehindBytes());
                } catch (Throwable e) {
                    log.error("schedule dispatchBehindBytes error.", e);
                }
            }
        }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);

        // 若是设置了NamesrvAddr，需要通过updateNameServerAddressList完成一次NameServer地址的跟新
        if (this.brokerConfig.getNamesrvAddr() != null) {
            this.brokerOuterAPI.updateNameServerAddressList(this.brokerConfig.getNamesrvAddr());
            log.info("Set user specified name server address: {}", this.brokerConfig.getNamesrvAddr());
        } else if (this.brokerConfig.isFetchNamesrvAddrByAddressServer()) {
            // 若是设置了NamesrvAddr，并且设置了fetchNamesrvAddrByAddressServer属性（默认关闭），需要定时获取更新NameServer地址（fetchNameServerAddr方法在之前博客也介绍过）
            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {
                        BrokerController.this.brokerOuterAPI.fetchNameServerAddr();
                    } catch (Throwable e) {
                        log.error("ScheduledTask fetchNameServerAddr exception", e);
                    }
                }
            }, 1000 * 10, 1000 * 60 * 2, TimeUnit.MILLISECONDS);
        }


        // 没有采用DLeger实现高可用 即采用主从复制实现高可用
        if (!messageStoreConfig.isEnableDLegerCommitLog()) {
            // 如果当前是从节点 则需要检查是否设置了HA的Master地址
            if (BrokerRole.SLAVE == this.messageStoreConfig.getBrokerRole()) {
                // 获得主节点地址
                if (this.messageStoreConfig.getHaMasterAddress() != null && this.messageStoreConfig.getHaMasterAddress().length() >= 6) {
                    // 若设置了Master地址要通过updateHaMasterAddress方法向更新Master地址
                    this.messageStore.updateHaMasterAddress(this.messageStoreConfig.getHaMasterAddress());
                    this.updateMasterHAServerAddrPeriodically = false;
                } else {
                    // 若没有设置需要更改updateMasterHAServerAddrPeriodically为true，在后面会有用
                    this.updateMasterHAServerAddrPeriodically = true;
                }
            } else {
                // 若是MASTER，则需要定时打印slave落后的字节数
                this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // todo
                            BrokerController.this.printMasterAndSlaveDiff();
                        } catch (Throwable e) {
                            log.error("schedule printMasterAndSlaveDiff error.", e);
                        }
                    }
                }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);
            }
        }

        if (TlsSystemConfig.tlsMode != TlsMode.DISABLED) {
            // Register a listener to reload SslContext
            try {
                fileWatchService = new FileWatchService(
                    new String[] {
                        TlsSystemConfig.tlsServerCertPath,
                        TlsSystemConfig.tlsServerKeyPath,
                        TlsSystemConfig.tlsServerTrustCertPath
                    },
                    new FileWatchService.Listener() {
                        boolean certChanged, keyChanged = false;

                        @Override
                        public void onChanged(String path) {
                            if (path.equals(TlsSystemConfig.tlsServerTrustCertPath)) {
                                log.info("The trust certificate changed, reload the ssl context");
                                reloadServerSslContext();
                            }
                            if (path.equals(TlsSystemConfig.tlsServerCertPath)) {
                                certChanged = true;
                            }
                            if (path.equals(TlsSystemConfig.tlsServerKeyPath)) {
                                keyChanged = true;
                            }
                            if (certChanged && keyChanged) {
                                log.info("The certificate and private key changed, reload the ssl context");
                                certChanged = keyChanged = false;
                                reloadServerSslContext();
                            }
                        }

                        private void reloadServerSslContext() {
                            ((NettyRemotingServer) remotingServer).loadSslContext();
                            ((NettyRemotingServer) fastRemotingServer).loadSslContext();
                        }
                    });
            } catch (Exception e) {
                log.warn("FileWatchService created error, can't load the certificate dynamically");
            }
        }
        // 初始化事务 todo 事务相关
        initialTransaction();
        // 初始化ACL
        initialAcl();
        // 初始化RPC钩子方法
        initialRpcHooks();
    }
    return result;
}

// MessageStore的构造方法
public DefaultMessageStore(final MessageStoreConfig messageStoreConfig, final BrokerStatsManager brokerStatsManager,
    final MessageArrivingListener messageArrivingListener, final BrokerConfig brokerConfig) throws IOException {
    this.messageArrivingListener = messageArrivingListener;
    this.brokerConfig = brokerConfig;
    this.messageStoreConfig = messageStoreConfig;
    this.brokerStatsManager = brokerStatsManager;
    // 请求定位服务
    this.allocateMappedFileService = new AllocateMappedFileService(this);
    // 存储服务 是否使用DLeger高可用，在DLeger高可用中，将DLeger本身的commitLog与RocketMQ的log结合
    if (messageStoreConfig.isEnableDLegerCommitLog()) {
        this.commitLog = new DLedgerCommitLog(this);
    } else {
        this.commitLog = new CommitLog(this);
    }
    // 消费队列信息
    this.consumeQueueTable = new ConcurrentHashMap<>(32);
    // 刷新队列服务
    this.flushConsumeQueueService = new FlushConsumeQueueService();
    // 清除CommitLog数据服务
    this.cleanCommitLogService = new CleanCommitLogService();
    // 清除消费队列服务
    this.cleanConsumeQueueService = new CleanConsumeQueueService();
    this.storeStatsService = new StoreStatsService();
    // 索引服务
    this.indexService = new IndexService(this);
    // HA服务，主从复制 高可用 todo DLeger
    if (!messageStoreConfig.isEnableDLegerCommitLog()) {
        this.haService = new HAService(this);
    } else {
        this.haService = null;
    }
    this.reputMessageService = new ReputMessageService();

    this.scheduleMessageService = new ScheduleMessageService(this);

    this.transientStorePool = new TransientStorePool(messageStoreConfig);

    if (messageStoreConfig.isTransientStorePoolEnable()) {
        this.transientStorePool.init();
    }

    this.allocateMappedFileService.start();

    this.indexService.start();

    this.dispatcherList = new LinkedList<>();
    this.dispatcherList.addLast(new CommitLogDispatcherBuildConsumeQueue());
    this.dispatcherList.addLast(new CommitLogDispatcherBuildIndex());

    File file = new File(StorePathConfigHelper.getLockFile(messageStoreConfig.getStorePathRootDir()));
    MappedFile.ensureDirOK(file.getParent());
    lockFile = new RandomAccessFile(file, "rw");
}


//MessageStore的load方法
public boolean load() {
    boolean result = true;

    try {
        boolean lastExitOK = !this.isTempFileExist();
        log.info("last shutdown {}", lastExitOK ? "normally" : "abnormally");

        if (null != scheduleMessageService) {
            result = result && this.scheduleMessageService.load();
        }

        // load Commit Log 加载 commit log
        result = result && this.commitLog.load();

        // load Consume Queue 加载消费队列
        result = result && this.loadConsumeQueue();

        // todo 深入分析
        if (result) {
            this.storeCheckpoint =
                new StoreCheckpoint(StorePathConfigHelper.getStoreCheckpoint(this.messageStoreConfig.getStorePathRootDir()));

            // 索引服务
            this.indexService.load(lastExitOK);

            // 恢复数据
            this.recover(lastExitOK);

            log.info("load over, and the max phy offset = {}", this.getMaxPhyOffset());
        }
    } catch (Exception e) {
        log.error("load exception", e);
        result = false;
    }

    if (!result) {
        this.allocateMappedFileService.shutdown();
    }

    return result;
}

private void initialTransaction() {
    //这里动态加载了TransactionalMessageService和AbstractTransactionalMessageCheckListener的实现类，位于如下
    //"META-INF/service/org.apache.rocketmq.broker.transaction.TransactionalMessageService"
    //"META-INF/service/org.apache.rocketmq.broker.transaction.AbstractTransactionalMessageCheckListener"
    //还创建了TransactionalMessageCheckService
    // todo 这些类有啥用 事务相关到后面深入分析
    this.transactionalMessageService = ServiceProvider.loadClass(ServiceProvider.TRANSACTION_SERVICE_ID, TransactionalMessageService.class);
    if (null == this.transactionalMessageService) {
        this.transactionalMessageService = new TransactionalMessageServiceImpl(new TransactionalMessageBridge(this, this.getMessageStore()));
        log.warn("Load default transaction message hook service: {}", TransactionalMessageServiceImpl.class.getSimpleName());
    }
    this.transactionalMessageCheckListener = ServiceProvider.loadClass(ServiceProvider.TRANSACTION_LISTENER_ID, AbstractTransactionalMessageCheckListener.class);
    if (null == this.transactionalMessageCheckListener) {
        this.transactionalMessageCheckListener = new DefaultTransactionalMessageCheckListener();
        log.warn("Load default discard message hook service: {}", DefaultTransactionalMessageCheckListener.class.getSimpleName());
    }
    this.transactionalMessageCheckListener.setBrokerController(this);
    this.transactionalMessageCheckService = new TransactionalMessageCheckService(this);
}
```

Broker会在initialize方法启动8个定时任务：
* 每天0点打印Broker的状态
* 10秒后开始 默认5秒刷新一次
* 每10s 定时向consumerFilter.json文件写入消费者过滤器信息
* 定时禁用消费慢的consumer，保护Broker，需要设置disableConsumeIfConsumerReadSlowly属性，默认false
* 每1秒定时打印Send、Pull、Query、Transaction队列信息
* 每60s定时打印已存储在提交日志中但尚未调度到消费队列的字节数
* 若是设置了NamesrvAddr，并且设置了fetchNamesrvAddrByAddressServer属性（默认关闭），需要定时获取更新NameServer地址
* 没有采用DLeger实现高可用 即采用主从复制实现高可用，并且是MASTER，则需要定时打印slave落后的字节数

**以上是初始化方法，已经分析完成。**

后面的代码涉及RocketMQ的消息存储架构，下面是架构图：
![rocketmq_design_1.png](https://i.loli.net/2020/03/20/j9bgpiQFdq3WmvE.png)

### 消息存储架构图中主要有下面三个跟消息存储相关的文件构成。

(1) CommitLog：消息主体以及元数据的存储主体，存储Producer端写入的消息主体内容,消息内容不是定长的。单个文件大小默认1G ，文件名长度为20位，左边补零，剩余为起始偏移量，比如00000000000000000000代表了第一个文件，起始偏移量为0，文件大小为1G=1073741824；当第一个文件写满了，第二个文件为00000000001073741824，起始偏移量为1073741824，以此类推。消息主要是顺序写入日志文件，当文件满了，写入下一个文件；

(2) ConsumeQueue：消息消费队列，引入的目的主要是提高消息消费的性能，由于RocketMQ是基于主题topic的订阅模式，消息消费是针对主题进行的，如果要遍历commitlog文件中根据topic检索消息是非常低效的。Consumer即可根据ConsumeQueue来查找待消费的消息。其中，ConsumeQueue（逻辑消费队列）作为消费消息的索引，保存了指定Topic下的队列消息在CommitLog中的起始物理偏移量offset，消息大小size和消息Tag的HashCode值。consumequeue文件可以看成是基于topic的commitlog索引文件，故consumequeue文件夹的组织方式如下：topic/queue/file三层组织结构，具体存储路径为：$HOME/store/consumequeue/{topic}/{queueId}/{fileName}。同样consumequeue文件采取定长设计，每一个条目共20个字节，分别为8字节的commitlog物理偏移量、4字节的消息长度、8字节tag hashcode，单个文件由30W个条目组成，可以像数组一样随机访问每一个条目，每个ConsumeQueue文件大小约5.72M；

(3) IndexFile：IndexFile（索引文件）提供了一种可以通过key或时间区间来查询消息的方法。Index文件的存储位置是：$HOME \store\index${fileName}，文件名fileName是以创建时的时间戳命名的，固定的单个IndexFile文件大小约为400M，一个IndexFile可以保存 2000W个索引，IndexFile的底层存储设计为在文件系统中实现HashMap结构，故rocketmq的索引文件其底层实现为hash索引。

在上面的RocketMQ的消息存储整体架构图中可以看出，RocketMQ采用的是混合型的存储结构，即为Broker单个实例下所有的队列共用一个日志数据文件（即为CommitLog）来存储。RocketMQ的混合型存储结构(多个Topic的消息实体内容都存储于一个CommitLog中)针对Producer和Consumer分别采用了数据和索引部分相分离的存储结构，Producer发送消息至Broker端，然后Broker端使用同步或者异步的方式对消息刷盘持久化，保存至CommitLog中。只要消息被刷盘持久化至磁盘文件CommitLog中，那么Producer发送的消息就不会丢失。正因为如此，Consumer也就肯定有机会去消费这条消息。当无法拉取到消息后，可以等下一次消息拉取，同时服务端也支持长轮询模式，如果一个消息拉取请求未拉取到消息，Broker允许等待30s的时间，只要这段时间内有新消息到达，将直接返回给消费端。这里，RocketMQ的具体做法是，使用Broker端的后台服务线程—ReputMessageService不停地分发请求并异步构建ConsumeQueue（逻辑消费队列）和IndexFile（索引文件）数据。

### 页缓存与内存映射
页缓存（PageCache)是OS对文件的缓存，用于加速对文件的读写。一般来说，程序对文件进行顺序读写的速度几乎接近于内存的读写速度，主要原因就是由于OS使用PageCache机制对读写访问操作进行了性能优化，将一部分的内存用作PageCache。对于数据的写入，OS会先写入至Cache内，随后通过异步的方式由pdflush内核线程将Cache内的数据刷盘至物理磁盘上。对于数据的读取，如果一次读取文件时出现未命中PageCache的情况，OS从物理磁盘上访问读取文件的同时，会顺序对其他相邻块的数据文件进行预读取。

在RocketMQ中，ConsumeQueue逻辑消费队列存储的数据较少，并且是顺序读取，在page cache机制的预读取作用下，Consume Queue文件的读性能几乎接近读内存，即使在有消息堆积情况下也不会影响性能。而对于CommitLog消息存储的日志数据文件来说，读取消息内容时候会产生较多的随机访问读取，严重影响性能。如果选择合适的系统IO调度算法，比如设置调度算法为“Deadline”（此时块存储采用SSD的话），随机读的性能也会有所提升。

另外，RocketMQ主要通过MappedByteBuffer对文件进行读写操作。其中，利用了NIO中的FileChannel模型将磁盘上的物理文件直接映射到用户态的内存地址中（这种Mmap的方式减少了传统IO将磁盘文件数据在操作系统内核地址空间的缓冲区和用户应用程序地址空间的缓冲区之间来回进行拷贝的性能开销），将对文件的操作转化为直接对内存地址进行操作，从而极大地提高了文件的读写效率（正因为需要使用内存映射机制，故RocketMQ的文件存储都使用定长结构来存储，方便一次将整个文件映射至内存）。

Broker初始化后，执行start()方法后，Broker真正开启各项服务。
```java
// 启动各项服务
public void start() throws Exception {
    if (this.messageStore != null) {
        this.messageStore.start();
    }

    // Netty 服务端启动
    if (this.remotingServer != null) {
        this.remotingServer.start();
    }

    // Netty 服务端启动
    if (this.fastRemotingServer != null) {
        this.fastRemotingServer.start();
    }

    if (this.fileWatchService != null) {
        this.fileWatchService.start();
    }

    // Netty 客户端启动
    if (this.brokerOuterAPI != null) {
        this.brokerOuterAPI.start();
    }

    // todo 消息拉取
    if (this.pullRequestHoldService != null) {
        this.pullRequestHoldService.start();
    }

    // 定期清除channel缓存
    if (this.clientHousekeepingService != null) {
        this.clientHousekeepingService.start();
    }

    if (this.filterServerManager != null) {
        this.filterServerManager.start();
    }

    // 之后在非DLeger模式下，
    // Master会启动事务消息检查，遍历未提交、未回滚的部分消息并向生产者发送检查请求以获取事务状态
    // 进行偏移量的检查和计算等操作，并移除掉需要丢弃的消息
    // Slave会启动同步操作
    if (!messageStoreConfig.isEnableDLegerCommitLog()) {
        startProcessorByHa(messageStoreConfig.getBrokerRole());
        handleSlaveSynchronize(messageStoreConfig.getBrokerRole());
    }



    this.registerBrokerAll(true, false, true);

    // 定时注册broker
    this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

        @Override
        public void run() {
            try {
                BrokerController.this.registerBrokerAll(true, false, brokerConfig.isForceRegister());
            } catch (Throwable e) {
                log.error("registerBrokerAll Exception", e);
            }
        }
    }, 1000 * 10, Math.max(10000, Math.min(brokerConfig.getRegisterNameServerPeriod(), 60000)), TimeUnit.MILLISECONDS);

    if (this.brokerStatsManager != null) {
        this.brokerStatsManager.start();
    }

    if (this.brokerFastFailure != null) {
        // 快速失败
        this.brokerFastFailure.start();
    }


}
```
```java
//org.apache.rocketmq.store.DefaultMessageStore#start
public void start() throws Exception {

    // 尝试获取文件锁 保证只被一个MessageStore读写
    lock = lockFile.getChannel().tryLock(0, 1, false);
    if (lock == null || lock.isShared() || !lock.isValid()) {
        throw new RuntimeException("Lock failed,MQ already started");
    }

    // 写入lock
    lockFile.getChannel().write(ByteBuffer.wrap("lock".getBytes()));
    // FileChannel.force()方法将通道里尚未写入磁盘的数据强制写到磁盘上。出于性能方面的考虑，操作系统会将数据缓存在内存中，所以无法保证写入到FileChannel里的数据一定会即时写到磁盘上。要保证这一点，需要调用force()方法。force()方法有一个boolean类型的参数，指明是否同时将文件元数据（权限信息等）写到磁盘上。
    lockFile.getChannel().force(true);
    {
        /**
            * todo 没看懂
            * 1.确保根据提交日志的最大物理偏移量在恢复过程中截断快进消息；
            * 2.可能缺少DLedger的commitPos，因此maxPhysicalPosInLogicQueue可能大于DLedgerCommitLog返回的maxOffset；
            * 3.根据消耗队列计算reput偏移；
            * 4.在启动提交日志之前，尤其是在自动更改代理角色时，请确保将分派落后消息。
            *
            * 1. Make sure the fast-forward messages to be truncated during the recovering according to the max physical offset of the commitlog;
            * 2. DLedger committedPos may be missing, so the maxPhysicalPosInLogicQueue maybe bigger that maxOffset returned by DLedgerCommitLog, just let it go;
            * 3. Calculate the reput offset according to the consume queue;
            * 4. Make sure the fall-behind messages to be dispatched before starting the commitlog, especially when the broker role are automatically changed.
            */
        // commitLog的getMinOffset方法获取最小的Offset
        // commitLog会将消息持久化为文件，每个文件默认最大1G，当超过1G，则会新创建一个文件存储，如此反复
        // 而commitLog会把这些文件在物理上不连续的Offset映射成逻辑上连续的Offset，以此来定位
        // 找到最大的offset就是为了找到下一条消息来的时候的写入点
        long maxPhysicalPosInLogicQueue = commitLog.getMinOffset();
        // 遍历队列
        for (ConcurrentMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
            for (ConsumeQueue logic : maps.values()) {
                // 通过遍历获得最大的offset
                if (logic.getMaxPhysicOffset() > maxPhysicalPosInLogicQueue) {
                    maxPhysicalPosInLogicQueue = logic.getMaxPhysicOffset();
                }
            }
        }
        // 遍历完成，maxPhysicalPosInLogicQueue就会被替换为最大的那次的消费Offset，这样后续就可以通过这个Offset映射到具体哪个文件的哪个位置
        if (maxPhysicalPosInLogicQueue < 0) {
            maxPhysicalPosInLogicQueue = 0;
        }
        if (maxPhysicalPosInLogicQueue < this.commitLog.getMinOffset()) {
            maxPhysicalPosInLogicQueue = this.commitLog.getMinOffset();
            /**
                * This happens in following conditions:
                * 1. If someone removes all the consumequeue files or the disk get damaged.
                * 2. Launch a new broker, and copy the commitlog from other brokers.
                *
                * All the conditions has the same in common that the maxPhysicalPosInLogicQueue should be 0.
                * If the maxPhysicalPosInLogicQueue is gt 0, there maybe something wrong.
                */
            log.warn("[TooSmallCqOffset] maxPhysicalPosInLogicQueue={} clMinOffset={}", maxPhysicalPosInLogicQueue, this.commitLog.getMinOffset());
        }
        log.info("[SetReputOffset] maxPhysicalPosInLogicQueue={} clMinOffset={} clMaxOffset={} clConfirmedOffset={}",
            maxPhysicalPosInLogicQueue, this.commitLog.getMinOffset(), this.commitLog.getMaxOffset(), this.commitLog.getConfirmOffset());
        // 设置写入点
        this.reputMessageService.setReputFromOffset(maxPhysicalPosInLogicQueue);
        // 启动ReputMessageService服务
        this.reputMessageService.start();

        /**
            * 一直等待消息分配完毕
            *  1. Finish dispatching the messages fall behind, then to start other services.
            *  2. DLedger committedPos may be missing, so here just require dispatchBehindBytes <= 0
            */
        while (true) {
            if (dispatchBehindBytes() <= 0) {
                break;
            }
            Thread.sleep(1000);
            log.info("Try to finish doing reput the messages fall behind during the starting, reputOffset={} maxOffset={} behind={}", this.reputMessageService.getReputFromOffset(), this.getMaxPhyOffset(), this.dispatchBehindBytes());
        }
        this.recoverTopicQueueTable();
    }

    // 非DLeger情况下
    // todo 高可用服务
    if (!messageStoreConfig.isEnableDLegerCommitLog()) {
        this.haService.start();
        this.handleScheduleMessageService(messageStoreConfig.getBrokerRole());
    }

    // 接着开启flushConsumeQueueService服务
    // 和reputMessageService类似，这里也会启动一个线程，使用doFlush方法定时刷新ConsumeQueue
    this.flushConsumeQueueService.start();
    this.commitLog.start();
    this.storeStatsService.start();

    this.createTempFile();
    this.addScheduleTask();
    this.shutdown = false;
}

public long getMinOffset() {
    // CommitLog管理的这些文件是通过mappedFileQueue管理，mappedFileQueue中会通过mappedFiles映射到每一个文件
    MappedFile mappedFile = this.mappedFileQueue.getFirstMappedFile();
    if (mappedFile != null) {
        if (mappedFile.isAvailable()) {
            // 在得到第一个文件的MappedFile映射后，通过getFileFromOffset方法，获取该文件的Offset
            return mappedFile.getFileFromOffset();
        } else {
            return this.rollNextFile(mappedFile.getFileFromOffset());
        }
    }

    return -1;
}

public MappedFile getFirstMappedFile() {
    MappedFile mappedFileFirst = null;

    if (!this.mappedFiles.isEmpty()) {
        try {
            // 获得第一个文件
            mappedFileFirst = this.mappedFiles.get(0);
        } catch (IndexOutOfBoundsException e) {
            //ignore
        } catch (Exception e) {
            log.error("getFirstMappedFile has exception.", e);
        }
    }

    return mappedFileFirst;
}
```

CommitLog管理的这些文件是通过mappedFileQueue管理，mappedFileQueue中会通过mappedFiles映射到每一个文件：
```java
private final CopyOnWriteArrayList<MappedFile> mappedFiles = new CopyOnWriteArrayList<MappedFile>();
```

MappedFile为内存映射，MappedFile可以通过fileChannel来完成对文件的访问和修改，主要属性如下：
```java
public class MappedFile extends ReferenceResource {
    protected int fileSize;
    protected FileChannel fileChannel;
    protected ByteBuffer writeBuffer = null;
    private String fileName;
    private long fileFromOffset;
    private File file;
    ......
}
```

下面看看reputMessageService的start方法
```java
// 每1ms执行一个doReput
@Override
public void run() {
    DefaultMessageStore.log.info(this.getServiceName() + " service started");

    while (!this.isStopped()) {
        try {
            Thread.sleep(1);
            this.doReput();
        } catch (Exception e) {
            DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
        }
    }

    DefaultMessageStore.log.info(this.getServiceName() + " service end");
}

private void doReput() {
    if (this.reputFromOffset < DefaultMessageStore.this.commitLog.getMinOffset()) {
        log.warn("The reputFromOffset={} is smaller than minPyOffset={}, this usually indicate that the dispatch behind too much and the commitlog has expired.",
            this.reputFromOffset, DefaultMessageStore.this.commitLog.getMinOffset());
        this.reputFromOffset = DefaultMessageStore.this.commitLog.getMinOffset();
    }
    for (boolean doNext = true; this.isCommitLogAvailable() && doNext; ) {

        if (DefaultMessageStore.this.getMessageStoreConfig().isDuplicationEnable()
            && this.reputFromOffset >= DefaultMessageStore.this.getConfirmOffset()) {
            break;
        }


        // 通过commitLog的getData方法获取SelectMappedBufferResult,获得reputFromOffset所对应的数据
        SelectMappedBufferResult result = DefaultMessageStore.this.commitLog.getData(reputFromOffset);
        if (result != null) {
            try {
                this.reputFromOffset = result.getStartOffset();

                for (int readSize = 0; readSize < result.getSize() && doNext; ) {
                    DispatchRequest dispatchRequest =
                        DefaultMessageStore.this.commitLog.checkMessageAndReturnSize(result.getByteBuffer(), false, false);
                    int size = dispatchRequest.getBufferSize() == -1 ? dispatchRequest.getMsgSize() : dispatchRequest.getBufferSize();

                    if (dispatchRequest.isSuccess()) {
                        if (size > 0) {
                            DefaultMessageStore.this.doDispatch(dispatchRequest);

                            // 如果当前是Master并且设置了长轮询的话，则需要通过messageArrivingListener通知消费队列有新的消息 todo
                            if (BrokerRole.SLAVE != DefaultMessageStore.this.getMessageStoreConfig().getBrokerRole()
                                && DefaultMessageStore.this.brokerConfig.isLongPollingEnable()) {
                                DefaultMessageStore.this.messageArrivingListener.arriving(dispatchRequest.getTopic(),
                                    dispatchRequest.getQueueId(), dispatchRequest.getConsumeQueueOffset() + 1,
                                    dispatchRequest.getTagsCode(), dispatchRequest.getStoreTimestamp(),
                                    dispatchRequest.getBitMap(), dispatchRequest.getPropertiesMap());
                            }

                            this.reputFromOffset += size;
                            readSize += size;
                            if (DefaultMessageStore.this.getMessageStoreConfig().getBrokerRole() == BrokerRole.SLAVE) {
                                DefaultMessageStore.this.storeStatsService
                                    .getSinglePutMessageTopicTimesTotal(dispatchRequest.getTopic()).incrementAndGet();
                                DefaultMessageStore.this.storeStatsService
                                    .getSinglePutMessageTopicSizeTotal(dispatchRequest.getTopic())
                                    .addAndGet(dispatchRequest.getMsgSize());
                            }
                        } else if (size == 0) {
                            this.reputFromOffset = DefaultMessageStore.this.commitLog.rollNextFile(this.reputFromOffset);
                            readSize = result.getSize();
                        }
                    } else if (!dispatchRequest.isSuccess()) {

                        if (size > 0) {
                            log.error("[BUG]read total count not equals msg total size. reputFromOffset={}", reputFromOffset);
                            this.reputFromOffset += size;
                        } else {
                            doNext = false;
                            // If user open the dledger pattern or the broker is master node,
                            // it will not ignore the exception and fix the reputFromOffset variable
                            if (DefaultMessageStore.this.getMessageStoreConfig().isEnableDLegerCommitLog() ||
                                DefaultMessageStore.this.brokerConfig.getBrokerId() == MixAll.MASTER_ID) {
                                log.error("[BUG]dispatch message to consume queue error, COMMITLOG OFFSET: {}",
                                    this.reputFromOffset);
                                this.reputFromOffset += result.getSize() - readSize;
                            }
                        }
                    }
                }
            } finally {
                result.release();
            }
        } else {
            doNext = false;
        }
    }
}

private boolean isCommitLogAvailable() {
    // 如果reputFromoffset小于commit的最大offset
    // isCommitLogAvailable方法，就是判断reputFromOffset是否达到了最后一个文件能访问的地方
    return this.reputFromOffset < DefaultMessageStore.this.commitLog.getMaxOffset();
}

/**
* Read CommitLog data, use data replication
*/
public SelectMappedBufferResult getData(final long offset) {
    return this.getData(offset, offset == 0);
}

public SelectMappedBufferResult getData(final long offset, final boolean returnFirstOnNotFound) {
    // 这里的mappedFileSize就是文件的大小，默认1G
    // 根据reputFromOffset通过mappedFileQueue的findMappedFileByOffset方法定位具体的MappedFile文件映射
    int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog();
    MappedFile mappedFile = this.mappedFileQueue.findMappedFileByOffset(offset, returnFirstOnNotFound);
    if (mappedFile != null) {
        // 因为文件是1G 所以offset对一个文件和对多个文件取模是一样的
        int pos = (int) (offset % mappedFileSize);
        SelectMappedBufferResult result = mappedFile.selectMappedBuffer(pos);
        return result;
    }

    return null;
}

public MappedFile findMappedFileByOffset(final long offset, final boolean returnFirstOnNotFound) {
    try {
        MappedFile firstMappedFile = this.getFirstMappedFile();
        MappedFile lastMappedFile = this.getLastMappedFile();
        if (firstMappedFile != null && lastMappedFile != null) {
            // 首先检查offset的有效性 offset越界
            if (offset < firstMappedFile.getFileFromOffset() || offset >= lastMappedFile.getFileFromOffset() + this.mappedFileSize) {
                LOG_ERROR.warn("Offset not matched. Request offset: {}, firstOffset: {}, lastOffset: {}, mappedFileSize: {}, mappedFiles count: {}",
                    offset,
                    firstMappedFile.getFileFromOffset(),
                    lastMappedFile.getFileFromOffset() + this.mappedFileSize,
                    this.mappedFileSize,
                    this.mappedFiles.size());
            } else {
                // 得到offset对应的文件在mappedFiles这个list中的下标
                int index = (int) ((offset / this.mappedFileSize) - (firstMappedFile.getFileFromOffset() / this.mappedFileSize));
                MappedFile targetFile = null;
                try {
                    targetFile = this.mappedFiles.get(index);
                } catch (Exception ignored) {
                }

                if (targetFile != null && offset >= targetFile.getFileFromOffset()
                    && offset < targetFile.getFileFromOffset() + this.mappedFileSize) {
                    return targetFile;
                }

                for (MappedFile tmpMappedFile : this.mappedFiles) {
                    if (offset >= tmpMappedFile.getFileFromOffset()
                        && offset < tmpMappedFile.getFileFromOffset() + this.mappedFileSize) {
                        return tmpMappedFile;
                    }
                }
            }

            if (returnFirstOnNotFound) {
                return firstMappedFile;
            }
        }
    } catch (Exception e) {
        log.error("findMappedFileByOffset Exception", e);
    }

    return null;
}

public SelectMappedBufferResult selectMappedBuffer(int pos) {
    int readPosition = getReadPosition();
    if (pos < readPosition && pos >= 0) {
        if (this.hold()) {
            ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
            byteBuffer.position(pos);
            int size = readPosition - pos;
            ByteBuffer byteBufferNew = byteBuffer.slice();
            byteBufferNew.limit(size);
            return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
        }
    }

    return null;
}

public DispatchRequest checkMessageAndReturnSize(java.nio.ByteBuffer byteBuffer, final boolean checkCRC,
    final boolean readBody) {
    try {
        // 1 TOTAL SIZE
        int totalSize = byteBuffer.getInt();

        // 2 MAGIC CODE
        int magicCode = byteBuffer.getInt();
        switch (magicCode) {
            case MESSAGE_MAGIC_CODE:
                break;
            case BLANK_MAGIC_CODE:
                return new DispatchRequest(0, true /* success */);
            default:
                log.warn("found a illegal magic code 0x" + Integer.toHexString(magicCode));
                return new DispatchRequest(-1, false /* success */);
        }

        byte[] bytesContent = new byte[totalSize];

        int bodyCRC = byteBuffer.getInt();

        int queueId = byteBuffer.getInt();

        int flag = byteBuffer.getInt();

        long queueOffset = byteBuffer.getLong();

        long physicOffset = byteBuffer.getLong();

        int sysFlag = byteBuffer.getInt();

        long bornTimeStamp = byteBuffer.getLong();

        ByteBuffer byteBuffer1 = byteBuffer.get(bytesContent, 0, 8);

        long storeTimestamp = byteBuffer.getLong();

        ByteBuffer byteBuffer2 = byteBuffer.get(bytesContent, 0, 8);

        int reconsumeTimes = byteBuffer.getInt();

        long preparedTransactionOffset = byteBuffer.getLong();

        int bodyLen = byteBuffer.getInt();
        if (bodyLen > 0) {
            if (readBody) {
                byteBuffer.get(bytesContent, 0, bodyLen);

                if (checkCRC) {
                    int crc = UtilAll.crc32(bytesContent, 0, bodyLen);
                    if (crc != bodyCRC) {
                        log.warn("CRC check failed. bodyCRC={}, currentCRC={}", crc, bodyCRC);
                        return new DispatchRequest(-1, false/* success */);
                    }
                }
            } else {
                byteBuffer.position(byteBuffer.position() + bodyLen);
            }
        }

        byte topicLen = byteBuffer.get();
        byteBuffer.get(bytesContent, 0, topicLen);
        String topic = new String(bytesContent, 0, topicLen, MessageDecoder.CHARSET_UTF8);

        long tagsCode = 0;
        String keys = "";
        String uniqKey = null;

        short propertiesLength = byteBuffer.getShort();
        Map<String, String> propertiesMap = null;
        if (propertiesLength > 0) {
            byteBuffer.get(bytesContent, 0, propertiesLength);
            String properties = new String(bytesContent, 0, propertiesLength, MessageDecoder.CHARSET_UTF8);
            propertiesMap = MessageDecoder.string2messageProperties(properties);

            keys = propertiesMap.get(MessageConst.PROPERTY_KEYS);

            uniqKey = propertiesMap.get(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);

            String tags = propertiesMap.get(MessageConst.PROPERTY_TAGS);
            if (tags != null && tags.length() > 0) {
                tagsCode = MessageExtBrokerInner.tagsString2tagsCode(MessageExt.parseTopicFilterType(sysFlag), tags);
            }

            // Timing message processing
            {
                String t = propertiesMap.get(MessageConst.PROPERTY_DELAY_TIME_LEVEL);
                if (ScheduleMessageService.SCHEDULE_TOPIC.equals(topic) && t != null) {
                    int delayLevel = Integer.parseInt(t);

                    if (delayLevel > this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel()) {
                        delayLevel = this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel();
                    }

                    if (delayLevel > 0) {
                        tagsCode = this.defaultMessageStore.getScheduleMessageService().computeDeliverTimestamp(delayLevel,
                            storeTimestamp);
                    }
                }
            }
        }

        int readLength = calMsgLength(bodyLen, topicLen, propertiesLength);
        // 如果长度不对，返回一个空
        if (totalSize != readLength) {
            doNothingForDeadCode(reconsumeTimes);
            doNothingForDeadCode(flag);
            doNothingForDeadCode(bornTimeStamp);
            doNothingForDeadCode(byteBuffer1);
            doNothingForDeadCode(byteBuffer2);
            log.error(
                "[BUG]read total count not equals msg total size. totalSize={}, readTotalCount={}, bodyLen={}, topicLen={}, propertiesLength={}",
                totalSize, readLength, bodyLen, topicLen, propertiesLength);
            return new DispatchRequest(totalSize, false/* success */);
        }

        return new DispatchRequest(
            topic,
            queueId,
            physicOffset,
            totalSize,
            tagsCode,
            storeTimestamp,
            queueOffset,
            keys,
            uniqKey,
            sysFlag,
            preparedTransactionOffset,
            propertiesMap
        );
    } catch (Exception e) {
    }

    return new DispatchRequest(-1, false /* success */);
}

```

下表为dispatchRequest的组成结构

![rocketmq_3.png](https://i.loli.net/2020/03/20/SpdChT9lyVnFzbQ.png)


接着就调用 DefaultMessageStore.this.doDispatch(dispatchRequest);

```java
public void doDispatch(DispatchRequest req) {
    for (CommitLogDispatcher dispatcher : this.dispatcherList) {
        dispatcher.dispatch(req);
    }
}

// 在构造DefaultStoreService时，执行了以下代码，即doDispatch执行的是这两个对象的dispatch方法
this.dispatcherList = new LinkedList<>();
this.dispatcherList.addLast(new CommitLogDispatcherBuildConsumeQueue());
this.dispatcherList.addLast(new CommitLogDispatcherBuildIndex());
```


```java
class CommitLogDispatcherBuildConsumeQueue implements CommitLogDispatcher {

    @Override
    public void dispatch(DispatchRequest request) {
        final int tranType = MessageSysFlag.getTransactionValue(request.getSysFlag());
        switch (tranType) {
            case MessageSysFlag.TRANSACTION_NOT_TYPE:
            case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                DefaultMessageStore.this.putMessagePositionInfo(request);
                break;
            case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
            case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                break;
        }
    }
}

// 当消息满足TRANSACTION_NOT_TYPE和TRANSACTION_COMMIT_TYPE时，调用putMessagePositionInfo方法

public void putMessagePositionInfo(DispatchRequest dispatchRequest) {
    ConsumeQueue cq = this.findConsumeQueue(dispatchRequest.getTopic(), dispatchRequest.getQueueId());
    cq.putMessagePositionInfoWrapper(dispatchRequest);
}

public ConsumeQueue findConsumeQueue(String topic, int queueId) {
    ConcurrentMap<Integer, ConsumeQueue> map = consumeQueueTable.get(topic);
    // 如果不存在，新建
    if (null == map) {
        ConcurrentMap<Integer, ConsumeQueue> newMap = new ConcurrentHashMap<Integer, ConsumeQueue>(128);
        // 双重校验
        ConcurrentMap<Integer, ConsumeQueue> oldMap = consumeQueueTable.putIfAbsent(topic, newMap);
        if (oldMap != null) {
            map = oldMap;
        } else {
            map = newMap;
        }
    }

    ConsumeQueue logic = map.get(queueId);
    if (null == logic) {
        // 不存在则新建 同样有双重校验
        ConsumeQueue newLogic = new ConsumeQueue(
            topic,
            queueId,
            StorePathConfigHelper.getStorePathConsumeQueue(this.messageStoreConfig.getStorePathRootDir()),
            this.getMessageStoreConfig().getMappedFileSizeConsumeQueue(),
            this);
        ConsumeQueue oldLogic = map.putIfAbsent(queueId, newLogic);
        if (oldLogic != null) {
            logic = oldLogic;
        } else {
            logic = newLogic;
        }
    }

    return logic;
}

// 这个方法主要是在重试次数内完成对putMessagePositionInfo的调用
public void putMessagePositionInfoWrapper(DispatchRequest request) {
    final int maxRetries = 30;
    boolean canWrite = this.defaultMessageStore.getRunningFlags().isCQWriteable();
    for (int i = 0; i < maxRetries && canWrite; i++) {
        long tagsCode = request.getTagsCode();
        if (isExtWriteEnable()) {
            ConsumeQueueExt.CqExtUnit cqExtUnit = new ConsumeQueueExt.CqExtUnit();
            cqExtUnit.setFilterBitMap(request.getBitMap());
            cqExtUnit.setMsgStoreTime(request.getStoreTimestamp());
            cqExtUnit.setTagsCode(request.getTagsCode());

            long extAddr = this.consumeQueueExt.put(cqExtUnit);
            if (isExtAddr(extAddr)) {
                tagsCode = extAddr;
            } else {
                log.warn("Save consume queue extend fail, So just save tagsCode! {}, topic:{}, queueId:{}, offset:{}", cqExtUnit,
                    topic, queueId, request.getCommitLogOffset());
            }
        }
        boolean result = this.putMessagePositionInfo(request.getCommitLogOffset(),
            request.getMsgSize(), tagsCode, request.getConsumeQueueOffset());
        if (result) {
            this.defaultMessageStore.getStoreCheckpoint().setLogicsMsgTimestamp(request.getStoreTimestamp());
            return;
        } else {
            // XXX: warn and notify me
            log.warn("[BUG]put commit log position info to " + topic + ":" + queueId + " " + request.getCommitLogOffset()
                + " failed, retry " + i + " times");

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.warn("", e);
            }
        }
    }

    // XXX: warn and notify me
    log.error("[BUG]consume queue can not write, {} {}", this.topic, this.queueId);
    this.defaultMessageStore.getRunningFlags().makeLogicsQueueError();
}

/*
*   将DispatchRequest中封装的CommitLogOffset、MsgSize以及tagsCode这20字节的信息byteBufferIndex这个ByteBuffer中

    根据ConsumeQueueOffset即cqOffset*CQ_STORE_UNIT_SIZE（20）计算expectLogicOffset

    ConsumeQueue文件是通过20字节来存放对应CommitLog文件中的消息映射其原理和CommitLog的相同

    expectLogicOffset就是ConsumeQueue文件逻辑Offset，由此可以通过getLastMappedFile找到对应的文件映射MappedFile

    在得到MappedFile后通过appendMessage方法，将byteBufferIndex中的数据追加在对应的ConsumeQueue文件中
*/
private boolean putMessagePositionInfo(final long offset, final int size, final long tagsCode,
    final long cqOffset) {

    if (offset + size <= this.maxPhysicOffset) {
        log.warn("Maybe try to build consume queue repeatedly maxPhysicOffset={} phyOffset={}", maxPhysicOffset, offset);
        return true;
    }

    this.byteBufferIndex.flip();
    this.byteBufferIndex.limit(CQ_STORE_UNIT_SIZE);
    this.byteBufferIndex.putLong(offset);
    this.byteBufferIndex.putInt(size);
    this.byteBufferIndex.putLong(tagsCode);

    final long expectLogicOffset = cqOffset * CQ_STORE_UNIT_SIZE;

    MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile(expectLogicOffset);
    if (mappedFile != null) {

        if (mappedFile.isFirstCreateInQueue() && cqOffset != 0 && mappedFile.getWrotePosition() == 0) {
            this.minLogicOffset = expectLogicOffset;
            this.mappedFileQueue.setFlushedWhere(expectLogicOffset);
            this.mappedFileQueue.setCommittedWhere(expectLogicOffset);
            this.fillPreBlank(mappedFile, expectLogicOffset);
            log.info("fill pre blank space " + mappedFile.getFileName() + " " + expectLogicOffset + " "
                + mappedFile.getWrotePosition());
        }

        if (cqOffset != 0) {
            long currentLogicOffset = mappedFile.getWrotePosition() + mappedFile.getFileFromOffset();

            if (expectLogicOffset < currentLogicOffset) {
                log.warn("Build  consume queue repeatedly, expectLogicOffset: {} currentLogicOffset: {} Topic: {} QID: {} Diff: {}",
                    expectLogicOffset, currentLogicOffset, this.topic, this.queueId, expectLogicOffset - currentLogicOffset);
                return true;
            }

            if (expectLogicOffset != currentLogicOffset) {
                LOG_ERROR.warn(
                    "[BUG]logic queue order maybe wrong, expectLogicOffset: {} currentLogicOffset: {} Topic: {} QID: {} Diff: {}",
                    expectLogicOffset,
                    currentLogicOffset,
                    this.topic,
                    this.queueId,
                    expectLogicOffset - currentLogicOffset
                );
            }
        }
        this.maxPhysicOffset = offset + size;
        return mappedFile.appendMessage(this.byteBufferIndex.array());
    }
    return false;
}

// 这里就通过JDK的NIO提供的API完成20字节数据从currentPos起始位置的追加
public boolean appendMessage(final byte[] data) {
    int currentPos = this.wrotePosition.get();

    if ((currentPos + data.length) <= this.fileSize) {
        try {
            this.fileChannel.position(currentPos);
            this.fileChannel.write(ByteBuffer.wrap(data));
        } catch (Throwable e) {
            log.error("Error occurred when append message to mappedFile.", e);
        }
        this.wrotePosition.addAndGet(data.length);
        return true;
    }

    return false;
}

```
下面看CommitLogDispatcherBuildIndex的dispatch方法，这个方法应该是和索引相关

```java
class CommitLogDispatcherBuildIndex implements CommitLogDispatcher {

    //根据messageIndexEnable属性的设置，调用indexService的buildIndex方法，实际上就是向Index文件的追加
    @Override
    public void dispatch(DispatchRequest request) {
        if (DefaultMessageStore.this.messageStoreConfig.isMessageIndexEnable()) {
            DefaultMessageStore.this.indexService.buildIndex(request);
        }
    }
}

public void buildIndex(DispatchRequest req) {
    IndexFile indexFile = retryGetAndCreateIndexFile();
    if (indexFile != null) {
        long endPhyOffset = indexFile.getEndPhyOffset();
        DispatchRequest msg = req;
        String topic = msg.getTopic();
        String keys = msg.getKeys();
        if (msg.getCommitLogOffset() < endPhyOffset) {
            return;
        }

        final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());
        switch (tranType) {
            case MessageSysFlag.TRANSACTION_NOT_TYPE:
            case MessageSysFlag.TRANSACTION_PREPARED_TYPE:
            case MessageSysFlag.TRANSACTION_COMMIT_TYPE:
                break;
            case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE:
                return;
        }

        if (req.getUniqKey() != null) {
            indexFile = putKey(indexFile, msg, buildKey(topic, req.getUniqKey()));
            if (indexFile == null) {
                log.error("putKey error commitlog {} uniqkey {}", req.getCommitLogOffset(), req.getUniqKey());
                return;
            }
        }

        if (keys != null && keys.length() > 0) {
            String[] keyset = keys.split(MessageConst.KEY_SEPARATOR);
            for (int i = 0; i < keyset.length; i++) {
                String key = keyset[i];
                if (key.length() > 0) {
                    indexFile = putKey(indexFile, msg, buildKey(topic, key));
                    if (indexFile == null) {
                        log.error("putKey error commitlog {} uniqkey {}", req.getCommitLogOffset(), req.getUniqKey());
                        return;
                    }
                }
            }
        }
    } else {
        log.error("build index error, stop building index");
    }
}

// 下面是indexFile一些属性
public class IndexFile {
    private static int hashSlotSize = 4;
    private static int indexSize = 20;
    private static int invalidIndex = 0;
    private final int hashSlotNum;
    private final int indexNum;
    private final MappedFile mappedFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final IndexHeader indexHeader;
    xxx
}
```

完成了调用 DefaultMessageStore.this.doDispatch(dispatchRequest); 后，回到doReput方法

如果当前是Master并且设置了长轮询的话，则需要通过messageArrivingListener通知消费队列有新的消息，这段代码后序会分析 

调用完deReput后，回到start方法，启动完reputMessageService后，会执行一个循环。由于代码太远了，再复制一遍

```java
/**
    * 一直等待消息分配完毕
    *  1. Finish dispatching the messages fall behind, then to start other services.
    *  2. DLedger committedPos may be missing, so here just require dispatchBehindBytes <= 0
    */
while (true) {
    if (dispatchBehindBytes() <= 0) {
        break;
    }
    Thread.sleep(1000);
    log.info("Try to finish doing reput the messages fall behind during the starting, reputOffset={} maxOffset={} behind={}", this.reputMessageService.getReputFromOffset(), this.getMaxPhyOffset(), this.dispatchBehindBytes());
}
this.recoverTopicQueueTable();
```

```java
@Override
public long dispatchBehindBytes() {
    return this.reputMessageService.behind();
}


public long behind() {
//            用来检查是否分配完毕
    return DefaultMessageStore.this.commitLog.getMaxOffset() - this.reputFromOffset;
}


public void recoverTopicQueueTable() {
    HashMap<String/* topic-queueid */, Long/* offset */> table = new HashMap<String, Long>(1024);
    // 由于前面的消息分配，这里将ConsumeQueue的Topic和QueueId，以及MaxOffset保存在table中，同时调用correctMinOffset方法根据物理队列最小offset计算修正逻辑队列最小offset
    long minPhyOffset = this.commitLog.getMinOffset();
    for (ConcurrentMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
        for (ConsumeQueue logic : maps.values()) {
            String key = logic.getTopic() + "-" + logic.getQueueId();
            table.put(key, logic.getMaxOffsetInQueue());
            logic.correctMinOffset(minPhyOffset);
        }
    }

//        当所有的ConsumeQueue遍历完成后，更新commitLog的topicQueueTable
    this.commitLog.setTopicQueueTable(table);
}
```

在完成这些过后，会开启HA服务（非DLeger情况下），关于HA后续博客再详细介绍
接着开启flushConsumeQueueService服务
和reputMessageService类似，这里也会启动一个线程，使用doFlush方法定时刷新ConsumeQueue

```java
public void run() {
    DefaultMessageStore.log.info(this.getServiceName() + " service started");

    while (!this.isStopped()) {
        try {
            int interval = DefaultMessageStore.this.getMessageStoreConfig().getFlushIntervalConsumeQueue();
            this.waitForRunning(interval);
            this.doFlush(1);
        } catch (Exception e) {
            DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
        }
    }

    this.doFlush(RETRY_TIMES_OVER);

    DefaultMessageStore.log.info(this.getServiceName() + " service end");
}

// 这里通过遍历consumeQueueTable中所有的ConsumeQueue，执行其flush方法
private void doFlush(int retryTimes) {
    int flushConsumeQueueLeastPages = DefaultMessageStore.this.getMessageStoreConfig().getFlushConsumeQueueLeastPages();

    if (retryTimes == RETRY_TIMES_OVER) {
        flushConsumeQueueLeastPages = 0;
    }

    long logicsMsgTimestamp = 0;

    int flushConsumeQueueThoroughInterval = DefaultMessageStore.this.getMessageStoreConfig().getFlushConsumeQueueThoroughInterval();
    long currentTimeMillis = System.currentTimeMillis();
    if (currentTimeMillis >= (this.lastFlushTimestamp + flushConsumeQueueThoroughInterval)) {
        this.lastFlushTimestamp = currentTimeMillis;
        flushConsumeQueueLeastPages = 0;
        logicsMsgTimestamp = DefaultMessageStore.this.getStoreCheckpoint().getLogicsMsgTimestamp();
    }

    ConcurrentMap<String, ConcurrentMap<Integer, ConsumeQueue>> tables = DefaultMessageStore.this.consumeQueueTable;

    for (ConcurrentMap<Integer, ConsumeQueue> maps : tables.values()) {
        for (ConsumeQueue cq : maps.values()) {
            boolean result = false;
            for (int i = 0; i < retryTimes && !result; i++) {
                result = cq.flush(flushConsumeQueueLeastPages);
            }
        }
    }

    if (0 == flushConsumeQueueLeastPages) {
        if (logicsMsgTimestamp > 0) {
            DefaultMessageStore.this.getStoreCheckpoint().setLogicsMsgTimestamp(logicsMsgTimestamp);
        }
        DefaultMessageStore.this.getStoreCheckpoint().flush();
    }
}

public boolean flush(final int flushLeastPages) {
    boolean result = this.mappedFileQueue.flush(flushLeastPages);
    if (isExtReadEnable()) {
        result = result & this.consumeQueueExt.flush(flushLeastPages);
    }

    return result;
}

public boolean flush(final int flushLeastPages) {
    boolean result = true;
    // 根据flushedWhere，通过findMappedFileByOffset获取要刷新的文件映射MappedFile
    MappedFile mappedFile = this.findMappedFileByOffset(this.flushedWhere, this.flushedWhere == 0);
    if (mappedFile != null) {
        long tmpTimeStamp = mappedFile.getStoreTimestamp();
        int offset = mappedFile.flush(flushLeastPages);
        long where = mappedFile.getFileFromOffset() + offset;
        result = where == this.flushedWhere;
        this.flushedWhere = where;
        if (0 == flushLeastPages) {
            this.storeTimestamp = tmpTimeStamp;
        }
    }

    return result;
}

/**
* @return The current flushed position
*
* 这里就通过NIO的force，将更新的数据强制写入MappedFile对应的ConsumeQueue文件
*
* 完成写入后，更新flushedWhere值，方便下一次刷新的定位
*/
public int flush(final int flushLeastPages) {
    if (this.isAbleToFlush(flushLeastPages)) {
        if (this.hold()) {
            int value = getReadPosition();

            try {
                //We only append data to fileChannel or mappedByteBuffer, never both.
                if (writeBuffer != null || this.fileChannel.position() != 0) {
                    // 刷盘其实就一行代码
                    this.fileChannel.force(false);
                } else {
                    this.mappedByteBuffer.force();
                }
            } catch (Throwable e) {
                log.error("Error occurred when force data to disk.", e);
            }

            this.flushedPosition.set(value);
            this.release();
        } else {
            log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
            this.flushedPosition.set(getReadPosition());
        }
    }
    return this.getFlushedPosition();
}

```

在启动完ConsumeQueue的刷新服务后，启动commitLog

首先会启动CommitLog的刷盘服务，分为同步刷盘和异步刷盘两种模式

在采用内存池缓存消息的时候需要启动commitLogService，在使用内存池的时候，这个服务会定时将内存池中的数据刷新到FileChannel中

```java
public void start() {
    // todo 后面分析
    this.flushCommitLogService.start();

    if (defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
        this.commitLogService.start();
    }
}

接着还会启动storeStatsService服务，监控Store

```java
public void run() {
    log.info(this.getServiceName() + " service started");

    while (!this.isStopped()) {
        try {
            this.waitForRunning(FREQUENCY_OF_SAMPLING);

            this.sampling();

            this.printTps();
        } catch (Exception e) {
            log.warn(this.getServiceName() + " service has exception. ", e);
        }
    }

    log.info(this.getServiceName() + " service end");
}

```

最后开启几个定时任务
* ①定期清除文件，会定期删除掉长时间（默认72小时）未被引用的CommitLog文件
* ②定期检查CommitLog和ConsumeQueue文件有否损坏、丢失，做日志打印
* ③定期虚拟机堆栈使用日志记录


做完这些之后，DefaultStoreService启动完成，回到BrokerController的启动

后面需要 Netty 服务端启动、Netty fast服务端启动、Netty 客户端启动、消息拉取服务（后面分析）、定期清除channel缓存、filterServerManager

之后在非DLeger模式下，Master会启动事务消息检查，遍历未提交、未回滚的部分消息并向生产者发送检查请求以获取事务状态进行偏移量的检查和计算等操作，并移除掉需要丢弃的消息、Slave会启动同步操作

接着会调用registerBrokerAll，向Master的注册

下面分析下Broker注册

```java
public synchronized void registerBrokerAll(final boolean checkOrderConfig, boolean oneway, boolean forceRegister) {
    TopicConfigSerializeWrapper topicConfigWrapper = this.getTopicConfigManager().buildTopicConfigSerializeWrapper();

    if (!PermName.isWriteable(this.getBrokerConfig().getBrokerPermission())
        || !PermName.isReadable(this.getBrokerConfig().getBrokerPermission())) {
        ConcurrentHashMap<String, TopicConfig> topicConfigTable = new ConcurrentHashMap<String, TopicConfig>();
        for (TopicConfig topicConfig : topicConfigWrapper.getTopicConfigTable().values()) {
            TopicConfig tmp =
                new TopicConfig(topicConfig.getTopicName(), topicConfig.getReadQueueNums(), topicConfig.getWriteQueueNums(),
                    this.brokerConfig.getBrokerPermission());
            topicConfigTable.put(topicConfig.getTopicName(), tmp);
        }
        topicConfigWrapper.setTopicConfigTable(topicConfigTable);
    }

    if (forceRegister || needRegister(this.brokerConfig.getBrokerClusterName(),
        this.getBrokerAddr(),
        this.brokerConfig.getBrokerName(),
        this.brokerConfig.getBrokerId(),
        this.brokerConfig.getRegisterBrokerTimeoutMills())) {
        doRegisterBrokerAll(checkOrderConfig, oneway, topicConfigWrapper);
    }
}


private boolean needRegister(final String clusterName,
    final String brokerAddr,
    final String brokerName,
    final long brokerId,
    final int timeoutMills) {

    TopicConfigSerializeWrapper topicConfigWrapper = this.getTopicConfigManager().buildTopicConfigSerializeWrapper();
    List<Boolean> changeList = brokerOuterAPI.needRegister(clusterName, brokerAddr, brokerName, brokerId, topicConfigWrapper, timeoutMills);
    boolean needRegister = false;
    for (Boolean changed : changeList) {
        if (changed) {
            needRegister = true;
            break;
        }
    }
    return needRegister;
}

public List<Boolean> needRegister(
    final String clusterName,
    final String brokerAddr,
    final String brokerName,
    final long brokerId,
    final TopicConfigSerializeWrapper topicConfigWrapper,
    final int timeoutMills) {
    final List<Boolean> changedList = new CopyOnWriteArrayList<>();
//        首先获取NameServer的地址列表
    List<String> nameServerAddressList = this.remotingClient.getNameServerAddressList();
    if (nameServerAddressList != null && nameServerAddressList.size() > 0) {
        final CountDownLatch countDownLatch = new CountDownLatch(nameServerAddressList.size());
        // 遍历所有NameServer地址
        for (final String namesrvAddr : nameServerAddressList) {
            brokerOuterExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        QueryDataVersionRequestHeader requestHeader = new QueryDataVersionRequestHeader();
                        requestHeader.setBrokerAddr(brokerAddr);
                        requestHeader.setBrokerId(brokerId);
                        requestHeader.setBrokerName(brokerName);
                        requestHeader.setClusterName(clusterName);
                        RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.QUERY_DATA_VERSION, requestHeader);
                        request.setBody(topicConfigWrapper.getDataVersion().encode());
                        // 以同步方式向NameServe发送QUERY_DATA_VERSION请求，将DataVersion信息发送过去，在NameServe端进行比对，进行相应响应
                        RemotingCommand response = remotingClient.invokeSync(namesrvAddr, request, timeoutMills);
                        DataVersion nameServerDataVersion = null;
                        Boolean changed = false;
                        switch (response.getCode()) {
                            case ResponseCode.SUCCESS: {
                                QueryDataVersionResponseHeader queryDataVersionResponseHeader =
                                    (QueryDataVersionResponseHeader) response.decodeCommandCustomHeader(QueryDataVersionResponseHeader.class);
                                changed = queryDataVersionResponseHeader.getChanged();
                                byte[] body = response.getBody();
                                if (body != null) {
                                    // 在收到成功的响应后，检查回送的nameServerDataVersion是否相等，若不相等，在changedList中添加一个true
                                    // 直至和所有NameServe比对完成
                                    nameServerDataVersion = DataVersion.decode(body, DataVersion.class);
                                    if (!topicConfigWrapper.getDataVersion().equals(nameServerDataVersion)) {
                                        changed = true;
                                    }
                                }
                                if (changed == null || changed) {
                                    changedList.add(Boolean.TRUE);
                                }
                            }
                            default:
                                break;
                        }
                        log.warn("Query data version from name server {} OK,changed {}, broker {},name server {}", namesrvAddr, changed, topicConfigWrapper.getDataVersion(), nameServerDataVersion == null ? "" : nameServerDataVersion);
                    } catch (Exception e) {
                        changedList.add(Boolean.TRUE);
                        log.error("Query data version from name server {}  Exception, {}", namesrvAddr, e);
                    } finally {
                        countDownLatch.countDown();
                    }
                }
            });

        }
        try {
            countDownLatch.await(timeoutMills, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("query dataversion from nameserver countDownLatch await Exception", e);
        }
    }
    return changedList;
}

private void doRegisterBrokerAll(boolean checkOrderConfig, boolean oneway,
    TopicConfigSerializeWrapper topicConfigWrapper) {
    List<RegisterBrokerResult> registerBrokerResultList = this.brokerOuterAPI.registerBrokerAll(
        this.brokerConfig.getBrokerClusterName(),
        this.getBrokerAddr(),
        this.brokerConfig.getBrokerName(),
        this.brokerConfig.getBrokerId(),
        this.getHAServerAddr(),
        topicConfigWrapper,
        this.filterServerManager.buildNewFilterServerList(),
        oneway,
        this.brokerConfig.getRegisterBrokerTimeoutMills(),
        this.brokerConfig.isCompressedRegister());

    if (registerBrokerResultList.size() > 0) {
        RegisterBrokerResult registerBrokerResult = registerBrokerResultList.get(0);
        if (registerBrokerResult != null) {
            if (this.updateMasterHAServerAddrPeriodically && registerBrokerResult.getHaServerAddr() != null) {
                this.messageStore.updateHaMasterAddress(registerBrokerResult.getHaServerAddr());
            }

            // 设置master地址
            this.slaveSynchronize.setMasterAddr(registerBrokerResult.getMasterAddr());

            if (checkOrderConfig) {
                this.getTopicConfigManager().updateOrderTopicConfig(registerBrokerResult.getKvTable());
            }
        }
    }
}
```

回到 BrokerController

```java
// 定时注册broker
this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

    @Override
    public void run() {
        try {
            BrokerController.this.registerBrokerAll(true, false, brokerConfig.isForceRegister());
        } catch (Throwable e) {
            log.error("registerBrokerAll Exception", e);
        }
    }
}, 1000 * 10, Math.max(10000, Math.min(brokerConfig.getRegisterNameServerPeriod(), 60000)), TimeUnit.MILLISECONDS);

```

最后开启Broker的快速失败
```java
if (this.brokerFastFailure != null) {
    // 快速失败
    this.brokerFastFailure.start();
}

public void start() {
        // 清理过期请求
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (brokerController.getBrokerConfig().isBrokerFastFailureEnable()) {
                    cleanExpiredRequest();
                }
            }
        }, 1000, 10, TimeUnit.MILLISECONDS);
    }

private void cleanExpiredRequest() {
    // 如果操作系统忙
    while (this.brokerController.getMessageStore().isOSPageCacheBusy()) {
        try {
            // 从线程池中阻塞队列
            if (!this.brokerController.getSendThreadPoolQueue().isEmpty()) {
                final Runnable runnable = this.brokerController.getSendThreadPoolQueue().poll(0, TimeUnit.SECONDS);
                if (null == runnable) {
                    break;
                }

                final RequestTask rt = castRunnable(runnable);
                // 直接返回忙 快速失败
                rt.returnResponse(RemotingSysResponseCode.SYSTEM_BUSY, String.format("[PCBUSY_CLEAN_QUEUE]broker busy, start flow control for a while, period in queue: %sms, size of queue: %d", System.currentTimeMillis() - rt.getCreateTimestamp(), this.brokerController.getSendThreadPoolQueue().size()));
            } else {
                break;
            }
        } catch (Throwable ignored) {
        }
    }
    //*********** 清理过期请求 ************

    cleanExpiredRequestInQueue(this.brokerController.getSendThreadPoolQueue(),
        this.brokerController.getBrokerConfig().getWaitTimeMillsInSendQueue());

    cleanExpiredRequestInQueue(this.brokerController.getPullThreadPoolQueue(),
        this.brokerController.getBrokerConfig().getWaitTimeMillsInPullQueue());

    cleanExpiredRequestInQueue(this.brokerController.getHeartbeatThreadPoolQueue(),
        this.brokerController.getBrokerConfig().getWaitTimeMillsInHeartbeatQueue());

    cleanExpiredRequestInQueue(this.brokerController.getEndTransactionThreadPoolQueue(), this
        .brokerController.getBrokerConfig().getWaitTimeMillsInTransactionQueue());
}
```


至此 RocketMQ Broker启动流程已经分析完成