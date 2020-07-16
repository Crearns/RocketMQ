## Apache RocketMQ Studying note

This is my note for studying Apache RocketMQ source code analysis. I mainly study RocketMQ through source code.
The source code in the repository has comments when I learn RocketMQ

* RocketMQ-NameSrv
* RocketMQ-Remoting
* RocketMQ-Broker
* RocketMQ-Store
* RocketMQ-Client(Producer & Consumer)
* RocketMQ-Transaction
* RocketMQ-High Available

Extra Get:
* Usage of Netty
* Distribution transaction practice
* MappedByteBuffer and mmap system call
* Master/Slave synchronize practice
* Find a bug in RocketMQ-Remoting (Concurrent Problem when create the channel).But I am 2 months late and this bug has been fixed. See [[ISSUE #1108] Fix concurrent problems with client-side connection creation #1109](https://github.com/apache/rocketmq/pull/1109)

### 源码分析笔记
* [RocketMQ NameSrv源码分析总结](code%20analysis/RocketMQ%20NameSrv源码分析总结.md)
* [RocketMQ Broker启动流程源码分析总结](code%20analysis/RocketMQ%20Broker启动流程源码分析总结.md)
* [RocketMQ Broker刷盘分析总结](code%20analysis/RocketMQ%20Broker刷盘分析总结.md)
* [RocketMQ Broker的HA策略源码分析](code%20analysis/RocketMQ%20Broker的HA策略源码分析.md)
* [RocketMQ Producer发送消息源码分析总结](code%20analysis/RocketMQ%20Producer发送消息源码分析总结.md)
* [RocketMQ Producer启动流程源码分析总结](code%20analysis/RocketMQ%20Producer启动流程源码分析总结.md)
* [RocketMQ Consumer启动总结](code%20analysis/RocketMQ%20Consumer启动总结.md)
* [RocketMQ Consumer消费逻辑](code%20analysis/RocketMQ%20消费逻辑.md)
* [RocketMQ 事务消息源码总结](code%20analysis/RocketMQ%20事务消息源码总结.md)
* [RocketMQ 消息存储源码分析总结](code%20analysis/RocketMQ%20消息存储源码分析总结.md)


Need to go deeper:
* RocketMQ Store
* RocketMQ Broker
