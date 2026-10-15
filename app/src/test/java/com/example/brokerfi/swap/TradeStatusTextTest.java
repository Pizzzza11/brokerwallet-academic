package com.example.brokerfi.swap;

import com.example.brokerfi.token.TokenTxRecord;
import org.junit.Test;
import static org.junit.Assert.*;

public class TradeStatusTextTest {
    private TokenTxRecord unknown(){TokenTxRecord r=new TokenTxRecord();r.status="UNKNOWN";return r;}
    private TokenTxRecord pending(){TokenTxRecord r=unknown();r.status="PENDING";r.txHash="0x"+String.format("%064d",1);return r;}
    @Test public void preflightAlwaysSaysNotSent() {
        assertEquals("发送前检查中，尚未发送",TradeStatusText.operation(false,true,false,null));
    }
    @Test public void confirmingCannotBeMistakenForSending() {
        assertEquals("等待你确认，尚未发送",TradeStatusText.operation(true,false,false,null));
    }
    @Test public void sendingWithoutHashIsNotYetReportedAsSuccessOrFailure() {
        assertEquals("发送中，请勿重复操作",TradeStatusText.operation(false,false,true,unknown()));
    }
    @Test public void activeReceiptWaitIsDistinctFromSending() {
        assertEquals("等待链上确认，请勿重复发送",TradeStatusText.operation(false,false,true,pending()));
    }
    @Test public void inactiveValidHashOffersQueryNotResend() {
        assertTrue(TradeStatusText.operation(false,false,false,pending()).contains("查询原编号（不重发）"));
    }
    @Test public void inactiveHashlessUnknownOffersRecoveryNotFailure() {
        String text=TradeStatusText.operation(false,false,false,unknown());
        assertTrue(text.contains("结果未知"));assertTrue(text.contains("不会自动重发"));assertFalse(text.contains("失败"));
    }
    @Test public void idleHasNoOperationBanner() { assertEquals("",TradeStatusText.operation(false,false,false,null)); }
}
