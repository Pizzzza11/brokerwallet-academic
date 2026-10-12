package com.example.brokerfi.swap;

import org.junit.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import static org.junit.Assert.*;

public class TradeReadFailureTest {
    @Test public void timeoutIsNotPresentedAsConfigurationMismatch() {
        assertEquals("等待链上响应超时",TradeReadFailure.describe(new SocketTimeoutException("secret request")));
        assertEquals("factory 查询：等待链上响应超时",TradeReadFailure.describe(
                new TradeReadFailure.Query("factory",new SocketTimeoutException("secret"))));
    }
    @Test public void networkErrorDoesNotRevealHostnameOrBody() {
        assertEquals("网络连接失败",TradeReadFailure.describe(new UnknownHostException("sensitive hostname")));
        assertEquals("校验未通过或查询异常",TradeReadFailure.describe(new IOException("sensitive body")));
    }
    @Test public void serviceAccountAndMalformedDataHaveDifferentDescriptions() {
        assertEquals("链上服务暂不可用",TradeReadFailure.describe(new TradeReadFailure(TradeReadFailure.Kind.HTTP)));
        assertEquals("链上服务拒绝查询",TradeReadFailure.describe(new TradeReadFailure(TradeReadFailure.Kind.REJECTED)));
        assertEquals("查询账户未被网关识别",TradeReadFailure.describe(new TradeReadFailure(TradeReadFailure.Kind.ACCOUNT)));
        assertEquals("链上返回数据不完整",TradeReadFailure.describe(new TradeReadFailure(TradeReadFailure.Kind.INVALID)));
    }
    @Test public void longerWaitIsBoundedAndRestrictedToSignedReads() {
        assertEquals(45,BrokerSwapClient.SIGNED_READ_TIMEOUT_SECONDS);
        assertEquals(60,BrokerSwapClient.SIGNED_READ_CALL_TIMEOUT_SECONDS);
    }
    @Test public void transactionTransportRetainsOriginalBoundsAndDoesNotAutomaticallyRetry() throws Exception {
        BrokerSwapClient client=new BrokerSwapClient(null);
        java.lang.reflect.Field writes=BrokerSwapClient.class.getDeclaredField("http");writes.setAccessible(true);
        java.lang.reflect.Field reads=BrokerSwapClient.class.getDeclaredField("signedReads");reads.setAccessible(true);
        okhttp3.OkHttpClient original=(okhttp3.OkHttpClient)writes.get(client), signed=(okhttp3.OkHttpClient)reads.get(client);
        assertEquals(25000,original.readTimeoutMillis());assertEquals(30000,original.callTimeoutMillis());
        assertFalse(original.retryOnConnectionFailure());assertFalse(signed.retryOnConnectionFailure());
        assertEquals(45000,signed.readTimeoutMillis());assertEquals(60000,signed.callTimeoutMillis());
    }
}
