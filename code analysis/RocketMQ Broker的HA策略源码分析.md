# RocketMQ Broker的HA策略源码分析

Broker的HA策略分为两部分
* 同步元数据
* 同步消息数据

## 同步元数据
在Slave启动时，会启动一个定时任务用来从master同步元数据

在 BrokerController 启动时

```java
// 之后在非DLeger模式下，
// Master会启动事务消息检查，遍历未提交、未回滚的部分消息并向生产者发送检查请求以获取事务状态
// 进行偏移量的检查和计算等操作，并移除掉需要丢弃的消息
// Slave会启动同步操作
if (!messageStoreConfig.isEnableDLegerCommitLog()) {
    startProcessorByHa(messageStoreConfig.getBrokerRole());
    handleSlaveSynchronize(messageStoreConfig.getBrokerRole());
}

// 主从同步方法
private void handleSlaveSynchronize(BrokerRole role) {
    // 如果当前是从节点
    if (role == BrokerRole.SLAVE) {
        if (null != slaveSyncFuture) {
            slaveSyncFuture.cancel(false);
        }
        // 可以注意在之前会通过setMasterAddr将Master的地址设为null
        // 这是由于在后面会通过另一个定时任务registerBrokerAll来向NameServer获取Master的地址
        this.slaveSynchronize.setMasterAddr(null);
        // 这里设置了定时任务，执行slaveSynchronize的syncAll方法
        slaveSyncFuture = this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    BrokerController.this.slaveSynchronize.syncAll();
                }
                catch (Throwable e) {
                    log.error("ScheduledTask SlaveSynchronize syncAll error.", e);
                }
            }
        }, 1000 * 3, 1000 * 10, TimeUnit.MILLISECONDS);
    } else {
        //handle the slave synchronise
        if (null != slaveSyncFuture) {
            slaveSyncFuture.cancel(false);
        }
        this.slaveSynchronize.setMasterAddr(null);
    }
}


// SlaveSynchronize
public void syncAll() {
    // 同步topic的配置信息
    this.syncTopicConfig();
    // 同步Consumer的Offset信息
    this.syncConsumerOffset();
    // 同步延迟队列信息
    this.syncDelayOffset();
    // 同步订阅信息
    this.syncSubscriptionGroupConfig();
}

private void syncTopicConfig() {
    String masterAddrBak = this.masterAddr;
    // 这里首先获取master的地址masterAddr，由于registerBrokerAll定时任务的存在
    // 即便这一次没有获取到masterAddr，只要节点中有master，总会在后面定时执行时从NameServer中获取到
    if (masterAddrBak != null && !masterAddrBak.equals(brokerController.getBrokerAddr())) {
        try {
            // 当获取到master地址后，通过BrokerOuterAPI的getAllTopicConfig方法，向master请求
            TopicConfigSerializeWrapper topicWrapper =
                this.brokerController.getBrokerOuterAPI().getAllTopicConfig(masterAddrBak);
            // 判断版本是否一致，若不一致，会进行替换，这样slave的Topic配置信息就和master保持同步了
            if (!this.brokerController.getTopicConfigManager().getDataVersion()
                .equals(topicWrapper.getDataVersion())) {

                this.brokerController.getTopicConfigManager().getDataVersion()
                    .assignNewOne(topicWrapper.getDataVersion());
                this.brokerController.getTopicConfigManager().getTopicConfigTable().clear();
                this.brokerController.getTopicConfigManager().getTopicConfigTable()
                    .putAll(topicWrapper.getTopicConfigTable());
                this.brokerController.getTopicConfigManager().persist();

                log.info("Update slave topic config from master, {}", masterAddrBak);
            }
        } catch (Exception e) {
            log.error("SyncTopicConfig Exception, {}", masterAddrBak, e);
        }
    }
}


public TopicConfigSerializeWrapper getAllTopicConfig(
    final String addr) throws RemotingConnectException, RemotingSendRequestException,
    RemotingTimeoutException, InterruptedException, MQBrokerException {
    // 创建 GET_ALL_TOPIC_CONFIG 指令的请求命令
    RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.GET_ALL_TOPIC_CONFIG, null);

    // 注意这里会通过MixAll的brokerVIPChannel方法，得到对应的master地址的VIP通道地址，就是端口号减2
    RemotingCommand response = this.remotingClient.invokeSync(MixAll.brokerVIPChannel(true, addr), request, 3000);
    assert response != null;
    switch (response.getCode()) {
        case ResponseCode.SUCCESS: {
            return TopicConfigSerializeWrapper.decode(response.getBody(), TopicConfigSerializeWrapper.class);
        }
        default:
            break;
    }

    throw new MQBrokerException(response.getCode(), response.getRemark());
}


// 下面看看master接收到请求的响应 

case RequestCode.GET_ALL_TOPIC_CONFIG:
    return this.getAllTopicConfig(ctx, request);

private RemotingCommand getAllTopicConfig(ChannelHandlerContext ctx, RemotingCommand request) {
    final RemotingCommand response = RemotingCommand.createResponseCommand(GetAllTopicConfigResponseHeader.class);
    // final GetAllTopicConfigResponseHeader responseHeader =
    // (GetAllTopicConfigResponseHeader) response.readCustomHeader();

    String content = this.brokerController.getTopicConfigManager().encode();
    if (content != null && content.length() > 0) {
        try {
            response.setBody(content.getBytes(MixAll.DEFAULT_CHARSET));
        } catch (UnsupportedEncodingException e) {
            log.error("", e);

            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark("UnsupportedEncodingException " + e);
            return response;
        }
    } else {
        log.error("No topic in this broker, client: {}", ctx.channel().remoteAddress());
        response.setCode(ResponseCode.SYSTEM_ERROR);
        response.setRemark("No topic in this broker");
        return response;
    }

    response.setCode(ResponseCode.SUCCESS);
    response.setRemark(null);
}
    
```

其他的同步和topic同步类似。


## 同步消息数据
在master启动时，会通过JDK的NIO方式启动一个HA服务线程，用以处理slave的连接

```java
// 在DefaultMessageStore启动时，并且没有使用DLeger实现高可用时，会启动haService
if (!messageStoreConfig.isEnableDLegerCommitLog()) {
    this.haService.start();
    this.handleScheduleMessageService(messageStoreConfig.getBrokerRole());
}

public void start() throws Exception {
    this.acceptSocketService.beginAccept();
    this.acceptSocketService.start();
    this.groupTransferService.start();
    this.haClient.start();
}


public void beginAccept() throws Exception {
    this.serverSocketChannel = ServerSocketChannel.open();
    this.selector = RemotingUtil.openSelector();
    this.serverSocketChannel.socket().setReuseAddress(true);
    this.serverSocketChannel.socket().bind(this.socketAddressListen);
    this.serverSocketChannel.configureBlocking(false);
    this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
}


// 其中监听的端口号为 private int haListenPort = 10912;

/**
* {@inherit∆Doc}
*
* 在master启动时，会通过JDK的NIO方式启动一个HA服务线程，用以处理slave的连接
*/
@Override
public void run() {
    log.info(this.getServiceName() + " service started");

    while (!this.isStopped()) {
        try {
            this.selector.select(1000);
            Set<SelectionKey> selected = this.selector.selectedKeys();

            if (selected != null) {
                for (SelectionKey k : selected) {
                    if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                        SocketChannel sc = ((ServerSocketChannel) k.channel()).accept();

                        if (sc != null) {
                            HAService.log.info("HAService receive new connection, "
                                + sc.socket().getRemoteSocketAddress());

                            try {
                                HAConnection conn = new HAConnection(HAService.this, sc);
                                conn.start();
                                HAService.this.addConnection(conn);
                            } catch (Exception e) {
                                log.error("new HAConnection exception", e);
                                sc.close();
                            }
                        }
                    } else {
                        log.warn("Unexpected ops in select " + k.readyOps());
                    }
                }

                selected.clear();
            }
        } catch (Exception e) {
            log.error(this.getServiceName() + " service has exception.", e);
        }
    }

    log.info(this.getServiceName() + " service end");
}


// 在构造方法内进行了对socketChannel的一些配置，还创建了一个WriteSocketService和一个ReadSocketService，这两个是后续处理消息同步的基础
public HAConnection(final HAService haService, final SocketChannel socketChannel) throws IOException {
    this.haService = haService;
    this.socketChannel = socketChannel;
    this.clientAddr = this.socketChannel.socket().getRemoteSocketAddress().toString();
    this.socketChannel.configureBlocking(false);
    this.socketChannel.socket().setSoLinger(false, -1);
    this.socketChannel.socket().setTcpNoDelay(true);
    this.socketChannel.socket().setReceiveBufferSize(1024 * 64);
    this.socketChannel.socket().setSendBufferSize(1024 * 64);
    this.writeSocketService = new WriteSocketService(this.socketChannel);
    this.readSocketService = new ReadSocketService(this.socketChannel);
    this.haService.getConnectionCount().incrementAndGet();
}
```

在构造方法内进行了对socketChannel的一些配置，还创建了一个WriteSocketService和一个ReadSocketService，这两个是后续处理消息同步的基础

创建完HAConnection，会开始readSocketService和writeSocketService

```java
public void start() {
    this.readSocketService.start();
    this.writeSocketService.start();
}
```


先分析HAClient的启动，看看slave启动流程

```java
@Override
public void run() {
log.info(this.getServiceName() + " service started");

// 在这个while循环中，首先通过connectMaster检查是否和master连接了
while (!this.isStopped()) {
    try {
        if (this.connectMaster()) {

            // 当确保与master的连接建立成功后，通过isTimeToReportOffset方法
            // 检查是否需要向master报告当前的最大Offset
            if (this.isTimeToReportOffset()) {
                // 发送 currentReportedOffset
                boolean result = this.reportSlaveMaxOffset(this.currentReportedOffset);
                // 如果还没有写完就结束了
                if (!result) {
                    // 断开连接
                    this.closeMaster();
                }
            }

            // 发送成功后，会调用selector的select方法，在超时时间内进行NIO的轮询，等待master的回送
            // 通过这我们可以看出slave在和master建立连接后，会定时向master报告自己当前的offset
            this.selector.select(1000);

            boolean ok = this.processReadEvent();
            if (!ok) {
                this.closeMaster();
            }

            if (!reportSlaveMaxOffsetPlus()) {
                continue;
            }

            long interval =
                HAService.this.getDefaultMessageStore().getSystemClock().now()
                    - this.lastWriteTimestamp;
            if (interval > HAService.this.getDefaultMessageStore().getMessageStoreConfig()
                .getHaHousekeepingInterval()) {
                log.warn("HAClient, housekeeping, found this connection[" + this.masterAddress
                    + "] expired, " + interval);
                this.closeMaster();
                log.warn("HAClient, master not response some time, so close connection");
            }
        } else {
            this.waitForRunning(1000 * 5);
        }
    } catch (Exception e) {
        log.warn(this.getServiceName() + " service has exception. ", e);
        this.waitForRunning(1000 * 5);
    }
}


private boolean connectMaster() throws ClosedChannelException {
    // 若是socketChannel为null，意味着并没有产生连接，或者连接断开
    if (null == socketChannel) {
        String addr = this.masterAddress.get();
        if (addr != null) {

            SocketAddress socketAddress = RemotingUtil.string2SocketAddress(addr);
            if (socketAddress != null) {
                this.socketChannel = RemotingUtil.connect(socketAddress);
                if (this.socketChannel != null) {
                    this.socketChannel.register(this.selector, SelectionKey.OP_READ);
                }
            }
        }

        // 只要是需要建立连接，都需要通过defaultMessageStore的getMaxPhyOffset方法，获取本地最大的Offset
        // 由currentReportedOffset保存，后续用于向master报告；以及保存了一个时间戳lastWriteTimestamp，用于之后的校对
        this.currentReportedOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();

        this.lastWriteTimestamp = System.currentTimeMillis();
    }

    return this.socketChannel != null;
}

private boolean isTimeToReportOffset() {
    long interval =
        HAService.this.defaultMessageStore.getSystemClock().now() - this.lastWriteTimestamp;
    // 如果距离上一次发送心跳时间超过 getHaSendHeartbeatInterval （默认5s） 则需要发送
    boolean needHeart = interval > HAService.this.defaultMessageStore.getMessageStoreConfig()
        .getHaSendHeartbeatInterval();

    return needHeart;
}

private boolean reportSlaveMaxOffset(final long maxOffset) {
    // 其中reportOffset是专门用来缓存offset的ByteBuffer
    this.reportOffset.position(0);
    this.reportOffset.limit(8);
    this.reportOffset.putLong(maxOffset);
    this.reportOffset.position(0);
    this.reportOffset.limit(8);

    // 将maxOffset存放在reportOffset中，然后通过socketChannel的write方法，完成向master的发送
    // 其中hasRemaining方法用来检查当前位置是否已经达到缓冲区极限limit，确保reportOffset 中的内容能被完全发送出去
    // 发送成功后，会调用selector的select方法，在超时时间内进行NIO的轮询，等待master的回送
    for (int i = 0; i < 3 && this.reportOffset.hasRemaining(); i++) {
        try {
            this.socketChannel.write(this.reportOffset);
        } catch (IOException e) {
            log.error(this.getServiceName()
                + "reportSlaveMaxOffset this.socketChannel.write exception", e);
            return false;
        }
    }

    lastWriteTimestamp = HAService.this.defaultMessageStore.getSystemClock().now();
    // position < limit 表示还没有写完
    return !this.reportOffset.hasRemaining();
}
```

下面看看master收到offset后如何处理

master启动的时候会调用ReadSocketService进行处理
```java
@Override
public void run() {
    HAConnection.log.info(this.getServiceName() + " service started");

    while (!this.isStopped()) {
        try {
            // 这里的while循环中首先也是通过selector的select方法，在超时时间内进行NIO的轮询
            this.selector.select(1000);
            boolean ok = this.processReadEvent();
            if (!ok) {
                HAConnection.log.error("processReadEvent error");
                break;
            }

            long interval = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastReadTimestamp;
            if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaHousekeepingInterval()) {
                log.warn("ha housekeeping, found this connection[" + HAConnection.this.clientAddr + "] expired, " + interval);
                break;
            }
        } catch (Exception e) {
            HAConnection.log.error(this.getServiceName() + " service has exception.", e);
            break;
        }
    }

    this.makeStop();

    writeSocketService.makeStop();

    haService.removeConnection(HAConnection.this);

    HAConnection.this.haService.getConnectionCount().decrementAndGet();

    SelectionKey sk = this.socketChannel.keyFor(this.selector);
    if (sk != null) {
        sk.cancel();
    }

    try {
        this.selector.close();
        this.socketChannel.close();
    } catch (IOException e) {
        HAConnection.log.error("", e);
    }

    HAConnection.log.info(this.getServiceName() + " service end");
}


private boolean processReadEvent() {
    int readSizeZeroTimes = 0;

    if (!this.byteBufferRead.hasRemaining()) {
        this.byteBufferRead.flip();
        this.processPosition = 0;
    }

    while (this.byteBufferRead.hasRemaining()) {
        try {
            // 这个方法其实就是通过socketChannel的read方法，将slave发送过来的数据存入byteBufferRead中
            int readSize = this.socketChannel.read(this.byteBufferRead);
            if (readSize > 0) {
                readSizeZeroTimes = 0;
                this.lastReadTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                // 在确保发送过来的数据能达到8字节时，取出long类型的offset值
                if ((this.byteBufferRead.position() - this.processPosition) >= 8) {
                    int pos = this.byteBufferRead.position() - (this.byteBufferRead.position() % 8);
                    long readOffset = this.byteBufferRead.getLong(pos - 8);
                    this.processPosition = pos;

                    HAConnection.this.slaveAckOffset = readOffset;
                    // slaveRequestOffset是用来处理第一次连接时的同步
                    if (HAConnection.this.slaveRequestOffset < 0) {
                        HAConnection.this.slaveRequestOffset = readOffset;
                        log.info("slave[" + HAConnection.this.clientAddr + "] request offset " + readOffset);
                    }

                    // notifyTransferSome方法是作为同步master时，进行相应的唤醒操作，异步master则没有要求，在后面具体分析
                    HAConnection.this.haService.notifyTransferSome(HAConnection.this.slaveAckOffset);
                }
            } else if (readSize == 0) {
                if (++readSizeZeroTimes >= 3) {
                    break;
                }
            } else {
                log.error("read socket[" + HAConnection.this.clientAddr + "] < 0");
                return false;
            }
        } catch (IOException e) {
            log.error("processReadEvent exception", e);
            return false;
        }
    }

    return true;
}
```

下面来看WriteSocketService线程

```java
@Override
public void run() {
    HAConnection.log.info(this.getServiceName() + " service started");

    while (!this.isStopped()) {
        try {
            this.selector.select(1000);

            // 这里一开始会对slaveRequestOffset进行一次判断，当且仅当slaveRequestOffset初始化的时候是才是-1
            // 也就是说当slave还没有发送过来offset时，WriteSocketService线程只会干等
            if (-1 == HAConnection.this.slaveRequestOffset) {
                Thread.sleep(10);
                continue;
            }

            // 首先对nextTransferFromWhere进行了判断，nextTransferFromWhere和slaveRequestOffset一样，在初始化的时候为-1
            // 也就代表着master和slave刚刚建立连接，并没有进行过一次消息的同步！
            if (-1 == this.nextTransferFromWhere) {
                // 此时会对修改了的slaveRequestOffset进行判断
                // 若是等于0，说明slave没有任何消息的历史记录，那么此时master会取得自身的MaxOffset
                if (0 == HAConnection.this.slaveRequestOffset) {
                    long masterOffset = HAConnection.this.haService.getDefaultMessageStore().getCommitLog().getMaxOffset();
                    // 计算出最后一个文件开始的offset
                    // 也就是说，当slave没有消息的历史记录，master只会从本地最后一个CommitLog文件开始的地方，将消息数据发送给slave
                    masterOffset =
                        masterOffset
                            - (masterOffset % HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                            .getMappedFileSizeCommitLog());

                    if (masterOffset < 0) {
                        masterOffset = 0;
                    }

                    this.nextTransferFromWhere = masterOffset;
                } else {
                    // 若是slave有数据，就从slave发送来的offset的位置起，进行发送，通过nextTransferFromWhere记录这个offset值
                    this.nextTransferFromWhere = HAConnection.this.slaveRequestOffset;
                }

                log.info("master transfer data from " + this.nextTransferFromWhere + " to slave[" + HAConnection.this.clientAddr
                    + "], and slave request " + HAConnection.this.slaveRequestOffset);
            }

            // 接着对lastWriteOver进行了判断，lastWriteOver是一个状态量，用来表示上次发送是否传输完毕，初始化是true
            if (this.lastWriteOver) {

                long interval =
                    HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastWriteTimestamp;

                // 若是true，这里会进行一次时间检查，lastWriteTimestamp记录最后一次发送的时间
                // 一次来判断是否超过了时间间隔haSendHeartbeatInterval（默认5s）
                // 也就是说至少有5s，master没有向slave发送任何消息
                // 那么此时就会发送一个心跳包
                if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                    .getHaSendHeartbeatInterval()) {

                    // Build Header
                    this.byteBufferHeader.position(0);
                    this.byteBufferHeader.limit(headerSize);
                    this.byteBufferHeader.putLong(this.nextTransferFromWhere);
                    this.byteBufferHeader.putInt(0);
                    this.byteBufferHeader.flip();

                    this.lastWriteOver = this.transferData();
                    if (!this.lastWriteOver)
                        continue;
                }
            } else {
                // 若是 lastWriteOver为false，则表示上次数据没有发送完，就需要通过transferData方法
                // 将剩余数据继续发送，只要没发送完，只会重复循环，直到发完
                this.lastWriteOver = this.transferData();
                if (!this.lastWriteOver)
                    continue;
            }

            // 首先根据nextTransferFromWhere，也就是刚才保存的offset，通过DefaultMessageStore的getCommitLogData方法，其实际上调用的是CommitLog的getData方法
            SelectMappedBufferResult selectResult =
                HAConnection.this.haService.getDefaultMessageStore().getCommitLogData(this.nextTransferFromWhere);
            if (selectResult != null) {
                int size = selectResult.getSize();
                // 在得到SelectMappedBufferResult后，这里会对读取到的数据大小进行一次判断
                // 若是大于haTransferBatchSize（默认32K），将size改为32K，实际上就是对发送数据大小的限制
                // 大于32K会切割，每次最多只允许发送32k

                if (size > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize()) {
                    size = HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize();
                }

                // 通过thisOffset记录nextTransferFromWhere即offset
                long thisOffset = this.nextTransferFromWhere;
                // 更新nextTransferFromWhere值，以便下一次定位
                this.nextTransferFromWhere += size;

                selectResult.getByteBuffer().limit(size);
                // 还会将读取到的数据结果selectResult交给selectMappedBufferResult保存
                this.selectMappedBufferResult = selectResult;

                // Build Header
                // 然后构建消息头，这里就和心跳包格式一样，前八字节存放offset，后四字节存放数据大小
                this.byteBufferHeader.position(0);
                this.byteBufferHeader.limit(headerSize);
                this.byteBufferHeader.putLong(thisOffset);
                this.byteBufferHeader.putInt(size);
                this.byteBufferHeader.flip();

                this.lastWriteOver = this.transferData();
            } else {

                // 这里若是master已将将所有本地数据同步给了slave，那么得到的SelectMappedBufferResult就会为null
                // 将自身阻塞，超时等待100ms，要么一直等到超时时间到了，要么就会在后面的同步双传中被同步master唤醒
                HAConnection.this.haService.getWaitNotifyObject().allWaitForRunning(100);
            }
        } catch (Exception e) {

            HAConnection.log.error(this.getServiceName() + " service has exception.", e);
            break;
        }
    }

    HAConnection.this.haService.getWaitNotifyObject().removeFromWaitingThreadTable();

    if (this.selectMappedBufferResult != null) {
        this.selectMappedBufferResult.release();
    }

    this.makeStop();

    readSocketService.makeStop();

    haService.removeConnection(HAConnection.this);

    SelectionKey sk = this.socketChannel.keyFor(this.selector);
    if (sk != null) {
        sk.cancel();
    }

    try {
        this.selector.close();
        this.socketChannel.close();
    } catch (IOException e) {
        HAConnection.log.error("", e);
    }

    HAConnection.log.info(this.getServiceName() + " service end");
}

private boolean transferData() throws Exception {
    int writeSizeZeroTimes = 0;
    // Write Header
    while (this.byteBufferHeader.hasRemaining()) {
        // 首先将byteBufferHeader中的12字节消息头通过socketChannel的write方法发送出去
        int writeSize = this.socketChannel.write(this.byteBufferHeader);
        if (writeSize > 0) {
            writeSizeZeroTimes = 0;
            // 无论发送什么都会将时间记录在lastWriteTimestamp中，以便后续发送心跳包的判断
            this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
        } else if (writeSize == 0) {
            if (++writeSizeZeroTimes >= 3) {
                break;
            }
        } else {
            throw new Exception("ha master write header error < 0");
        }
    }

    // 若是selectMappedBufferResult等于null，说明是心跳包，只发送消息头
    if (null == this.selectMappedBufferResult) {
        return !this.byteBufferHeader.hasRemaining();
    }

    writeSizeZeroTimes = 0;

    // Write Body
    if (!this.byteBufferHeader.hasRemaining()) {
        while (this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
            // 然后将selectMappedBufferResult中的ByteBuffer的消息数据发送出去
            int writeSize = this.socketChannel.write(this.selectMappedBufferResult.getByteBuffer());
            if (writeSize > 0) {
                writeSizeZeroTimes = 0;
                // 无论发送什么都会将时间记录在lastWriteTimestamp中，以便后续发送心跳包的判断
                this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
            } else if (writeSize == 0) {
                if (++writeSizeZeroTimes >= 3) {
                    break;
                }
            } else {
                throw new Exception("ha master write body error < 0");
            }
        }
    }

    boolean result = !this.byteBufferHeader.hasRemaining() && !this.selectMappedBufferResult.getByteBuffer().hasRemaining();

    if (!this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
        this.selectMappedBufferResult.release();
        this.selectMappedBufferResult = null;
    }

    return result;
}
```

看到这里其实就会发现WriteSocketService线程开启后，只要slave向master发出了第一个offset后，WriteSocketService线程都会不断地将对应位置自己本地的CommitLog文件中的内容发送给slave，直到完全同步后，WriteSocketService线程才会稍微缓缓，进入阻塞100ms以及每隔五秒发一次心跳包的状态

但是只要当Producer向master发送来消息后，由刷盘线程完成持久化后，WriteSocketService线程又会忙碌起来，此时也才是体现同步双写和异步复制的时候

下面分析slave收到心跳以及数据的处理逻辑
```java
private boolean processReadEvent() {
    int readSizeZeroTimes = 0;
    while (this.byteBufferRead.hasRemaining()) {
        try {
            // 在socketChannel通过read方法将master发送的数据读取到byteBufferRead缓冲区后
            // 由dispatchReadRequest方法做进一步处理
            int readSize = this.socketChannel.read(this.byteBufferRead);
            if (readSize > 0) {
                readSizeZeroTimes = 0;
                boolean result = this.dispatchReadRequest();
                if (!result) {
                    log.error("HAClient, dispatchReadRequest error");
                    return false;
                }
            } else if (readSize == 0) {
                if (++readSizeZeroTimes >= 3) {
                    break;
                }
            } else {
                log.info("HAClient, processReadEvent read socket < 0");
                return false;
            }
        } catch (IOException e) {
            log.info("HAClient, processReadEvent read socket exception", e);
            return false;
        }
    }

    return true;
}

private boolean dispatchReadRequest() {
    final int msgHeaderSize = 8 + 4; // phyoffset + size
    int readSocketPos = this.byteBufferRead.position();

    while (true) {
        // 说明有新数据进来
        int diff = this.byteBufferRead.position() - this.dispatchPosition;
        if (diff >= msgHeaderSize) {
            // 这里就首先将12字节的消息头取出来
            // masterPhyOffset：8字节offset ，bodySize ：4字节消息大小
            long masterPhyOffset = this.byteBufferRead.getLong(this.dispatchPosition);
            int bodySize = this.byteBufferRead.getInt(this.dispatchPosition + 8);

            long slavePhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();

            // 根据master发来的masterPhyOffset会和自己本地的slavePhyOffset进行校验，以便安全备份
            if (slavePhyOffset != 0) {
                if (slavePhyOffset != masterPhyOffset) {
                    log.error("master pushed offset not equal the max phy offset in slave, SLAVE: "
                        + slavePhyOffset + " MASTER: " + masterPhyOffset);
                    return false;
                }
            }

            // body有数据
            if (diff >= (msgHeaderSize + bodySize)) {
                byte[] bodyData = new byte[bodySize];
                // 调整position 因为之前读了12字节头信息
                this.byteBufferRead.position(this.dispatchPosition + msgHeaderSize);
                // 把数据读到bodyData中
                this.byteBufferRead.get(bodyData);

                // 把master的信息写到commitLog中
                HAService.this.defaultMessageStore.appendToCommitLog(masterPhyOffset, bodyData);

                this.byteBufferRead.position(readSocketPos);
                this.dispatchPosition += msgHeaderSize + bodySize;

                if (!reportSlaveMaxOffsetPlus()) {
                    return false;
                }

                continue;
            }
        }

        if (!this.byteBufferRead.hasRemaining()) {
            this.reallocateByteBuffer();
        }

        break;
    }

    return true;
}


@Override
public boolean appendToCommitLog(long startOffset, byte[] data) {
    if (this.shutdown) {
        log.warn("message store has shutdown, so appendToPhyQueue is forbidden");
        return false;
    }

    // 其实调用了appendData
    boolean result = this.commitLog.appendData(startOffset, data);
    // 在完成写入后，需要唤醒reputMessageService消息调度，以便Consumer的消费
    if (result) {
        this.reputMessageService.wakeup();
    } else {
        log.error("appendToPhyQueue failed " + startOffset + " " + data.length);
    }

    return result;
}


// 由于完成了写入，那么此时获取到的offset肯定比currentReportedOffset中保存的大
// 然后再次通过reportSlaveMaxOffset方法，将当前的offset报告给master
private boolean reportSlaveMaxOffsetPlus() {
    boolean result = true;
    long currentPhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();
    if (currentPhyOffset > this.currentReportedOffset) {
        this.currentReportedOffset = currentPhyOffset;
        result = this.reportSlaveMaxOffset(this.currentReportedOffset);
        if (!result) {
            this.closeMaster();
            log.error("HAClient, reportSlaveMaxOffset error, " + this.currentReportedOffset);
        }
    }

    return result;
}
```

这其实上已经完成了异步master的异步复制过程

再来看看同步双写是如何实现的：
和刷盘一样，都是在Producer发送完消息，Broker进行完消息的存储后进行的

```java
// 同步双写
public void handleHA(AppendMessageResult result, PutMessageResult putMessageResult, MessageExt messageExt) {
    // 如果当前为master结点
    if (BrokerRole.SYNC_MASTER == this.defaultMessageStore.getMessageStoreConfig().getBrokerRole()) {
        HAService service = this.defaultMessageStore.getHaService();
        if (messageExt.isWaitStoreMsgOK()) {
            // Determine whether to wait
            // 根据Offset+WroteBytes创建一条记录GroupCommitRequest，然后会将添加在List中
            if (service.isSlaveOK(result.getWroteOffset() + result.getWroteBytes())) {
                GroupCommitRequest request = new GroupCommitRequest(result.getWroteOffset() + result.getWroteBytes());
                service.putRequest(request);
                // 然后调用getWaitNotifyObject的wakeupAll方法，把阻塞中的所有WriteSocketService线程唤醒
                service.getWaitNotifyObject().wakeupAll();
                // 因为master和slave是一对多的关系，那么这里就会有多个slave连接，也就有多个WriteSocketService线程，保证消息能同步到所有slave中
                // 在唤醒WriteSocketService线程工作后，调用request的waitForFlush方法，将自身阻塞，预示着同步复制的真正开启
                boolean flushOK =
                    request.waitForFlush(this.defaultMessageStore.getMessageStoreConfig().getSyncFlushTimeout());
                if (!flushOK) {
                    // 通过waitForRunning进行阻塞，超时等待，最多五次等待，超过时间会向Producer发送FLUSH_SLAVE_TIMEOUT
                    log.error("do sync transfer other node, wait return, but failed, topic: " + messageExt.getTopic() + " tags: "
                        + messageExt.getTags() + " client address: " + messageExt.getBornHostNameString());
                    putMessageResult.setPutMessageStatus(PutMessageStatus.FLUSH_SLAVE_TIMEOUT);
                }
            }
            // Slave problem
            else {
                // Tell the producer, slave not available
                putMessageResult.setPutMessageStatus(PutMessageStatus.SLAVE_NOT_AVAILABLE);
            }
        }
    }
}


// HAService.GroupTransferService
// 这里的工作原理和同步刷盘GroupCommitService基本一致
public void run() {
    log.info(this.getServiceName() + " service started");

    while (!this.isStopped()) {
        try {
            this.waitForRunning(10);
            this.doWaitTransfer();
        } catch (Exception e) {
            log.warn(this.getServiceName() + " service has exception. ", e);
        }
    }

    log.info(this.getServiceName() + " service end");
}
```
```java
// GroupTransferService同样保存两张List：
private volatile List<CommitLog.GroupCommitRequest> requestsWrite = new ArrayList<>();
private volatile List<CommitLog.GroupCommitRequest> requestsRead = new ArrayList<>();
// 由这两张List做一个类似JVM新生代的复制算法
// 在handleHA方法中，就会将创建的GroupCommitRequest记录添加在requestsWrite这个List中
```

下面看看doWaitTransfer方法
```java
private void doWaitTransfer() {
    synchronized (this.requestsRead) {
        if (!this.requestsRead.isEmpty()) {
            for (CommitLog.GroupCommitRequest req : this.requestsRead) {
                // 首先取出记录中的NextOffset和push2SlaveMaxOffset比较
                // push2SlaveMaxOffset值是通过slave发送过来的
                boolean transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();
                // 其实这里主要要考虑到WriteSocketService线程的工作原理，只要本地文件有更新
                // 那么就会向slave发送数据，所以这里由于HA同步是发生在刷盘后的
                // 那么就有可能在这个doWaitTransfer执行前，有slave已经将数据进行了同步
                // 并且向master报告了自己offset，更新了push2SlaveMaxOffset的值
                for (int i = 0; !transferOK && i < 5; i++) {
                    // 通过waitForRunning进行阻塞，超时等待，最多五次等待，超过时间会向Producer发送FLUSH_SLAVE_TIMEOUT
                    this.notifyTransferObject.waitForRunning(1000);
                    transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();
                }

                if (!transferOK) {
                    log.warn("transfer messsage to slave timeout, " + req.getNextOffset());
                }

                // 这个判断就会为真，意味着节点中已经有了备份，所以就会直接调用，回到CommitLog的waitandflush方法
                req.wakeupCustomer(transferOK);
            }

            this.requestsRead.clear();
        }
    }
}
```

```java
// push2SlaveMaxOffset值是通过slave发送过来的
// 即便也多个slave连接，这里的push2SlaveMaxOffset永远会记录最大的那个offset
public void notifyTransferSome(final long offset) {
    // 即便也多个slave连接，这里的push2SlaveMaxOffset永远会记录最大的那个offset
    for (long value = this.push2SlaveMaxOffset.get(); offset > value; ) {
        boolean ok = this.push2SlaveMaxOffset.compareAndSet(value, offset);
        if (ok) {
            this.groupTransferService.notifyTransferSome();
            break;
        } else {
            value = this.push2SlaveMaxOffset.get();
        }
    }
}
```