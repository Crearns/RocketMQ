package org.apache.rocketmq.remoting;

import io.netty.channel.ChannelHandlerContext;
import org.apache.rocketmq.logging.inner.Logger;
import org.apache.rocketmq.remoting.exception.RemotingConnectException;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.netty.*;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Created By Creams on 2020/04/01
 */
public class NettyConcurrencyTest {
    NettyRemotingClient client;
    NettyRemotingServer server;

    @Before
    public void setUp() {
        NettyServerConfig config = new NettyServerConfig();
        config.setListenPort(8899);
        server = new NettyRemotingServer(config);
        server.registerDefaultProcessor(new NettyRequestProcessor() {
            @Override
            public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws Exception {
                System.out.println(request.toString());
                return RemotingCommand.createResponseCommand(1, null);
            }

            @Override
            public boolean rejectRequest() {
                return false;
            }
        }, Executors.newFixedThreadPool(2));

        client = new NettyRemotingClient(new NettyClientConfig());

        server.start();
        client.start();
    }

    @Test
    public void testConcurrency() throws InterruptedException {
        final RemotingCommand request1 = RemotingCommand.createRequestCommand(1, null);
        final RemotingCommand request2 = RemotingCommand.createRequestCommand(2, null);
        final CountDownLatch latch = new CountDownLatch(2);
        Runnable runnable1 = new Runnable() {
            @Override
            public void run() {
                try {
                    client.invokeSync("127.0.0.1:8899", request1, 5000000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (RemotingConnectException e) {
                    e.printStackTrace();
                } catch (RemotingSendRequestException e) {
                    e.printStackTrace();
                } catch (RemotingTimeoutException e) {
                    e.printStackTrace();
                }
                latch.countDown();

            }
        };

        Runnable runnable2 = new Runnable() {
            @Override
            public void run() {
                try {
                    client.invokeSync("127.0.0.1:8899", request2, 5000000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (RemotingConnectException e) {
                    e.printStackTrace();
                } catch (RemotingSendRequestException e) {
                    e.printStackTrace();
                } catch (RemotingTimeoutException e) {
                    e.printStackTrace();
                }
                latch.countDown();

            }
        };

        Thread thread1 = new Thread(runnable1, "NettyClientTestThread-1");
        Thread thread2 = new Thread(runnable2, "NettyClientTestThread-2");


        thread1.start();
        thread2.start();
        latch.await();

    }
}
