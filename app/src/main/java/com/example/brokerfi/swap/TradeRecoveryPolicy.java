package com.example.brokerfi.swap;

import com.example.brokerfi.token.TokenTxRecord;

/** Separate unknown outcome from a user's explicit decision to start an independent operation. */
public final class TradeRecoveryPolicy {
    private TradeRecoveryPolicy() {}
    public static boolean unresolved(TokenTxRecord r) {
        return r!=null && ("UNKNOWN".equals(r.status)||"SUBMITTING".equals(r.status)||"PENDING".equals(r.status));
    }
    public static boolean canAcceptRisk(TokenTxRecord r,boolean active) {
        return !active && r!=null && ("UNKNOWN".equals(r.status)||"SUBMITTING".equals(r.status))
                && !SwapReceipt.validHash(r.txHash);
    }
    public static boolean deferred(TokenTxRecord r) {
        return canAcceptRisk(r,false) && r.continuationRiskAcceptedMs>0;
    }
    public static boolean blocks(TokenTxRecord r) { return unresolved(r) && !deferred(r); }
    public static boolean acceptRisk(TokenTxRecord r,boolean active,long nowMs) {
        if(!canAcceptRisk(r,active)||nowMs<=0)return false;
        r.continuationRiskAcceptedMs=nowMs;
        return true;
    }
    static String details(TokenTxRecord r) {
        return deferred(r)?"\n旧结果仍待核查；已接受迟到执行／额外手续费风险，允许独立新交易（未取消、未重发旧请求）":"";
    }
}
