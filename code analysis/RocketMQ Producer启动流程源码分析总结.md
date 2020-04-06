# RocketMQ-Producer 启动流程源码分析总结

## Producer
消息发布的角色，支持分布式集群方式部署。Producer通过MQ的负载均衡模块选择相应的Broker集群队列进行消息投递，投递的过程支持快速失败并且低延迟。

Producer与NameServer集群中的其中一个节点（随机选择）建立长连接，定期从NameServer获取Topic路由信息，并向提供Topic 服务的Master建立长连接，且定时向Master发送心跳。Producer完全无状态，可集群部署。

类定义
```java
public class DefaultMQProducer extends ClientConfig implements MQProducer
```
<img style="width: 50%" src="https://i.loli.net/2020/03/17/QyFLY7zAnciq82V.png" >

## 启动流程
```java
DefaultMQProducer producer = new DefaultMQProducer("ProducerGroupName");
producer.start();       
```

1. 构造 DefaultMQProducerImpl 对象
2. 执行 DefaultMQProducer#start() 方法

服务状态：ServiceState
```java
public enum ServiceState {
    /**
     * Service just created,not start
     */
    CREATE_JUST,
    /**
     * Service Running
     */
    RUNNING,
    /**
     * Service shutdown
     */
    SHUTDOWN_ALREADY,
    /**
     * Service Start failure
     */
    START_FAILED;
}

```

2.1 修改状态为 START_FAILED; 防止在其他状态下错误调用start

2.2 进行ProducerGroup命名检查，防止group与保留名重复

2.3  this.mQClientFactory = MQClientManager.getInstance().getAndCreateMQClientInstance(this.defaultMQProducer, rpcHook);

2.3.1 通过clientId获得MQClient的实例（如果没有则创建一个） MQClientInstance主要是实现客户端的实例、锁服务以及生产者消费者的缓存，和MQClientAPIImpl，而其中又有Netty的Client，处理器，以及API实现，创建完MQClientAPIImpl对象后，会根据clientConfig的getNamesrvAddr判断是否设置了namesrvAddr名称服务地址，若是设置了，需要通过mQClientAPIImpl的updateNameServerAddressList方法，完成对名称服务地址的更新

2.4 向topicPublishInfoTable中添加一条键值为createTopicKey（"TBW102"）的TopicPublishInfo记录 不知道有啥用

2.5 启动 MQClientInstance，具体过程在第三点介绍

2.6 修改状态为 RUNNING

2.7 给所有Broker发送一次心跳包


3.1 修改状态为 START_FAILED; 防止在其他状态下错误调用start

3.2 这里首先检查名称服务地址是否设置，若是没有设置，则通过MQClientAPIImpl的fetchNameServerAddr方法，尝试自动获取名称服务，主要是通过http请求获取

3.3 启动mQClientAPI，主要是启动NettyClient

3.4 接着是开始5个定时任务，
* ①若是名称服务地址namesrvAddr不存在，则调用前面的fetchNameServerAddr方法，定时更新namesrv
* ②通过updateTopicRouteInfoFromNameServer方法定时更新Topic所对应的路由信息 30s
* ③定时清除离线的Broker，以及向当前在线的Broker发送心跳包
* ④定时持久化消费者队列的消费进度
* ⑤定时调整消费者端的线程池的大小 todo consumer

3.5 开启pullMessageService服务，为消费者拉取消息 todo consumer

3.6 开启rebalanceService服务，用来均衡消息队列 todo consumer

3.7 启pushMessageService服务，为消费者拉取消息 todo consumer