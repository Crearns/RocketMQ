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

Extra harvest:
* Using Netty and principle
* Distribution Transaction Practice
* MappedByteBuffer and mmap system call
* Master/Slave Synchronize Practice
* Find a bug in RocketMQ-Remoting (Concurrent Problem when create the channel).But I am 2 months late and this bug has been fixed. See [[ISSUE #1108] Fix concurrent problems with client-side connection creation #1109](https://github.com/apache/rocketmq/pull/1109)


Need to go deeper:
* RocketMQ Store
* RocketMQ Broker