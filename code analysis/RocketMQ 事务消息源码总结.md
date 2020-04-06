# RocketMQ 事务消息源码总结

首先看看官方文档介绍：

Apache RocketMQ在4.3.0版中已经支持分布式事务消息，这里RocketMQ采用了2PC的思想来实现了提交事务消息，同时增加一个补偿逻辑来处理二阶段超时或者失败的消息，如下图所示。

![rocketmq_design_10.png](https://i.loli.net/2020/04/06/lgd89q4ILAbtucP.png)

## RocketMQ事务消息流程概要
上图说明了事务消息的大致方案，其中分为两个流程：正常事务消息的发送及提交、事务消息的补偿流程。

1.事务消息发送及提交：

(1) 发送消息（half消息）。

(2) 服务端响应消息写入结果。

(3) 根据发送结果执行本地事务（如果写入失败，此时half消息对业务不可见，本地逻辑不执行）。

(4) 根据本地事务状态执行Commit或者Rollback（Commit操作生成消息索引，消息对消费者可见）

2.补偿流程：

(1) 对没有Commit/Rollback的事务消息（pending状态的消息），从服务端发起一次“回查”

(2) Producer收到回查消息，检查回查消息对应的本地事务的状态

(3) 根据本地事务状态，重新Commit或者Rollback

其中，补偿阶段用于解决消息Commit或者Rollback发生超时或者失败的情况。

## RocketMQ事务消息设计
1.事务消息在一阶段对用户不可见

在RocketMQ事务消息的主要流程中，一阶段的消息如何对用户不可见。其中，事务消息相对普通消息最大的特点就是一阶段发送的消息对用户是不可见的。那么，如何做到写入消息但是对用户不可见呢？RocketMQ事务消息的做法是：如果消息是half消息，将备份原消息的主题与消息消费队列，然后改变主题为RMQ_SYS_TRANS_HALF_TOPIC。由于消费组未订阅该主题，故消费端无法消费half类型的消息，然后RocketMQ会开启一个定时任务，从Topic为RMQ_SYS_TRANS_HALF_TOPIC中拉取消息进行消费，根据生产者组获取一个服务提供者发送回查事务状态请求，根据事务状态来决定是提交或回滚消息。

在RocketMQ中，消息在服务端的存储结构如下，每条消息都会有对应的索引信息，Consumer通过ConsumeQueue这个二级索引来读取消息实体内容，其流程如下：

![rocketmq_design_11.png](https://i.loli.net/2020/04/06/oCQhIR5A2kEFl8y.png)

RocketMQ的具体实现策略是：写入的如果事务消息，对消息的Topic和Queue等属性进行替换，同时将原来的Topic和Queue信息存储到消息的属性中，正因为消息主题被替换，故消息并不会转发到该原主题的消息消费队列，消费者无法感知消息的存在，不会消费。其实改变消息主题是RocketMQ的常用“套路”，回想一下延时消息的实现机制。

2.Commit和Rollback操作以及Op消息的引入

在完成一阶段写入一条对用户不可见的消息后，二阶段如果是Commit操作，则需要让消息对用户可见；如果是Rollback则需要撤销一阶段的消息。先说Rollback的情况。对于Rollback，本身一阶段的消息对用户是不可见的，其实不需要真正撤销消息（实际上RocketMQ也无法去真正的删除一条消息，因为是顺序写文件的）。但是区别于这条消息没有确定状态（Pending状态，事务悬而未决），需要一个操作来标识这条消息的最终状态。RocketMQ事务消息方案中引入了Op消息的概念，用Op消息标识事务消息已经确定的状态（Commit或者Rollback）。如果一条事务消息没有对应的Op消息，说明这个事务的状态还无法确定（可能是二阶段失败了）。引入Op消息后，事务消息无论是Commit或者Rollback都会记录一个Op操作。Commit相对于Rollback只是在写入Op消息前创建Half消息的索引。

3.Op消息的存储和对应关系

RocketMQ将Op消息写入到全局一个特定的Topic中通过源码中的方法—TransactionalMessageUtil.buildOpTopic()；这个Topic是一个内部的Topic（像Half消息的Topic一样），不会被用户消费。Op消息的内容为对应的Half消息的存储的Offset，这样通过Op消息能索引到Half消息进行后续的回查操作。

![rocketmq_design_12.png](https://i.loli.net/2020/04/06/Z5Jp8kFeO9dvDoV.png)

4.Half消息的索引构建

在执行二阶段Commit操作时，需要构建出Half消息的索引。一阶段的Half消息由于是写到一个特殊的Topic，所以二阶段构建索引时需要读取出Half消息，并将Topic和Queue替换成真正的目标的Topic和Queue，之后通过一次普通消息的写入操作来生成一条对用户可见的消息。所以RocketMQ事务消息二阶段其实是利用了一阶段存储的消息的内容，在二阶段时恢复出一条完整的普通消息，然后走一遍消息写入流程。

5.如何处理二阶段失败的消息？

如果在RocketMQ事务消息的二阶段过程中失败了，例如在做Commit操作时，出现网络问题导致Commit失败，那么需要通过一定的策略使这条消息最终被Commit。RocketMQ采用了一种补偿机制，称为“回查”。Broker端对未确定状态的消息发起回查，将消息发送到对应的Producer端（同一个Group的Producer），由Producer根据消息来检查本地事务的状态，进而执行Commit或者Rollback。Broker端通过对比Half消息和Op消息进行事务消息的回查并且推进CheckPoint（记录那些事务消息的状态是确定的）。

值得注意的是，rocketmq并不会无休止的的信息事务状态回查，默认回查15次，如果15次回查还是无法得知事务状态，rocketmq默认回滚该消息。


下面看看具体源码如何实现的吧：

```java
/**
    * 发送事务消息
    * @param msg msg 消息
    * @param localTransactionExecuter 【本地事务】执行器
    * @param arg 【本地事务】执行器参数
    * @return 事务发送结果
    * @throws MQClientException 当 Client 发生异常时
    */
public TransactionSendResult sendMessageInTransaction(final Message msg,
                                                        final LocalTransactionExecuter localTransactionExecuter, final Object arg)
    throws MQClientException {
    TransactionListener transactionListener = getCheckListener();
    if (null == localTransactionExecuter && null == transactionListener) {
        throw new MQClientException("tranExecutor is null", null);
    }
    Validators.checkMessage(msg, this.defaultMQProducer);

    // 发送【Half消息】
    SendResult sendResult = null;

    // 给消息设置Half属性, 用于后续broker接收到消息判断是否是事务消息的prepare
    MessageAccessor.putProperty(msg, MessageConst.PROPERTY_TRANSACTION_PREPARED, "true");
    MessageAccessor.putProperty(msg, MessageConst.PROPERTY_PRODUCER_GROUP, this.defaultMQProducer.getProducerGroup());
    try {
        // 发送消息
        sendResult = this.send(msg);
    } catch (Exception e) {
        throw new MQClientException("send message Exception", e);
    }

    // 处理发送【Half消息】结果
    LocalTransactionState localTransactionState = LocalTransactionState.UNKNOW;
    Throwable localException = null;
    switch (sendResult.getSendStatus()) {
        // 发送【Half消息】成功，执行【本地事务】逻辑
        case SEND_OK: {
            try {
                if (sendResult.getTransactionId() != null) { // 事务编号。目前开源版本暂时没用到，猜想ONS在使用。
                    msg.putUserProperty("__transactionId__", sendResult.getTransactionId());
                }
                String transactionId = msg.getProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);
                if (null != transactionId && !"".equals(transactionId)) {
                    msg.setTransactionId(transactionId);
                }

                //执行【本地事务】逻辑 兼容版本
                if (null != localTransactionExecuter) {
                    localTransactionState = localTransactionExecuter.executeLocalTransactionBranch(msg, arg);
                } else if (transactionListener != null) {
                    log.debug("Used new transaction API");
                    localTransactionState = transactionListener.executeLocalTransaction(msg, arg);
                }
                if (null == localTransactionState) {
                    localTransactionState = LocalTransactionState.UNKNOW;
                }

                if (localTransactionState != LocalTransactionState.COMMIT_MESSAGE) {
                    log.info("executeLocalTransactionBranch return {}", localTransactionState);
                    log.info(msg.toString());
                }
            } catch (Throwable e) {
                log.info("executeLocalTransactionBranch exception", e);
                log.info(msg.toString());
                localException = e;
            }
        }
        break;
        // 发送【Half消息】失败，标记【本地事务】状态为回滚
        case FLUSH_DISK_TIMEOUT:
        case FLUSH_SLAVE_TIMEOUT:
        case SLAVE_NOT_AVAILABLE:
            localTransactionState = LocalTransactionState.ROLLBACK_MESSAGE;
            break;
        default:
            break;
    }

    // 结束事务：提交消息 COMMIT / ROLLBACK
    try {
        // 根据localTransactionState不同，发送EndTransactionRequestHeader进行事务的第二阶段
        // commit/rollback之前发送的prepare消息
        this.endTransaction(sendResult, localTransactionState, localException);
    } catch (Exception e) {
        log.warn("local transaction execute " + localTransactionState + ", but end broker transaction failed", e);
    }

    // 返回【事务发送结果】
    TransactionSendResult transactionSendResult = new TransactionSendResult();
    transactionSendResult.setSendStatus(sendResult.getSendStatus());
    transactionSendResult.setMessageQueue(sendResult.getMessageQueue());
    transactionSendResult.setMsgId(sendResult.getMsgId());
    transactionSendResult.setQueueOffset(sendResult.getQueueOffset());
    transactionSendResult.setTransactionId(sendResult.getTransactionId());
    transactionSendResult.setLocalTransactionState(localTransactionState);
    return transactionSendResult;
}
```

发送消息前面已经分析过了，现在可以看看结束事务的逻辑

```java
/**
    * 结束事务：提交消息 COMMIT / ROLLBACK
    *
    * @param sendResult 发送【Half消息】结果
    * @param localTransactionState 【本地事务】状态
    * @param localException 执行【本地事务】逻辑产生的异常
    * @throws RemotingException 当远程调用发生异常时
    * @throws MQBrokerException 当 Broker 发生异常时
    * @throws InterruptedException 当线程中断时
    * @throws UnknownHostException 当解码消息编号失败是
    */
public void endTransaction(
    final SendResult sendResult,
    final LocalTransactionState localTransactionState,
    final Throwable localException) throws RemotingException, MQBrokerException, InterruptedException, UnknownHostException {
    // 解码消息编号
    final MessageId id;
    // 从发送的结果中获取消息在CommitLog中的Offset
    // 该offset后续会回传给Borker，让Borker知道去处理哪一个half消息
    if (sendResult.getOffsetMsgId() != null) {
        id = MessageDecoder.decodeMessageId(sendResult.getOffsetMsgId());
    } else {
        id = MessageDecoder.decodeMessageId(sendResult.getMsgId());
    }

    // 创建请求
    String transactionId = sendResult.getTransactionId();
    final String brokerAddr = this.mQClientFactory.findBrokerAddressInPublish(sendResult.getMessageQueue().getBrokerName());
    EndTransactionRequestHeader requestHeader = new EndTransactionRequestHeader();
    requestHeader.setTransactionId(transactionId);
    // Broker会根据该Offset在Commit/abort阶段取出half消息进行处理
    requestHeader.setCommitLogOffset(id.getOffset());
    switch (localTransactionState) {
        case COMMIT_MESSAGE:
            requestHeader.setCommitOrRollback(MessageSysFlag.TRANSACTION_COMMIT_TYPE);
            break;
        case ROLLBACK_MESSAGE:
            requestHeader.setCommitOrRollback(MessageSysFlag.TRANSACTION_ROLLBACK_TYPE);
            break;
        case UNKNOW:
            requestHeader.setCommitOrRollback(MessageSysFlag.TRANSACTION_NOT_TYPE);
            break;
        default:
            break;
    }

    requestHeader.setProducerGroup(this.defaultMQProducer.getProducerGroup());
    requestHeader.setTranStateTableOffset(sendResult.getQueueOffset());
    requestHeader.setMsgId(sendResult.getMsgId());
    String remark = localException != null ? ("executeLocalTransactionBranch exception: " + localException.toString()) : null;

    // 提交消息 COMMIT / ROLLBACK。！！！通信方式为：Oneway！！！
    this.mQClientFactory.getMQClientAPIImpl().endTransactionOneway(brokerAddr, requestHeader, remark,
        this.defaultMQProducer.getSendMsgTimeout());
}
```

另外需要注意，在发送消息的时候，会把标识符设置成事务
```java
// 事务相关
final String tranMsg = msg.getProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED);
if (tranMsg != null && Boolean.parseBoolean(tranMsg)) {
    sysFlag |= MessageSysFlag.TRANSACTION_PREPARED_TYPE;
}
```

下面看看Broker的响应逻辑

```java
// SendMessageProcessor.java

// 之后会对消息的PROPERTY_TRANSACTION_PREPARED属性进行检查，判断是否是事务消息
//
//若是事务消息，会检查是否设置了拒绝事务消息的配置rejectTransactionMessage
//若是拒绝则返回相应响应response，由Netty发送给Producer
//否则调用TransactionalMessageService的prepareMessage方法
String traFlag = oriProps.get(MessageConst.PROPERTY_TRANSACTION_PREPARED);
if (traFlag != null && Boolean.parseBoolean(traFlag)) {
    if (this.brokerController.getBrokerConfig().isRejectTransactionMessage()) {
        response.setCode(ResponseCode.NO_PERMISSION);
        response.setRemark(
            "the broker[" + this.brokerController.getBrokerConfig().getBrokerIP1()
                + "] sending transaction message is forbidden");
        return response;
    }
    // 事务会走这里
    putMessageResult = this.brokerController.getTransactionalMessageService().prepareMessage(msgInner);
} else {
    putMessageResult = this.brokerController.getMessageStore().putMessage(msgInner);
}


public PutMessageResult prepareMessage(MessageExtBrokerInner messageInner) {
    return transactionalMessageBridge.putHalfMessage(messageInner);
}

public PutMessageResult putHalfMessage(MessageExtBrokerInner messageInner) {
    return store.putMessage(parseHalfMessageInner(messageInner));
}

// 转换成half消息
private MessageExtBrokerInner parseHalfMessageInner(MessageExtBrokerInner msgInner) {
    MessageAccessor.putProperty(msgInner, MessageConst.PROPERTY_REAL_TOPIC, msgInner.getTopic());
    MessageAccessor.putProperty(msgInner, MessageConst.PROPERTY_REAL_QUEUE_ID,
        String.valueOf(msgInner.getQueueId()));
    // 重置事务标识，后续的IndexService和
    msgInner.setSysFlag(
        MessageSysFlag.resetTransactionValue(msgInner.getSysFlag(), MessageSysFlag.TRANSACTION_NOT_TYPE));
    // 设置内部消息的Topic为MixAll.RMQ_SYS_TRANS_HALF_TOPIC对应的值
    msgInner.setTopic(TransactionalMessageUtil.buildHalfTopic());
    msgInner.setQueueId(0);
    msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgInner.getProperties()));
    return msgInner;
}

```

以上代码执行后就和普通的putMessage是一样的，接着会把put的结果发回给Producer，下面看看Producer发送结束事务的请求给Broker时，Broker的逻辑~。

```java
// EndTransactionProcessor
@Override
public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws
    RemotingCommandException {
    final RemotingCommand response = RemotingCommand.createResponseCommand(null);
    final EndTransactionRequestHeader requestHeader =
        (EndTransactionRequestHeader)request.decodeCommandCustomHeader(EndTransactionRequestHeader.class);
    LOGGER.info("Transaction request:{}", requestHeader);
    // 如果是slave 返回 SLAVE_NOT_AVAILABLE
    if (BrokerRole.SLAVE == brokerController.getMessageStoreConfig().getBrokerRole()) {
        response.setCode(ResponseCode.SLAVE_NOT_AVAILABLE);
        LOGGER.warn("Message store is slave mode, so end transaction is forbidden. ");
        return response;
    }

    if (requestHeader.getFromTransactionCheck()) {
        switch (requestHeader.getCommitOrRollback()) {
            case MessageSysFlag.TRANSACTION_NOT_TYPE: {
                LOGGER.warn("Check producer[{}] transaction state, but it's pending status."
                        + "RequestHeader: {} Remark: {}",
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                    requestHeader.toString(),
                    request.getRemark());
                return null;
            }

            case MessageSysFlag.TRANSACTION_COMMIT_TYPE: {
                LOGGER.warn("Check producer[{}] transaction state, the producer commit the message."
                        + "RequestHeader: {} Remark: {}",
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                    requestHeader.toString(),
                    request.getRemark());

                break;
            }

            case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE: {
                LOGGER.warn("Check producer[{}] transaction state, the producer rollback the message."
                        + "RequestHeader: {} Remark: {}",
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                    requestHeader.toString(),
                    request.getRemark());
                break;
            }
            default:
                return null;
        }
    } else {
        switch (requestHeader.getCommitOrRollback()) {
            case MessageSysFlag.TRANSACTION_NOT_TYPE: {
                LOGGER.warn("The producer[{}] end transaction in sending message,  and it's pending status."
                        + "RequestHeader: {} Remark: {}",
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                    requestHeader.toString(),
                    request.getRemark());
                return null;
            }

            case MessageSysFlag.TRANSACTION_COMMIT_TYPE: {
                break;
            }

            case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE: {
                LOGGER.warn("The producer[{}] end transaction in sending message, rollback the message."
                        + "RequestHeader: {} Remark: {}",
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                    requestHeader.toString(),
                    request.getRemark());
                break;
            }
            default:
                return null;
        }
    }
    OperationResult result = new OperationResult();
    // 处理TRANSACTION_COMMIT_TYPE类型的EndTransactionRequest
    if (MessageSysFlag.TRANSACTION_COMMIT_TYPE == requestHeader.getCommitOrRollback()) {
        result = this.brokerController.getTransactionalMessageService().commitMessage(requestHeader);
        if (result.getResponseCode() == ResponseCode.SUCCESS) {
            RemotingCommand res = checkPrepareMessage(result.getPrepareMessage(), requestHeader);
            if (res.getCode() == ResponseCode.SUCCESS) {
                // 根据取出的Half消息生成内部消息，设置目标Topic,queueId，保存到目标消息队列中
                MessageExtBrokerInner msgInner = endMessageTransaction(result.getPrepareMessage());
                msgInner.setSysFlag(MessageSysFlag.resetTransactionValue(msgInner.getSysFlag(), requestHeader.getCommitOrRollback()));
                msgInner.setQueueOffset(requestHeader.getTranStateTableOffset());
                msgInner.setPreparedTransactionOffset(requestHeader.getCommitLogOffset());
                msgInner.setStoreTimestamp(result.getPrepareMessage().getStoreTimestamp());
                RemotingCommand sendResult = sendFinalMessage(msgInner);
                if (sendResult.getCode() == ResponseCode.SUCCESS) {
                    // 操作成功删除half消息
                    this.brokerController.getTransactionalMessageService().deletePrepareMessage(result.getPrepareMessage());
                }
                return sendResult;
            }
            return res;
        }
    } else if (MessageSysFlag.TRANSACTION_ROLLBACK_TYPE == requestHeader.getCommitOrRollback()) {
        // 处理TRANSACTION_ROLLBACK_TYPE类型的EndTransactionRequest
        // 这里result其实就是拿到的half消息
        result = this.brokerController.getTransactionalMessageService().rollbackMessage(requestHeader);
        if (result.getResponseCode() == ResponseCode.SUCCESS) {
            // 检查half消息
            RemotingCommand res = checkPrepareMessage(result.getPrepareMessage(), requestHeader);
            if (res.getCode() == ResponseCode.SUCCESS) {
                // 操作成功删除half消息
                this.brokerController.getTransactionalMessageService().deletePrepareMessage(result.getPrepareMessage());
            }
            return res;
        }
    }
    response.setCode(result.getResponseCode());
    response.setRemark(result.getResponseRemark());
    return response;
}


@Override
public boolean deletePrepareMessage(MessageExt msgExt) {
    // TransactionalMessageUtil.REMOVETAG = "d"
    if (this.transactionalMessageBridge.putOpMessage(msgExt, TransactionalMessageUtil.REMOVETAG)) {
        log.info("Transaction op message write successfully. messageId={}, queueId={} msgExt:{}", msgExt.getMsgId(), msgExt.getQueueId(), msgExt);
        return true;
    } else {
        log.error("Transaction op message write failed. messageId is {}, queueId is {}", msgExt.getMsgId(), msgExt.getQueueId());
        return false;
    }
}


public boolean putOpMessage(MessageExt messageExt, String opType) {
    // 生成一个MessageQueue，该MessageQueue重写了hashCode,equals方法，如果成员变量值相同，则会被认为是相等的关系。
    MessageQueue messageQueue = new MessageQueue(messageExt.getTopic(),
        this.brokerController.getBrokerConfig().getBrokerName(), messageExt.getQueueId());
    if (TransactionalMessageUtil.REMOVETAG.equals(opType)) {
        return addRemoveTagInTransactionOp(messageExt, messageQueue);
    }
    return true;
}


/**
    * Use this function while transaction msg is committed or rollback write a flag 'd' to operation queue for the
    * msg's offset
    *
    * @param messageExt Op message
    * @param messageQueue Op message queue
    * @return This method will always return true.
    */
private boolean addRemoveTagInTransactionOp(MessageExt messageExt, MessageQueue messageQueue) {
    // op消息的内容其实是half消息的offset （设计文档中也有）
    Message message = new Message(TransactionalMessageUtil.buildOpTopic(), TransactionalMessageUtil.REMOVETAG,
        String.valueOf(messageExt.getQueueOffset()).getBytes(TransactionalMessageUtil.charset));
    writeOp(message, messageQueue);
    return true;
}

private void writeOp(Message message, MessageQueue mq) {
    MessageQueue opQueue;
    if (opQueueMap.containsKey(mq)) {
        opQueue = opQueueMap.get(mq);
    } else {
        opQueue = getOpQueueByHalf(mq);
        MessageQueue oldQueue = opQueueMap.putIfAbsent(mq, opQueue);
        if (oldQueue != null) {
            opQueue = oldQueue;
        }
    }
    if (opQueue == null) {
        opQueue = new MessageQueue(TransactionalMessageUtil.buildOpTopic(), mq.getBrokerName(), mq.getQueueId());
    }
    putMessage(makeOpMessageInner(message, opQueue));
}
```

现在整个事务过程已经分析完成，还有一个回查的事务补偿，下面分析一波：

事务回查的功能封装在transactionalMessageCheckService这个服务线程中，其中transactionalMessageCheckService会在初始化事务的时候进行类加载并且创建

```java
private void initialTransaction() {
    //这里动态加载了TransactionalMessageService和AbstractTransactionalMessageCheckListener的实现类，位于如下
    //"META-INF/service/org.apache.rocketmq.broker.transaction.TransactionalMessageService"
    //"META-INF/service/org.apache.rocketmq.broker.transaction.AbstractTransactionalMessageCheckListener"
    //还创建了TransactionalMessageCheckService
    // 主要是事务相关的类 比如half消息以及回查
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

// 启动的时候是只有master才能启动，和HA服务一起
private void startProcessorByHa(BrokerRole role) {
    if (BrokerRole.SLAVE != role) {
        if (this.transactionalMessageCheckService != null) {
            this.transactionalMessageCheckService.start();
        }
    }
}
```

TransactionalMessageCheckService#check是在onWaitEnd的时候进行回查，当初在run方法找半天没找到以为是merge代码的时候merge没了（逃 

```java
 @Override
protected void onWaitEnd() {
    long timeout = brokerController.getBrokerConfig().getTransactionTimeOut();
    int checkMax = brokerController.getBrokerConfig().getTransactionCheckMax();
    long begin = System.currentTimeMillis();
    log.info("Begin to check prepare message, begin time:{}", begin);
    this.brokerController.getTransactionalMessageService().check(timeout, checkMax, this.brokerController.getTransactionalMessageCheckListener());
    log.info("End to check prepare message, consumed time:{}", System.currentTimeMillis() - begin);
}


@Override
public void check(long transactionTimeout, int transactionCheckMax,
    AbstractTransactionalMessageCheckListener listener) {
    try {
        String topic = MixAll.RMQ_SYS_TRANS_HALF_TOPIC;
        Set<MessageQueue> msgQueues = transactionalMessageBridge.fetchMessageQueues(topic);
        if (msgQueues == null || msgQueues.size() == 0) {
            log.warn("The queue of topic is empty :" + topic);
            return;
        }
        log.debug("Check topic={}, queues={}", topic, msgQueues);
        // 每次check过程都遍历Half消息的所有消息队列
        for (MessageQueue messageQueue : msgQueues) {
            long startTime = System.currentTimeMillis();
            MessageQueue opQueue = getOpQueue(messageQueue);
            // 通过ConsumerOffsetManager().queryOffset()获得Group+Topic+queueId的消费offset
            long halfOffset = transactionalMessageBridge.fetchConsumeOffset(messageQueue);
            long opOffset = transactionalMessageBridge.fetchConsumeOffset(opQueue);
            log.info("Before check, the queue={} msgOffset={} opOffset={}", messageQueue, halfOffset, opOffset);
            if (halfOffset < 0 || opOffset < 0) {
                log.error("MessageQueue: {} illegal offset read: {}, op offset: {},skip this queue", messageQueue,
                    halfOffset, opOffset);
                continue;
            }

            List<Long> doneOpOffset = new ArrayList<>();
            HashMap<Long, Long> removeMap = new HashMap<>();

            // fillOpRemoveMap从OpQueue里拉取op消息并逐个处理，
            // 对于那些offset小于half消息现在消费到的offset的放入doneOpOffset
            // 否则放到removeMap中，该map key为op消息对应的half消息的offset,value为该op消息在op队列中的offset
            PullResult pullResult = fillOpRemoveMap(removeMap, opQueue, opOffset, halfOffset, doneOpOffset);
            if (null == pullResult) {
                log.error("The queue={} check msgOffset={} with opOffset={} failed, pullResult is null",
                    messageQueue, halfOffset, opOffset);
                continue;
            }
            // single thread
            int getMessageNullCount = 1;
            long newOffset = halfOffset;
            // 从当前half队列消费的offset开始逐个遍历
            long i = halfOffset;
            while (true) {
                // 该队列处理的太久了切换下一个队列进行check， MAX_PROCESS_TIME_LIMIT = 1min
                if (System.currentTimeMillis() - startTime > MAX_PROCESS_TIME_LIMIT) {
                    log.info("Queue={} process time reach max={}", messageQueue, MAX_PROCESS_TIME_LIMIT);
                    break;
                }

                // 如果当前消息已经被标记为删除，则从removeMap里删除该offset,处理下一条消息，本条什么也不做
                if (removeMap.containsKey(i)) {
                    log.info("Half offset {} has been committed/rolled back", i);
                    removeMap.remove(i);
                } else {
                    // 不在removeMap里，则需要进行check处理
                    // a) 取出HalfMsg
                    GetResult getResult = getHalfMsg(messageQueue, i);
                    MessageExt msgExt = getResult.getMsg();
                    if (msgExt == null) {
                        if (getMessageNullCount++ > MAX_RETRY_COUNT_WHEN_HALF_NULL) {
                            break;
                        }
                        if (getResult.getPullResult().getPullStatus() == PullStatus.NO_NEW_MSG) {
                            log.debug("No new msg, the miss offset={} in={}, continue check={}, pull result={}", i,
                                messageQueue, getMessageNullCount, getResult.getPullResult());
                            break;
                        } else {
                            log.info("Illegal offset, the miss offset={} in={}, continue check={}, pull result={}",
                                i, messageQueue, getMessageNullCount, getResult.getPullResult());
                            i = getResult.getPullResult().getNextBeginOffset();
                            newOffset = i;
                            continue;
                        }
                    }

                    // b) 检查是否需要丢弃或者需要跳过
                    // needDiscard每次都会修改消息的PROPERTY_TRANSACTION_CHECK_TIMES属性，用于记录被check次数
                    // check次数超过transactionCheckMax或者消息太旧，比Message最长保存时间还久 则都需要丢弃跳过不处理
                    if (needDiscard(msgExt, transactionCheckMax) || needSkip(msgExt)) {
                        listener.resolveDiscardMsg(msgExt);
                        newOffset = i + 1;
                        i++;
                        continue;
                    }

                    // msgExt写入时间大于startTime，则说明是本轮启动check后存入的消息，则中断对应队列的check,换下一个queue
                    if (msgExt.getStoreTimestamp() >= startTime) {
                        log.debug("Fresh stored. the miss offset={}, check it later, store={}", i,
                            new Date(msgExt.getStoreTimestamp()));
                        break;
                    }

                    long valueOfCurrentMinusBorn = System.currentTimeMillis() - msgExt.getBornTimestamp();
                    long checkImmunityTime = transactionTimeout;
                    String checkImmunityTimeStr = msgExt.getUserProperty(MessageConst.PROPERTY_CHECK_IMMUNITY_TIME_IN_SECONDS);
                    if (null != checkImmunityTimeStr) {
                        checkImmunityTime = getImmunityTime(checkImmunityTimeStr, transactionTimeout);
                        // 消息有PROPERTY_CHECK_IMMUNITY_TIME_IN_SECONDS属性，且在checkImmunityTime时间内
                        if (valueOfCurrentMinusBorn < checkImmunityTime) {
                            // 如果该消息产生时间大于checkImmunityTime，则跳过本条消息，进行下一条消息处理
                            // 如果还在checkImmunityTime时间内，则检查消息的PROPERTY_TRANSACTION_PREPARED_QUEUE_OFFSET属性
                            // 如果没有该属性，或者该属性对应的值不在removeMap里，说明还不能跳过本条消息，
                            // 这时根据half消息重新生成一个消息append到half消息队尾，并跳过当前位置，即延后处理
                            // 如果有该属性，则说明被renew过，如果removeMap包含该offset,则跳过本条消息
                            if (checkPrepareQueueOffset(removeMap, doneOpOffset, msgExt)) {
                                newOffset = i + 1;
                                i++;
                                continue;
                            }
                        }
                    } else {
                        // 如果消息产生到现在的时间小于checkImmunityTime， 则当前队列check太早，切换到下一个队列
                        if ((0 <= valueOfCurrentMinusBorn) && (valueOfCurrentMinusBorn < checkImmunityTime)) {
                            log.debug("New arrived, the miss offset={}, check it later checkImmunity={}, born={}", i,
                                checkImmunityTime, new Date(msgExt.getBornTimestamp()));
                            break;
                        }
                    }
                    List<MessageExt> opMsg = pullResult.getMsgFoundList();
                    boolean isNeedCheck = (opMsg == null && valueOfCurrentMinusBorn > checkImmunityTime)
                        || (opMsg != null && (opMsg.get(opMsg.size() - 1).getBornTimestamp() - startTime > transactionTimeout))
                        || (valueOfCurrentMinusBorn <= -1);

                    if (isNeedCheck) {
                        // 重新写入失败则重复本次循环，写入成功则向客端户发送check状态请求
                        if (!putBackHalfMsgQueue(msgExt, i)) {
                            continue;
                        }
                        // 发送CheckTransactionStateRequest给客户端用于check本条消息的状态
                        // 客户端收到请求后根据TransactionId,MsgId等信息提交EndTransaction请求，这样如果久未主动commit/abort的事务消息又可以进行第二阶段的提交
                        listener.resolveHalfMsg(msgExt);
                    } else {
                        pullResult = fillOpRemoveMap(removeMap, opQueue, pullResult.getNextBeginOffset(), halfOffset, doneOpOffset);
                        log.info("The miss offset:{} in messageQueue:{} need to get more opMsg, result is:{}", i,
                            messageQueue, pullResult);
                        continue;
                    }
                }
                newOffset = i + 1;
                i++;
            }
            // 如果half的消费offset有更新则提交新的offset
            if (newOffset != halfOffset) {
                transactionalMessageBridge.updateConsumeOffset(messageQueue, newOffset);
            }
            // op队列消费进度更新为最后一个连续的offset
            long newOpOffset = calculateOpOffset(doneOpOffset, opOffset);
            if (newOpOffset != opOffset) {
                transactionalMessageBridge.updateConsumeOffset(opQueue, newOpOffset);
            }
        }
    } catch (Exception e) {
        e.printStackTrace();
        log.error("Check error", e);
    }

}



/**
    * Read op message, parse op message, and fill removeMap
    *
    * @param removeMap Half message to be remove, key:halfOffset, value: opOffset.
    * @param opQueue Op message queue.
    * @param pullOffsetOfOp The begin offset of op message queue.
    * @param miniOffset The current minimum offset of half message queue.
    * @param doneOpOffset Stored op messages that have been processed.
    * @return Op message result.
    */
private PullResult fillOpRemoveMap(HashMap<Long, Long> removeMap,
    MessageQueue opQueue, long pullOffsetOfOp, long miniOffset, List<Long> doneOpOffset) {
    PullResult pullResult = pullOpMsg(opQueue, pullOffsetOfOp, 32);
    if (null == pullResult) {
        return null;
    }
    if (pullResult.getPullStatus() == PullStatus.OFFSET_ILLEGAL
        || pullResult.getPullStatus() == PullStatus.NO_MATCHED_MSG) {
        log.warn("The miss op offset={} in queue={} is illegal, pullResult={}", pullOffsetOfOp, opQueue,
            pullResult);
        transactionalMessageBridge.updateConsumeOffset(opQueue, pullResult.getNextBeginOffset());
        return pullResult;
    } else if (pullResult.getPullStatus() == PullStatus.NO_NEW_MSG) {
        log.warn("The miss op offset={} in queue={} is NO_NEW_MSG, pullResult={}", pullOffsetOfOp, opQueue,
            pullResult);
        return pullResult;
    }
    // 找到并遍历op消息
    List<MessageExt> opMsg = pullResult.getMsgFoundList();
    if (opMsg == null) {
        log.warn("The miss op offset={} in queue={} is empty, pullResult={}", pullOffsetOfOp, opQueue, pullResult);
        return pullResult;
    }
    for (MessageExt opMessageExt : opMsg) {
        Long queueOffset = getLong(new String(opMessageExt.getBody(), TransactionalMessageUtil.charset));
        log.info("Topic: {} tags: {}, OpOffset: {}, HalfOffset: {}", opMessageExt.getTopic(),
            opMessageExt.getTags(), opMessageExt.getQueueOffset(), queueOffset);
        // 如果op消息的tag为REMOVETAG("d") 即被标记为删除，如果queueOffset < miniOffset放入doneOpOffset，否则放入removeMap
        if (TransactionalMessageUtil.REMOVETAG.equals(opMessageExt.getTags())) {
            if (queueOffset < miniOffset) {
                doneOpOffset.add(opMessageExt.getQueueOffset());
            } else {
                removeMap.put(queueOffset, opMessageExt.getQueueOffset());
            }
        } else {
            log.error("Found a illegal tag in opMessageExt= {} ", opMessageExt);
        }
    }
    log.debug("Remove map: {}", removeMap);
    log.debug("Done op list: {}", doneOpOffset);
    return pullResult;
}



public void resolveHalfMsg(final MessageExt msgExt) {
    executorService.execute(new Runnable() {
        @Override
        public void run() {
            try {
                sendCheckMessage(msgExt);
            } catch (Exception e) {
                LOGGER.error("Send check message error!", e);
            }
        }
    });
}


public void sendCheckMessage(MessageExt msgExt) throws Exception {
    CheckTransactionStateRequestHeader checkTransactionStateRequestHeader = new CheckTransactionStateRequestHeader();
    checkTransactionStateRequestHeader.setCommitLogOffset(msgExt.getCommitLogOffset());
    checkTransactionStateRequestHeader.setOffsetMsgId(msgExt.getMsgId());
    checkTransactionStateRequestHeader.setMsgId(msgExt.getUserProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX));
    checkTransactionStateRequestHeader.setTransactionId(checkTransactionStateRequestHeader.getMsgId());
    checkTransactionStateRequestHeader.setTranStateTableOffset(msgExt.getQueueOffset());
    msgExt.setTopic(msgExt.getUserProperty(MessageConst.PROPERTY_REAL_TOPIC));
    msgExt.setQueueId(Integer.parseInt(msgExt.getUserProperty(MessageConst.PROPERTY_REAL_QUEUE_ID)));
    msgExt.setStoreSize(0);
    String groupId = msgExt.getProperty(MessageConst.PROPERTY_PRODUCER_GROUP);
    Channel channel = brokerController.getProducerManager().getAvaliableChannel(groupId);
    if (channel != null) {
        brokerController.getBroker2Client().checkProducerTransactionState(groupId, channel, checkTransactionStateRequestHeader, msgExt);
    } else {
        LOGGER.warn("Check transaction failed, channel is null. groupId={}", groupId);
    }
}


public void checkProducerTransactionState(
    final String group,
    final Channel channel,
    final CheckTransactionStateRequestHeader requestHeader,
    final MessageExt messageExt) throws Exception {
    RemotingCommand request =
        RemotingCommand.createRequestCommand(RequestCode.CHECK_TRANSACTION_STATE, requestHeader);
    request.setBody(MessageDecoder.encode(messageExt, false));
    try {
        this.brokerController.getRemotingServer().invokeOneway(channel, request, 10);
    } catch (Exception e) {
        log.error("Check transaction failed because invoke producer exception. group={}, msgId={}", group, messageExt.getMsgId(), e.getMessage());
    }
}


// producer收到请求的逻辑


public RemotingCommand checkTransactionState(ChannelHandlerContext ctx,
        RemotingCommand request) throws RemotingCommandException {
    final CheckTransactionStateRequestHeader requestHeader =
        (CheckTransactionStateRequestHeader) request.decodeCommandCustomHeader(CheckTransactionStateRequestHeader.class);
    final ByteBuffer byteBuffer = ByteBuffer.wrap(request.getBody());
    final MessageExt messageExt = MessageDecoder.decode(byteBuffer);
    if (messageExt != null) {
        if (StringUtils.isNotEmpty(this.mqClientFactory.getClientConfig().getNamespace())) {
            messageExt.setTopic(NamespaceUtil
                .withoutNamespace(messageExt.getTopic(), this.mqClientFactory.getClientConfig().getNamespace()));
        }
        String transactionId = messageExt.getProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);
        if (null != transactionId && !"".equals(transactionId)) {
            messageExt.setTransactionId(transactionId);
        }
        final String group = messageExt.getProperty(MessageConst.PROPERTY_PRODUCER_GROUP);
        if (group != null) {
            MQProducerInner producer = this.mqClientFactory.selectProducer(group);
            if (producer != null) {
                final String addr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
                producer.checkTransactionState(addr, messageExt, requestHeader);
            } else {
                log.debug("checkTransactionState, pick producer by group[{}] failed", group);
            }
        } else {
            log.warn("checkTransactionState, pick producer group failed");
        }
    } else {
        log.warn("checkTransactionState, decode message failed");
    }

    return null;
}


@Override
public void checkTransactionState(final String addr, final MessageExt msg,
    final CheckTransactionStateRequestHeader header) {
    Runnable request = new Runnable() {
        private final String brokerAddr = addr;
        private final MessageExt message = msg;
        private final CheckTransactionStateRequestHeader checkRequestHeader = header;
        private final String group = DefaultMQProducerImpl.this.defaultMQProducer.getProducerGroup();

        @Override
        public void run() {
            TransactionCheckListener transactionCheckListener = DefaultMQProducerImpl.this.checkListener();
            TransactionListener transactionListener = getCheckListener();
            if (transactionCheckListener != null || transactionListener != null) {
                LocalTransactionState localTransactionState = LocalTransactionState.UNKNOW;
                Throwable exception = null;
                try {
                    if (transactionCheckListener != null) {
                        localTransactionState = transactionCheckListener.checkLocalTransactionState(message);
                    } else if (transactionListener != null) {
                        log.debug("Used new check API in transaction message");
                        localTransactionState = transactionListener.checkLocalTransaction(message);
                    } else {
                        log.warn("CheckTransactionState, pick transactionListener by group[{}] failed", group);
                    }
                } catch (Throwable e) {
                    log.error("Broker call checkTransactionState, but checkLocalTransactionState exception", e);
                    exception = e;
                }

                this.processTransactionState(
                    localTransactionState,
                    group,
                    exception);
            } else {
                log.warn("CheckTransactionState, pick transactionCheckListener by group[{}] failed", group);
            }
        }

        private void processTransactionState(
            final LocalTransactionState localTransactionState,
            final String producerGroup,
            final Throwable exception) {
            final EndTransactionRequestHeader thisHeader = new EndTransactionRequestHeader();
            thisHeader.setCommitLogOffset(checkRequestHeader.getCommitLogOffset());
            thisHeader.setProducerGroup(producerGroup);
            thisHeader.setTranStateTableOffset(checkRequestHeader.getTranStateTableOffset());
            thisHeader.setFromTransactionCheck(true);

            String uniqueKey = message.getProperties().get(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);
            if (uniqueKey == null) {
                uniqueKey = message.getMsgId();
            }
            thisHeader.setMsgId(uniqueKey);
            thisHeader.setTransactionId(checkRequestHeader.getTransactionId());
            switch (localTransactionState) {
                case COMMIT_MESSAGE:
                    thisHeader.setCommitOrRollback(MessageSysFlag.TRANSACTION_COMMIT_TYPE);
                    break;
                case ROLLBACK_MESSAGE:
                    thisHeader.setCommitOrRollback(MessageSysFlag.TRANSACTION_ROLLBACK_TYPE);
                    log.warn("when broker check, client rollback this transaction, {}", thisHeader);
                    break;
                case UNKNOW:
                    thisHeader.setCommitOrRollback(MessageSysFlag.TRANSACTION_NOT_TYPE);
                    log.warn("when broker check, client does not know this transaction state, {}", thisHeader);
                    break;
                default:
                    break;
            }

            String remark = null;
            if (exception != null) {
                remark = "checkLocalTransactionState Exception: " + RemotingHelper.exceptionSimpleDesc(exception);
            }

            try {
                DefaultMQProducerImpl.this.mQClientFactory.getMQClientAPIImpl().endTransactionOneway(brokerAddr, thisHeader, remark,
                    3000);
            } catch (Exception e) {
                log.error("endTransactionOneway exception", e);
            }
        }
    };

    this.checkExecutor.submit(request);
}

```


以上的TransactionCheckListener和TransactionCheckListener貌似在新版本要删除，新版本采用了TransactionListener接口，将两个功能合二为一了。

以上就是事务消息的所有内容了，下面再看看官方的example
```java
public class TransactionProducer {
    public static void main(String[] args) throws MQClientException, InterruptedException {
        TransactionListener transactionListener = new TransactionListenerImpl();
        TransactionMQProducer producer = new TransactionMQProducer("please_rename_unique_group_name");
        ExecutorService executorService = new ThreadPoolExecutor(2, 5, 100, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2000), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("client-transaction-msg-check-thread");
                return thread;
            }
        });

        producer.setExecutorService(executorService);
        producer.setTransactionListener(transactionListener);
        producer.start();

        String[] tags = new String[] {"TagA", "TagB", "TagC", "TagD", "TagE"};
        for (int i = 0; i < 10; i++) {
            try {
                Message msg =
                    new Message("TopicTest1234", tags[i % tags.length], "KEY" + i,
                        ("Hello RocketMQ " + i).getBytes(RemotingHelper.DEFAULT_CHARSET));
                SendResult sendResult = producer.sendMessageInTransaction(msg, null);
                System.out.printf("%s%n", sendResult);

                Thread.sleep(10);
            } catch (MQClientException | UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

        for (int i = 0; i < 100000; i++) {
            Thread.sleep(1000);
        }
        producer.shutdown();
    }
}


public class TransactionListenerImpl implements TransactionListener {
    private AtomicInteger transactionIndex = new AtomicInteger(0);

    private ConcurrentHashMap<String, Integer> localTrans = new ConcurrentHashMap<>();

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        int value = transactionIndex.getAndIncrement();
        int status = value % 3;
        localTrans.put(msg.getTransactionId(), status);
        return LocalTransactionState.UNKNOW;
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        Integer status = localTrans.get(msg.getTransactionId());
        if (null != status) {
            switch (status) {
                case 0:
                    return LocalTransactionState.UNKNOW;
                case 1:
                    return LocalTransactionState.COMMIT_MESSAGE;
                case 2:
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                default:
                    return LocalTransactionState.COMMIT_MESSAGE;
            }
        }
        return LocalTransactionState.COMMIT_MESSAGE;
    }
}
```
