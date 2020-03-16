package org.apache.rocketmq.remoting.netty;

import io.netty.channel.ChannelHandlerContext;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.junit.Test;

import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class NettyRemotingServerTest {
    @Test
    public void server() {
        NettyServerConfig config = new NettyServerConfig();
        config.setListenPort(8899);
        NettyRemotingServer server = new NettyRemotingServer(config);

        server.registerDefaultProcessor(new NettyRequestProcessor() {
            @Override
            public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request) throws Exception {
                System.out.println("收到");
                return null;
            }

            @Override
            public boolean rejectRequest() {
                return false;
            }
        }, Executors.newSingleThreadExecutor());

        server.start();
    }

}