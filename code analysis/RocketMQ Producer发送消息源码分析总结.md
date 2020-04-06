# RocketMQ-Producer 发送消息源码分析总结

首先看Message类的基本字段
```java
public class Message implements Serializable {
    private static final long serialVersionUID = 8445773977080406428L;

    // topic名
    private String topic;
    // 标识符
    private int flag;
    // message的属性
    private Map<String, String> properties;
    // 消息体
    private byte[] body;
    // 事务ID todo：事务再分析
    private String transactionId;

    ...
}

// properties 的一些属性
public class MessageConst {
    public static final String PROPERTY_KEYS = "KEYS";
    public static final String PROPERTY_TAGS = "TAGS";
    public static final String PROPERTY_WAIT_STORE_MSG_OK = "WAIT";
    public static final String PROPERTY_DELAY_TIME_LEVEL = "DELAY";
    public static final String PROPERTY_RETRY_TOPIC = "RETRY_TOPIC";
    public static final String PROPERTY_REAL_TOPIC = "REAL_TOPIC";
    public static final String PROPERTY_REAL_QUEUE_ID = "REAL_QID";
    public static final String PROPERTY_TRANSACTION_PREPARED = "TRAN_MSG";
    public static final String PROPERTY_PRODUCER_GROUP = "PGROUP";
    public static final String PROPERTY_MIN_OFFSET = "MIN_OFFSET";
    public static final String PROPERTY_MAX_OFFSET = "MAX_OFFSET";
    public static final String PROPERTY_BUYER_ID = "BUYER_ID";
    public static final String PROPERTY_ORIGIN_MESSAGE_ID = "ORIGIN_MESSAGE_ID";
    public static final String PROPERTY_TRANSFER_FLAG = "TRANSFER_FLAG";
    public static final String PROPERTY_CORRECTION_FLAG = "CORRECTION_FLAG";
    public static final String PROPERTY_MQ2_FLAG = "MQ2_FLAG";
    public static final String PROPERTY_RECONSUME_TIME = "RECONSUME_TIME";
    public static final String PROPERTY_MSG_REGION = "MSG_REGION";
    public static final String PROPERTY_TRACE_SWITCH = "TRACE_ON";
    public static final String PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX = "UNIQ_KEY";
    public static final String PROPERTY_MAX_RECONSUME_TIMES = "MAX_RECONSUME_TIMES";
    public static final String PROPERTY_CONSUME_START_TIMESTAMP = "CONSUME_START_TIME";
    public static final String PROPERTY_TRANSACTION_PREPARED_QUEUE_OFFSET = "TRAN_PREPARED_QUEUE_OFFSET";
    public static final String PROPERTY_TRANSACTION_CHECK_TIMES = "TRANSACTION_CHECK_TIMES";
    public static final String PROPERTY_CHECK_IMMUNITY_TIME_IN_SECONDS = "CHECK_IMMUNITY_TIME_IN_SECONDS";
}
 ```

RocketMQ 发送消息支持三种发送模式
* 同步
* 异步（异步的发送回调支持成功发送以及失败发送）
* 单程

发送消息的示例代码如下：

```java
public class Producer {
    public static void main(String[] args) throws MQClientException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("please_rename_unique_group_name");
        producer.setNamesrvAddr("127.0.0.1:9876");
        producer.start();

        for (int i = 0; i < 1000; i++) {
            try {

                /*
                 * Create a message instance, specifying topic, tag and message body.
                 */
                Message msg = new Message("TopicTest" /* Topic */,
                    "TagA" /* Tag */,
                    ("Hello RocketMQ " + i).getBytes(RemotingHelper.DEFAULT_CHARSET) /* Message body */
                );

                /*
                 * Call send message to deliver message to one of brokers.
                 */
                SendResult sendResult = producer.send(msg);

                System.out.printf("%s%n", sendResult);
            } catch (Exception e) {
                e.printStackTrace();
                Thread.sleep(1000);
            }
        }
        producer.shutdown();
    }
}

```

方法入口：
```java
DefaultMQProducer#send(Message);
```
```java
private SendResult sendDefaultImpl(
    Message msg,
    final CommunicationMode communicationMode,
    final SendCallback sendCallback,
    final long timeout
) throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
    this.makeSureStateOK();
    Validators.checkMessage(msg, this.defaultMQProducer);

    final long invokeID = random.nextLong();
    // 记录开始时间
    long beginTimestampFirst = System.currentTimeMillis();
    long beginTimestampPrev = beginTimestampFirst;
    long endTimestamp = beginTimestampFirst;
    // 获得路由信息
    TopicPublishInfo topicPublishInfo = this.tryToFindTopicPublishInfo(msg.getTopic());
    // 如果存在路由信息并且信息可用则走下面逻辑
    if (topicPublishInfo != null && topicPublishInfo.ok()) {
        boolean callTimeout = false;
        MessageQueue mq = null;
        Exception exception = null;
        SendResult sendResult = null;
        // 同步发送次数最多3次（1次正常+2次重试）而单程只发送一次（即不重试） 异步有自己的重试机制
        int timesTotal = communicationMode == CommunicationMode.SYNC ? 1 + this.defaultMQProducer.getRetryTimesWhenSendFailed() : 1;
        int times = 0;
        String[] brokersSent = new String[timesTotal];
        for (; times < timesTotal; times++) {
            String lastBrokerName = null == mq ? null : mq.getBrokerName();
            MessageQueue mqSelected = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName);
            // 取到MQ
            if (mqSelected != null) {
                mq = mqSelected;
                // 第 times 次发送的broker
                brokersSent[times] = mq.getBrokerName();
                try {
                    //发送start时间
                    beginTimestampPrev = System.currentTimeMillis();
                    // 如果当前为重发 （非第一次发）
                    if (times > 0) {
                        //Reset topic with namespace during resend. 发送到指定的topic，
                        msg.setTopic(this.defaultMQProducer.withNamespace(msg.getTopic()));
                    }
                    // 花费时间
                    long costTime = beginTimestampPrev - beginTimestampFirst;
                    if (timeout < costTime) {
                        // 超时 退出循环
                        callTimeout = true;
                        break;
                    }
                    // 正式发送消息
                    sendResult = this.sendKernelImpl(msg, mq, communicationMode, sendCallback, topicPublishInfo, timeout - costTime);
                    // 结束时间戳
                    endTimestamp = System.currentTimeMillis();
                    // 更新容错对象
                    this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, false);
                    switch (communicationMode) {
                        // 只有同步会返回result
                        case ASYNC:
                            return null;
                        case ONEWAY:
                            return null;
                        case SYNC:
                            // 如果没有发送成功，并且允许发送给Broker失败后，重新选择Broker发送，则重新选择broker发送
                            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                                if (this.defaultMQProducer.isRetryAnotherBrokerWhenNotStoreOK()) {
                                    continue;
                                }
                            }
                            return sendResult;
                        default:
                            break;
                    }
                } catch (RemotingException e) {
                    endTimestamp = System.currentTimeMillis();
                    this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                    log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                    log.warn(msg.toString());
                    exception = e;
                    continue;
                } catch (MQClientException e) {
                    endTimestamp = System.currentTimeMillis();
                    this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                    log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                    log.warn(msg.toString());
                    exception = e;
                    continue;
                } catch (MQBrokerException e) {
                    endTimestamp = System.currentTimeMillis();
                    this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, true);
                    log.warn(String.format("sendKernelImpl exception, resend at once, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                    log.warn(msg.toString());
                    exception = e;
                    switch (e.getResponseCode()) {
                        case ResponseCode.TOPIC_NOT_EXIST:
                        case ResponseCode.SERVICE_NOT_AVAILABLE:
                        case ResponseCode.SYSTEM_ERROR:
                        case ResponseCode.NO_PERMISSION:
                        case ResponseCode.NO_BUYER_ID:
                        case ResponseCode.NOT_IN_CURRENT_UNIT:
                            continue;
                        default:
                            if (sendResult != null) {
                                return sendResult;
                            }

                            throw e;
                    }
                } catch (InterruptedException e) {
                    endTimestamp = System.currentTimeMillis();
                    this.updateFaultItem(mq.getBrokerName(), endTimestamp - beginTimestampPrev, false);
                    log.warn(String.format("sendKernelImpl exception, throw exception, InvokeID: %s, RT: %sms, Broker: %s", invokeID, endTimestamp - beginTimestampPrev, mq), e);
                    log.warn(msg.toString());

                    log.warn("sendKernelImpl exception", e);
                    log.warn(msg.toString());
                    throw e;
                }
            } else {
                break;
            }
        }

        if (sendResult != null) {
            return sendResult;
        }

        String info = String.format("Send [%d] times, still failed, cost [%d]ms, Topic: %s, BrokersSent: %s",
            times,
            System.currentTimeMillis() - beginTimestampFirst,
            msg.getTopic(),
            Arrays.toString(brokersSent));

        info += FAQUrl.suggestTodo(FAQUrl.SEND_MSG_FAILED);

        MQClientException mqClientException = new MQClientException(info, exception);
        if (callTimeout) {
            throw new RemotingTooMuchRequestException("sendDefaultImpl call timeout");
        }

        if (exception instanceof MQBrokerException) {
            mqClientException.setResponseCode(((MQBrokerException) exception).getResponseCode());
        } else if (exception instanceof RemotingConnectException) {
            mqClientException.setResponseCode(ClientErrorCode.CONNECT_BROKER_EXCEPTION);
        } else if (exception instanceof RemotingTimeoutException) {
            mqClientException.setResponseCode(ClientErrorCode.ACCESS_BROKER_TIMEOUT);
        } else if (exception instanceof MQClientException) {
            mqClientException.setResponseCode(ClientErrorCode.BROKER_NOT_EXIST_EXCEPTION);
        }

        throw mqClientException;
    }

    List<String> nsList = this.getmQClientFactory().getMQClientAPIImpl().getNameServerAddressList();
    if (null == nsList || nsList.isEmpty()) {
        throw new MQClientException(
            "No name server address, please set it." + FAQUrl.suggestTodo(FAQUrl.NAME_SERVER_ADDR_NOT_EXIST_URL), null).setResponseCode(ClientErrorCode.NO_NAME_SERVER_EXCEPTION);
    }

    throw new MQClientException("No route info of this topic, " + msg.getTopic() + FAQUrl.suggestTodo(FAQUrl.NO_TOPIC_ROUTE_INFO),
        null).setResponseCode(ClientErrorCode.NOT_FOUND_TOPIC_EXCEPTION);
}
```
根据要发送的topic到路由表中查询路由信息，如果路由表找不到则要通过Namesrv查找路由信息，这个过程在producer启动时分析过

```java
private TopicPublishInfo tryToFindTopicPublishInfo(final String topic) {
    // 缓存中尝试获取
    TopicPublishInfo topicPublishInfo = this.topicPublishInfoTable.get(topic);
    // 若是不存在，或者消息队列不可用（ok不成立）：刷新路由信息
    if (null == topicPublishInfo || !topicPublishInfo.ok()) {
        this.topicPublishInfoTable.putIfAbsent(topic, new TopicPublishInfo());
        this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic);
        topicPublishInfo = this.topicPublishInfoTable.get(topic);
    }

    // 若是存在，且有路由信息消息队列可用，则直接返回topicPublishInfo
    // 否则还需要调用updateTopicRouteInfoFromNameServer来进行一次更新
    if (topicPublishInfo.isHaveTopicRouterInfo() || topicPublishInfo.ok()) {
        return topicPublishInfo;
    } else {
        this.mQClientFactory.updateTopicRouteInfoFromNameServer(topic, true, this.defaultMQProducer);
        topicPublishInfo = this.topicPublishInfoTable.get(topic);
        return topicPublishInfo;
    }
}
```
接下来会在topic中选择一条Queue进行发送，具体逻辑如下
```java
public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
    // 如果开启了延迟容错开关
    if (this.sendLatencyFaultEnable) {
        try {
            // 通过threadlocal得到线程对应的下标，再通过取模获得对应的队列
            int index = tpInfo.getSendWhichQueue().getAndIncrement();
            // 循环遍历topic的队列集合
            for (int i = 0; i < tpInfo.getMessageQueueList().size(); i++) {
                // 进行取模运算得到pis
                int pos = Math.abs(index++) % tpInfo.getMessageQueueList().size();
                if (pos < 0)
                    pos = 0;
                // 取得对应下标的qUEUE
                MessageQueue mq = tpInfo.getMessageQueueList().get(pos);
                // 如果这个Queue的Broker是可用的，具体怎样算可用的，看下一段
                if (latencyFaultTolerance.isAvailable(mq.getBrokerName())) {
                    if (null == lastBrokerName || mq.getBrokerName().equals(lastBrokerName))
                        return mq;
                }
            }
            // 如果一个没能获取MQ
            final String notBestBroker = latencyFaultTolerance.pickOneAtLeast();
            int writeQueueNums = tpInfo.getQueueIdByBroker(notBestBroker);
            if (writeQueueNums > 0) {
                final MessageQueue mq = tpInfo.selectOneMessageQueue();
                if (notBestBroker != null) {
                    mq.setBrokerName(notBestBroker);
                    mq.setQueueId(tpInfo.getSendWhichQueue().getAndIncrement() % writeQueueNums);
                }
                return mq;
            } else {
                latencyFaultTolerance.remove(notBestBroker);
            }
        } catch (Exception e) {
            log.error("Error occurred when selecting message queue", e);
        }

        return tpInfo.selectOneMessageQueue();
    }

    // 如果没开开关，则直接通过随机取模获得
    return tpInfo.selectOneMessageQueue(lastBrokerName);
}

@Override
public boolean isAvailable(final String name) {
//        如果faultItem 中不存在该broker，返回true，当存在时，还需判断isAvailable
    final FaultItem faultItem = this.faultItemTable.get(name);
    if (faultItem != null) {
        return faultItem.isAvailable();
    }
    return true;
}

public boolean isAvailable() {
    return (System.currentTimeMillis() - startTimestamp) >= 0;
}
```
其中sendLatencyFaultEnable字段是RocketMQ的一个新特性，打开后所有的broker延迟信息都会被记录；发送消息时会选择延迟最低的broker来发送，提高效率；broker延迟过高会自动减少它的消息分配，充分发挥所有服务器的能力。

主要实现是通过一个延迟容错对象，它维护了延迟broker的信息
```java
private final LatencyFaultTolerance<String> latencyFaultTolerance = new LatencyFaultToleranceImpl();

// 内部实现为一个ConcurrentHashMap

private final ConcurrentHashMap<String, FaultItem> faultItemTable = new ConcurrentHashMap<String, FaultItem>(16);

```



下面看看发送的具体逻辑吧
```java
private SendResult sendKernelImpl(final Message msg,
                                final MessageQueue mq,
                                final CommunicationMode communicationMode,
                                final SendCallback sendCallback,
                                final TopicPublishInfo topicPublishInfo,
                                final long timeout) throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
    // 开始时间
    long beginStartTime = System.currentTimeMillis();
    // 通过缓存获得broker地址
    String brokerAddr = this.mQClientFactory.findBrokerAddressInPublish(mq.getBrokerName());
    if (null == brokerAddr) {
        // 如果地址为空 则尝试获取topic路由信息
        tryToFindTopicPublishInfo(mq.getTopic());
        // 再次获得broker地址
        brokerAddr = this.mQClientFactory.findBrokerAddressInPublish(mq.getBrokerName());
    }

    // 信息内容
    SendMessageContext context = null;
    // 找到了broker地址
    if (brokerAddr != null) {
        // 如果设置了VIP（高优先级队列）通道，那么这里将根据brokerAddr获取VIP通道的的地址 即端口-2
        brokerAddr = MixAll.brokerVIPChannel(this.defaultMQProducer.isSendMessageWithVIPChannel(), brokerAddr);

        // 得到消息内容的字节数组
        byte[] prevBody = msg.getBody();
        try {
            //for MessageBatch,ID has been set in the generating process
            // 如果是单条消息（即不是批量发送），给消息设置独特id
            // id主要有PID、IP地址，ClassLoader的HashCode、时间戳组成
            if (!(msg instanceof MessageBatch)) {
                MessageClientIDSetter.setUniqID(msg);
            }

            boolean topicWithNamespace = false;
            // 如果producer有namespace
            if (null != this.mQClientFactory.getClientConfig().getNamespace()) {
                // 给消息设置instanceId
                msg.setInstanceId(this.mQClientFactory.getClientConfig().getNamespace());
                topicWithNamespace = true;
            }

            // 标识符
            int sysFlag = 0;
            // 消息压缩
            boolean msgBodyCompressed = false;
            // 如果返回 true 则需要压缩并且压缩成功
            // 如果超过4KB，并且不是批处理发送，进行压缩
            if (this.tryToCompressMessage(msg)) {
                // 在标识符上进行压缩标记
                sysFlag |= MessageSysFlag.COMPRESSED_FLAG;
                msgBodyCompressed = true;
            }

            // 事务相关 todo 事务相关
            final String tranMsg = msg.getProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED);
            if (tranMsg != null && Boolean.parseBoolean(tranMsg)) {
                sysFlag |= MessageSysFlag.TRANSACTION_PREPARED_TYPE;
            }

            // 查看是否有hook 有则执行
            if (hasCheckForbiddenHook()) {
                CheckForbiddenContext checkForbiddenContext = new CheckForbiddenContext();
                checkForbiddenContext.setNameSrvAddr(this.defaultMQProducer.getNamesrvAddr());
                checkForbiddenContext.setGroup(this.defaultMQProducer.getProducerGroup());
                checkForbiddenContext.setCommunicationMode(communicationMode);
                checkForbiddenContext.setBrokerAddr(brokerAddr);
                checkForbiddenContext.setMessage(msg);
                checkForbiddenContext.setMq(mq);
                checkForbiddenContext.setUnitMode(this.isUnitMode());
                this.executeCheckForbiddenHook(checkForbiddenContext);
            }

            // 查看是否有hook 有则执行
            if (this.hasSendMessageHook()) {
                context = new SendMessageContext();
                context.setProducer(this);
                context.setProducerGroup(this.defaultMQProducer.getProducerGroup());
                context.setCommunicationMode(communicationMode);
                context.setBornHost(this.defaultMQProducer.getClientIP());
                context.setBrokerAddr(brokerAddr);
                context.setMessage(msg);
                context.setMq(mq);
                context.setNamespace(this.defaultMQProducer.getNamespace());
                String isTrans = msg.getProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED);
                if (isTrans != null && isTrans.equals("true")) {
                    context.setMsgType(MessageType.Trans_Msg_Half);
                }

                if (msg.getProperty("__STARTDELIVERTIME") != null || msg.getProperty(MessageConst.PROPERTY_DELAY_TIME_LEVEL) != null) {
                    context.setMsgType(MessageType.Delay_Msg);
                }
                this.executeSendMessageHookBefore(context);
            }

            // 设置消息请求头
            SendMessageRequestHeader requestHeader = new SendMessageRequestHeader();
            requestHeader.setProducerGroup(this.defaultMQProducer.getProducerGroup());
            requestHeader.setTopic(msg.getTopic());
            requestHeader.setDefaultTopic(this.defaultMQProducer.getCreateTopicKey());
            requestHeader.setDefaultTopicQueueNums(this.defaultMQProducer.getDefaultTopicQueueNums());
            requestHeader.setQueueId(mq.getQueueId());
            requestHeader.setSysFlag(sysFlag);
            requestHeader.setBornTimestamp(System.currentTimeMillis());
            requestHeader.setFlag(msg.getFlag());
            requestHeader.setProperties(MessageDecoder.messageProperties2String(msg.getProperties()));
            requestHeader.setReconsumeTimes(0);
            requestHeader.setUnitMode(this.isUnitMode());
            // 设置是否为批量发送
            requestHeader.setBatch(msg instanceof MessageBatch);
            // 如果当前程序属于是重试发送
            if (requestHeader.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                String reconsumeTimes = MessageAccessor.getReconsumeTime(msg);
                if (reconsumeTimes != null) {
                    // 设置重试次数
                    requestHeader.setReconsumeTimes(Integer.valueOf(reconsumeTimes));
                    MessageAccessor.clearProperty(msg, MessageConst.PROPERTY_RECONSUME_TIME);
                }

                // 设置最大重试次数
                String maxReconsumeTimes = MessageAccessor.getMaxReconsumeTimes(msg);
                if (maxReconsumeTimes != null) {
                    requestHeader.setMaxReconsumeTimes(Integer.valueOf(maxReconsumeTimes));
                    MessageAccessor.clearProperty(msg, MessageConst.PROPERTY_MAX_RECONSUME_TIMES);
                }
            }

            SendResult sendResult = null;
            // 判断发送模式
            switch (communicationMode) {
                case ASYNC:
                    Message tmpMessage = msg;
                    boolean messageCloned = false;
                    // 如果消息压缩了
                    if (msgBodyCompressed) {
                        // rocketmq-cpp/src/producer/DefaultMQProducer.cpp: tryToCompressMessage函数没有判断msg是否被压缩过，
                        // 只判断了是否过大，如果压缩后发送失败进行重发并且body大小仍然>=getCompressMsgBodyOverHowmuch()， body会被压缩两次
                        //If msg body was compressed, msgbody should be reset using prevBody.
                        //Clone new message using commpressed message body and recover origin massage.
                        //Fix bug:https://github.com/apache/rocketmq-externals/issues/66
                        tmpMessage = MessageAccessor.cloneMessage(msg);
                        // 克隆一份临时消息
                        messageCloned = true;
                        // 压缩前的消息
                        msg.setBody(prevBody);
                    }

                    if (topicWithNamespace) {
                        if (!messageCloned) {
                            tmpMessage = MessageAccessor.cloneMessage(msg);
                            messageCloned = true;
                        }
                        //将namespace去掉
                        msg.setTopic(NamespaceUtil.withoutNamespace(msg.getTopic(), this.defaultMQProducer.getNamespace()));
                    }

                    long costTimeAsync = System.currentTimeMillis() - beginStartTime;
                    if (timeout < costTimeAsync) {
                        throw new RemotingTooMuchRequestException("sendKernelImpl call timeout");
                    }
                    sendResult = this.mQClientFactory.getMQClientAPIImpl().sendMessage(
                        brokerAddr,
                        mq.getBrokerName(),
                        tmpMessage,
                        requestHeader,
                        timeout - costTimeAsync,
                        communicationMode,
                        sendCallback,
                        topicPublishInfo,
                        this.mQClientFactory,
                        this.defaultMQProducer.getRetryTimesWhenSendAsyncFailed(),
                        context,
                        this);
                    break;
                case ONEWAY:
                case SYNC:
                    long costTimeSync = System.currentTimeMillis() - beginStartTime;
                    if (timeout < costTimeSync) {
                        throw new RemotingTooMuchRequestException("sendKernelImpl call timeout");
                    }
                    sendResult = this.mQClientFactory.getMQClientAPIImpl().sendMessage(
                        brokerAddr,
                        mq.getBrokerName(),
                        msg,
                        requestHeader,
                        timeout - costTimeSync,
                        communicationMode,
                        context,
                        this);
                    break;
                default:
                    assert false;
                    break;
            }

            if (this.hasSendMessageHook()) {
                context.setSendResult(sendResult);
                this.executeSendMessageHookAfter(context);
            }

            return sendResult;
        } catch (RemotingException e) {
            if (this.hasSendMessageHook()) {
                context.setException(e);
                this.executeSendMessageHookAfter(context);
            }
            throw e;
        } catch (MQBrokerException e) {
            if (this.hasSendMessageHook()) {
                context.setException(e);
                this.executeSendMessageHookAfter(context);
            }
            throw e;
        } catch (InterruptedException e) {
            if (this.hasSendMessageHook()) {
                context.setException(e);
                this.executeSendMessageHookAfter(context);
            }
            throw e;
        } finally {
            // 如果出错 设置为压缩前防止二次压缩
            msg.setBody(prevBody);
            msg.setTopic(NamespaceUtil.withoutNamespace(msg.getTopic(), this.defaultMQProducer.getNamespace()));
        }
    }

    throw new MQClientException("The broker[" + mq.getBrokerName() + "] not exist", null);
}


public SendResult sendMessage(
    final String addr,
    final String brokerName,
    final Message msg,
    final SendMessageRequestHeader requestHeader,
    final long timeoutMillis,
    final CommunicationMode communicationMode,
    final SendCallback sendCallback,
    final TopicPublishInfo topicPublishInfo,
    final MQClientInstance instance,
    final int retryTimesWhenSendFailed,
    final SendMessageContext context,
    final DefaultMQProducerImpl producer
) throws RemotingException, MQBrokerException, InterruptedException {
    long beginStartTime = System.currentTimeMillis();
    RemotingCommand request = null;
    // V2性能更高 因为字段名变 abcdefg  太丧心病狂了。。
    if (sendSmartMsg || msg instanceof MessageBatch) {
        SendMessageRequestHeaderV2 requestHeaderV2 = SendMessageRequestHeaderV2.createSendMessageRequestHeaderV2(requestHeader);
        request = RemotingCommand.createRequestCommand(msg instanceof MessageBatch ? RequestCode.SEND_BATCH_MESSAGE : RequestCode.SEND_MESSAGE_V2, requestHeaderV2);
    } else {
        request = RemotingCommand.createRequestCommand(RequestCode.SEND_MESSAGE, requestHeader);
    }

    request.setBody(msg.getBody());

    switch (communicationMode) {
        case ONEWAY:
            // 发送单程信息
            this.remotingClient.invokeOneway(addr, request, timeoutMillis);
            return null;
        case ASYNC:
            // 异步
            final AtomicInteger times = new AtomicInteger();
            long costTimeAsync = System.currentTimeMillis() - beginStartTime;
            if (timeoutMillis < costTimeAsync) {
                throw new RemotingTooMuchRequestException("sendMessage call timeout");
            }
            this.sendMessageAsync(addr, brokerName, msg, timeoutMillis - costTimeAsync, request, sendCallback, topicPublishInfo, instance,
                retryTimesWhenSendFailed, times, context, producer);
            return null;
        case SYNC:
            // 同步
            long costTimeSync = System.currentTimeMillis() - beginStartTime;
            if (timeoutMillis < costTimeSync) {
                throw new RemotingTooMuchRequestException("sendMessage call timeout");
            }
            return this.sendMessageSync(addr, brokerName, msg, timeoutMillis - costTimeSync, request);
        default:
            assert false;
            break;
    }

    return null;
}

// 这里分析下异步发送，同步和单程都比较简单
private void sendMessageAsync(
    final String addr,
    final String brokerName,
    final Message msg,
    final long timeoutMillis,
    final RemotingCommand request,
    final SendCallback sendCallback,
    final TopicPublishInfo topicPublishInfo,
    final MQClientInstance instance,
    final int retryTimesWhenSendFailed,
    final AtomicInteger times,
    final SendMessageContext context,
    final DefaultMQProducerImpl producer
) throws InterruptedException, RemotingException {
    this.remotingClient.invokeAsync(addr, request, timeoutMillis, new InvokeCallback() {
        @Override
        public void operationComplete(ResponseFuture responseFuture) {
            RemotingCommand response = responseFuture.getResponseCommand();
            if (null == sendCallback && response != null) {

                try {
                    SendResult sendResult = MQClientAPIImpl.this.processSendResponse(brokerName, msg, response);
                    if (context != null && sendResult != null) {
                        context.setSendResult(sendResult);
                        context.getProducer().executeSendMessageHookAfter(context);
                    }
                } catch (Throwable e) {
                }

                producer.updateFaultItem(brokerName, System.currentTimeMillis() - responseFuture.getBeginTimestamp(), false);
                return;
            }

            if (response != null) {
                try {
                    SendResult sendResult = MQClientAPIImpl.this.processSendResponse(brokerName, msg, response);
                    assert sendResult != null;
                    if (context != null) {
                        context.setSendResult(sendResult);
                        context.getProducer().executeSendMessageHookAfter(context);
                    }

                    try {
                        sendCallback.onSuccess(sendResult);
                    } catch (Throwable e) {
                    }

                    producer.updateFaultItem(brokerName, System.currentTimeMillis() - responseFuture.getBeginTimestamp(), false);
                } catch (Exception e) {
                    // 此时不需要重发
                    producer.updateFaultItem(brokerName, System.currentTimeMillis() - responseFuture.getBeginTimestamp(), true);
                    onExceptionImpl(brokerName, msg, 0L, request, sendCallback, topicPublishInfo, instance,
                        retryTimesWhenSendFailed, times, e, context, false, producer);
                }
            } else {
                producer.updateFaultItem(brokerName, System.currentTimeMillis() - responseFuture.getBeginTimestamp(), true);
                if (!responseFuture.isSendRequestOK()) {
                    MQClientException ex = new MQClientException("send request failed", responseFuture.getCause());
                    onExceptionImpl(brokerName, msg, 0L, request, sendCallback, topicPublishInfo, instance,
                        retryTimesWhenSendFailed, times, ex, context, true, producer);
                } else if (responseFuture.isTimeout()) {
                    MQClientException ex = new MQClientException("wait response timeout " + responseFuture.getTimeoutMillis() + "ms",
                        responseFuture.getCause());
                    onExceptionImpl(brokerName, msg, 0L, request, sendCallback, topicPublishInfo, instance,
                        retryTimesWhenSendFailed, times, ex, context, true, producer);
                } else {
                    MQClientException ex = new MQClientException("unknow reseaon", responseFuture.getCause());
                    onExceptionImpl(brokerName, msg, 0L, request, sendCallback, topicPublishInfo, instance,
                        retryTimesWhenSendFailed, times, ex, context, true, producer);
                }
            }
        }
    });
}

private SendResult processSendResponse(
    final String brokerName,
    final Message msg,
    final RemotingCommand response
) throws MQBrokerException, RemotingCommandException {
    switch (response.getCode()) {
        // 刷新磁盘超时
        case ResponseCode.FLUSH_DISK_TIMEOUT:
        // 主从同步超时
        case ResponseCode.FLUSH_SLAVE_TIMEOUT:
        // 从节点不可用
        case ResponseCode.SLAVE_NOT_AVAILABLE: {
        }
        case ResponseCode.SUCCESS: {
            SendStatus sendStatus = SendStatus.SEND_OK;
            switch (response.getCode()) {
                case ResponseCode.FLUSH_DISK_TIMEOUT:
                    sendStatus = SendStatus.FLUSH_DISK_TIMEOUT;
                    break;
                case ResponseCode.FLUSH_SLAVE_TIMEOUT:
                    sendStatus = SendStatus.FLUSH_SLAVE_TIMEOUT;
                    break;
                case ResponseCode.SLAVE_NOT_AVAILABLE:
                    sendStatus = SendStatus.SLAVE_NOT_AVAILABLE;
                    break;
                case ResponseCode.SUCCESS:
                    sendStatus = SendStatus.SEND_OK;
                    break;
                default:
                    assert false;
                    break;
            }

            // 反射得到SendMessageResponseHeader
                SendMessageResponseHeader responseHeader =
                    (SendMessageResponseHeader) response.decodeCommandCustomHeader(SendMessageResponseHeader.class);

                //If namespace not null , reset Topic without namespace.
                String topic = msg.getTopic();
                if (StringUtils.isNotEmpty(this.clientConfig.getNamespace())) {
                    topic = NamespaceUtil.withoutNamespace(topic, this.clientConfig.getNamespace());
                }

                MessageQueue messageQueue = new MessageQueue(topic, brokerName, responseHeader.getQueueId());

                String uniqMsgId = MessageClientIDSetter.getUniqID(msg);
                // 如果是批量发送
                if (msg instanceof MessageBatch) {
                    StringBuilder sb = new StringBuilder();
                    for (Message message : (MessageBatch) msg) {
                        // uniqID
                        sb.append(sb.length() == 0 ? "" : ",").append(MessageClientIDSetter.getUniqID(message));
                    }
                    uniqMsgId = sb.toString();
                }
                // 创建Result对象
                SendResult sendResult = new SendResult(sendStatus,
                    uniqMsgId,
                    responseHeader.getMsgId(), messageQueue, responseHeader.getQueueOffset());
                // 设置事务ID
                sendResult.setTransactionId(responseHeader.getTransactionId());
                // 得到regionId
                String regionId = response.getExtFields().get(MessageConst.PROPERTY_MSG_REGION);
                // 得到trace on
                String traceOn = response.getExtFields().get(MessageConst.PROPERTY_TRACE_SWITCH);
                if (regionId == null || regionId.isEmpty()) {
                    regionId = MixAll.DEFAULT_TRACE_REGION_ID;
                }
                // todo：traceOn字段的用途
                if (traceOn != null && traceOn.equals("false")) {
                    sendResult.setTraceOn(false);
                } else {
                    sendResult.setTraceOn(true);
                }
                sendResult.setRegionId(regionId);
                return sendResult;
            }
            default:
                break;
    }

    throw new MQBrokerException(response.getCode(), response.getRemark());
}

private void onExceptionImpl(final String brokerName,
    final Message msg,
    final long timeoutMillis,
    final RemotingCommand request,
    final SendCallback sendCallback,
    final TopicPublishInfo topicPublishInfo,
    final MQClientInstance instance,
    final int timesTotal,
    final AtomicInteger curTimes,
    final Exception e,
    final SendMessageContext context,
    final boolean needRetry,
    final DefaultMQProducerImpl producer
) {
    int tmp = curTimes.incrementAndGet();
    // 如果需要重发，执行重发机制
    if (needRetry && tmp <= timesTotal) {
        // 进行重发机制
        String retryBrokerName = brokerName;//by default, it will send to the same broker
        if (topicPublishInfo != null) { //select one message queue accordingly, in order to determine which broker to send
            MessageQueue mqChosen = producer.selectOneMessageQueue(topicPublishInfo, brokerName);
            retryBrokerName = mqChosen.getBrokerName();
        }
        String addr = instance.findBrokerAddressInPublish(retryBrokerName);
        log.info("async send msg by retry {} times. topic={}, brokerAddr={}, brokerName={}", tmp, msg.getTopic(), addr,
            retryBrokerName);
        try {
            request.setOpaque(RemotingCommand.createNewRequestId());
            sendMessageAsync(addr, retryBrokerName, msg, timeoutMillis, request, sendCallback, topicPublishInfo, instance,
                timesTotal, curTimes, context, producer);
        } catch (InterruptedException e1) {
            onExceptionImpl(retryBrokerName, msg, timeoutMillis, request, sendCallback, topicPublishInfo, instance, timesTotal, curTimes, e1,
                context, false, producer);
        } catch (RemotingConnectException e1) {
            producer.updateFaultItem(brokerName, 3000, true);
            onExceptionImpl(retryBrokerName, msg, timeoutMillis, request, sendCallback, topicPublishInfo, instance, timesTotal, curTimes, e1,
                context, true, producer);
        } catch (RemotingTooMuchRequestException e1) {
            onExceptionImpl(retryBrokerName, msg, timeoutMillis, request, sendCallback, topicPublishInfo, instance, timesTotal, curTimes, e1,
                context, false, producer);
        } catch (RemotingException e1) {
            producer.updateFaultItem(brokerName, 3000, true);
            onExceptionImpl(retryBrokerName, msg, timeoutMillis, request, sendCallback, topicPublishInfo, instance, timesTotal, curTimes, e1,
                context, true, producer);
        }
    } else {

        if (context != null) {
            context.setException(e);
            context.getProducer().executeSendMessageHookAfter(context);
        }

        try {
            // 执行错误回调
            sendCallback.onException(e);
        } catch (Exception ignored) {
        }
    }
}

```

