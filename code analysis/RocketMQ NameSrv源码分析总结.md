# RocketMQ-NameSrv源码分析总结

RocketMQ 架构图如下：

![rocketmq_architecture_1.png](https://i.loli.net/2020/03/16/DhIst4HfEReu8Fn.jpg)

## RocketMQ Namesrv简介：

NameServer：NameServer是一个非常简单的Topic路由注册中心，其角色类似Dubbo中的zookeeper，支持Broker的动态注册与发现。主要包括两个功能：Broker管理，NameServer接受Broker集群的注册信息并且保存下来作为路由信息的基本数据。然后提供心跳检测机制，检查Broker是否还存活；路由信息管理，每个NameServer将保存关于Broker集群的整个路由信息和用于客户端查询的队列信息。然后Producer和Conumser通过NameServer就可以知道整个Broker集群的路由信息，从而进行消息的投递和消费。**NameServer通常也是集群的方式部署，各实例间相互不进行信息通讯。Broker是向每一台NameServer注册自己的路由信息，所以每一个NameServer实例上面都保存一份完整的路由信息。当某个NameServer因某种原因下线了，Broker仍然可以向其它NameServer同步其路由信息，Producer,Consumer仍然可以动态感知Broker的路由的信息。**

NameServer是一个几乎无状态节点，可集群部署，节点之间无任何信息同步。

## namesrv的主要功能
1. 每个Broker启动的时候会向Namesrv发送注册请求，Namesrv接收Broker的请求注册路由信息，NameServer保存活跃的broker列表，包括Master和Slave；

2. 用来保存所有topic和该topic所有队列的列表；

3. NameServer用来保存所有broker的Filter列表

4. 接收client（Producer和Consumer）的请求根据某个topic获取所有到broker的路由信息；

## NameServer的初始化及启动过程
1. KVConfigManager类加载NameServer的配置参数，配置参数的路径是 $HOME /namesrv/kvConfig.json;将配置参数加载保存到KVConfigManager.configTable:HashMap&lt;String,HashMap&lt;String,String&gt;&gt;变量中。

2. 以初始化BrokerHousekeepingService对象为参数初始化NettyRemotingServer对象，BrokerHousekeepingService对象作为该Netty连接中Socket链接的监听器（ChannelEventListener）；监听与Broker建立的渠道的状态（空闲、关闭、异常三个状态），并调用BrokerHousekeepingService的相应onChannelDestroy方法。其中渠道的空闲、关闭、异常状态均调用RouteInfoManager.onChannelDestory方法处理。

3. 注册默认的处理类DefaultRequestProcessor,所有的请求均由该处理类的processRequest方法来处理。

4. 设置两个定时任务：

    第一是每隔10秒检查一遍所有Broker的状态的定时任务，调用scanNotActiveBroker方法；大致逻辑是：遍历brokerLiveTable集合，查看每个broker的最后更新时间（BrokerLiveInfo.lastUpdateTimestamp）是否超过2分钟，若超过则关闭该broker的渠道并调用RouteInfoManager.onChannelDestory方法清理RouteInfoManager类的topicQueueTable、brokerAddrTable、clusterAddrTable、filterServerTable成员变量。

    第二是每隔10分钟打印一次NameServer的配置参数。即KVConfigManager.configTable变量的内容。

5. 启动NameServer的Netty服务端（NettyRemotingServer），监听渠道的请求信息。当收到客户端的请求信息之后会初始化一个线程，并放入线程池中进行处理,该线程调用DefaultRequestProcessor. processRequest方法来处理请求。



## Namesrv主要保存的信息

```java
// topic 和 broker的Map，保存了topic在每个broker上的读写Queue的个数以及读写权限
private final HashMap<String/* topic */, List<QueueData>> topicQueueTable;
// 注册到nameserv上的所有broker，按照brokername分组
private final HashMap<String/* brokerName */, BrokerData> brokerAddrTable;
// 集群 与 Broker的对应关系
private final HashMap<String/* clusterName */, Set<String/* brokerName */>> clusterAddrTable;
// broker最新的心跳时间以及配置版本号
private final HashMap<String/* brokerAddr */, BrokerLiveInfo> brokerLiveTable;
// broker和FilterServer的对应关系
private final HashMap<String/* brokerAddr */, List<String>/* Filter Server */> filterServerTable;
```

![1.png](https://i.loli.net/2020/03/17/TxStka1AVeUY35j.png)

![2.png](https://i.loli.net/2020/03/17/aDFjgkrBoOSRywN.png)

## Namesrv 接收的RequestCode

```java
switch (request.getCode()) {
    // 追加配置
    case RequestCode.PUT_KV_CONFIG:
        return this.putKVConfig(ctx, request);
    // 获得配置
    case RequestCode.GET_KV_CONFIG:
        return this.getKVConfig(ctx, request);
    // 删除配置
    case RequestCode.DELETE_KV_CONFIG:
        return this.deleteKVConfig(ctx, request);
    // 查询版本号
    case RequestCode.QUERY_DATA_VERSION:
        return queryBrokerTopicConfig(ctx, request);
    // 注册Broker 数据都是持久化的，如果存在则覆盖配置
    case RequestCode.REGISTER_BROKER:
        Version brokerVersion = MQVersion.value2Version(request.getVersion());
        if (brokerVersion.ordinal() >= MQVersion.Version.V3_0_11.ordinal()) {
            return this.registerBrokerWithFilterServer(ctx, request);
        } else {
            return this.registerBroker(ctx, request);
        }
    // 卸载Broker
    case RequestCode.UNREGISTER_BROKER:
        return this.unregisterBroker(ctx, request);
    // 根据Topic获得路由信息
    case RequestCode.GET_ROUTEINTO_BY_TOPIC:
        return this.getRouteInfoByTopic(ctx, request);
    // 获得Broker集群信息
    case RequestCode.GET_BROKER_CLUSTER_INFO:
        return this.getBrokerClusterInfo(ctx, request);
    // 写权限
    case RequestCode.WIPE_WRITE_PERM_OF_BROKER:
        return this.wipeWritePermOfBroker(ctx, request);
    // 获得Namesrv所有tipic
    case RequestCode.GET_ALL_TOPIC_LIST_FROM_NAMESERVER:
        return getAllTopicListFromNameserver(ctx, request);
    // 删除Namesrv中的某个topic
    case RequestCode.DELETE_TOPIC_IN_NAMESRV:
        return deleteTopicInNamesrv(ctx, request);
    // 通过namespace获得kv列表
    case RequestCode.GET_KVLIST_BY_NAMESPACE:
        return this.getKVListByNamespace(ctx, request);
    // 获得指定集群的topic
    case RequestCode.GET_TOPICS_BY_CLUSTER:
        return this.getTopicsByCluster(ctx, request);
    // 
    case RequestCode.GET_SYSTEM_TOPIC_LIST_FROM_NS:
        return this.getSystemTopicListFromNs(ctx, request);
    // 获得unit topic
    case RequestCode.GET_UNIT_TOPIC_LIST:
        return this.getUnitTopicList(ctx, request);
    // 
    case RequestCode.GET_HAS_UNIT_SUB_TOPIC_LIST:
        return this.getHasUnitSubTopicList(ctx, request);
    // 
    case RequestCode.GET_HAS_UNIT_SUB_UNUNIT_TOPIC_LIST:
        return this.getHasUnitSubUnUnitTopicList(ctx, request);
    // 更新Namesrv配置
    case RequestCode.UPDATE_NAMESRV_CONFIG:
        return this.updateConfig(ctx, request);
    // 获得Namesrv配置
    case RequestCode.GET_NAMESRV_CONFIG:
        return this.getConfig(ctx, request);
    default:
        break;
}
```