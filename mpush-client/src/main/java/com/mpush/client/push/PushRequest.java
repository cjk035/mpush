/*
 * (C) Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *   ohun@live.cn (夜色)
 */

package com.mpush.client.push;

import com.mpush.api.connection.Connection;
import com.mpush.api.push.AckModel;
import com.mpush.api.push.PushCallback;
import com.mpush.api.push.PushSender;
import com.mpush.api.router.ClientLocation;
import com.mpush.common.message.gateway.GatewayPushMessage;
import com.mpush.common.router.ConnectionRouterManager;
import com.mpush.common.router.RemoteRouter;
import com.mpush.tools.common.TimeLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by ohun on 2015/12/30.
 *
 * @author ohun@live.cn
 */
public class PushRequest extends FutureTask<Boolean> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PushRequest.class);

    private static final Callable<Boolean> NONE = () -> Boolean.FALSE;

    private enum Status {init, success, failure, offline, timeout}

    private final AtomicReference<Status> status = new AtomicReference<>(Status.init);
    private final TimeLine timeLine = new TimeLine("Push-Time-Line");

    private final PushClient client;

    private AckModel ackModel;
    private PushCallback callback;
    private String userId;
    private byte[] content;
    private long timeout;
    private ClientLocation location;
    private Future<?> future;

    private void sendToConnServer(RemoteRouter router) {
        timeLine.addTimePoint("lookup-remote");

        if (router == null) {
            //1.1没有查到说明用户已经下线
            offline();
            return;
        }

        timeLine.addTimePoint("get-gateway-conn");


        //2.通过网关连接，把消息发送到所在机器
        location = router.getRouteValue();
        Connection gatewayConn = client.getGatewayConnection(location.getHost());
        if (gatewayConn == null) {
            LOGGER.error("get gateway connection failure, location={}", location);
            failure();
            return;
        }

        timeLine.addTimePoint("send-to-gateway-begin");

        GatewayPushMessage pushMessage = new GatewayPushMessage(userId, location.getClientType(), content, gatewayConn);
        pushMessage.getPacket().addFlag(ackModel.flag);

        timeLine.addTimePoint("put-request-bus");
        future = PushRequestBus.I.put(pushMessage.getSessionId(), this);

        pushMessage.sendRaw(f -> {
            timeLine.addTimePoint("send-to-gateway-end");
            if (f.isSuccess()) {
            } else {
                failure();
            }
        });
    }

    private void submit(Status status) {
        if (this.status.compareAndSet(Status.init, status)) {//防止重复调用
            if (future != null) future.cancel(true);
            if (callback != null) {
                PushRequestBus.I.asyncCall(this);
            } else {
                LOGGER.warn("callback is null");
            }
            super.set(this.status.get() == Status.success);
        }
        timeLine.end();
        LOGGER.info("push request {} end, userId={}, content={}, location={}, timeLine={}"
                , status, userId, content, location, timeLine);
    }

    @Override
    public void run() {
        switch (status.get()) {
            case success:
                callback.onSuccess(userId, location);
                break;
            case failure:
                callback.onFailure(userId, location);
                break;
            case offline:
                callback.onOffline(userId, location);
                break;
            case timeout:
                callback.onTimeout(userId, location);
                break;
            case init://从定时任务过来的，超时时间到了
                submit(Status.timeout);
                break;
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException();
    }

    public FutureTask<Boolean> send(RemoteRouter router) {
        timeLine.begin();
        sendToConnServer(router);
        return this;
    }

    public void redirect() {
        timeLine.addTimePoint("redirect");
        LOGGER.warn("user route has changed, userId={}, location={}", userId, location);
        ConnectionRouterManager.I.invalidateLocalCache(userId);
        if (status.get() == Status.init) {//表示任务还没有完成，还可以重新发送
            RemoteRouter route = ConnectionRouterManager.I.lookup(userId, location.getClientType());
            send(route);
        }
    }

    public FutureTask<Boolean> offline() {
        ConnectionRouterManager.I.invalidateLocalCache(userId);
        submit(Status.offline);
        return this;
    }

    public void timeout() {
        submit(Status.timeout);
    }

    public void success() {
        submit(Status.success);
    }

    public void failure() {
        submit(Status.failure);
    }

    public long getTimeout() {
        return timeout;
    }

    public PushRequest(PushClient client) {
        super(NONE);
        this.client = client;
    }

    public static PushRequest build(PushClient client) {
        return new PushRequest(client);
    }

    public PushRequest setCallback(PushCallback callback) {
        this.callback = callback;
        return this;
    }

    public PushRequest setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public PushRequest setContent(byte[] content) {
        this.content = content;
        return this;
    }

    public PushRequest setTimeout(long timeout) {
        this.timeout = timeout;
        return this;
    }

    public PushRequest setAckModel(AckModel ackModel) {
        this.ackModel = ackModel;
        return this;
    }

    @Override
    public String toString() {
        return "PushRequest{" +
                "content='" + content + '\'' +
                ", userId='" + userId + '\'' +
                ", timeout=" + timeout +
                ", location=" + location +
                '}';
    }
}
