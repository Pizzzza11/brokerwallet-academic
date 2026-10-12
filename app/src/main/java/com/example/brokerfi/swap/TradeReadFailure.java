package com.example.brokerfi.swap;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Allowlisted descriptions only: never exposes server bodies, signed requests or credentials. */
final class TradeReadFailure extends IOException {
    enum Kind { HTTP, REJECTED, ACCOUNT, INVALID }
    final Kind kind;
    TradeReadFailure(Kind kind) { super("Chain read " + kind.name()); this.kind=kind; }
    static final class Query extends IOException {
        final String getter;
        Query(String getter, IOException cause) { super("Metadata read failed",cause); this.getter=getter; }
    }
    static String describe(Throwable error) {
        for(Throwable e=error;e!=null;e=e.getCause()) {
            if(e instanceof Query) return ((Query)e).getter+" 查询："+describe(e.getCause());
            if(e instanceof InterruptedIOException) return "等待链上响应超时";
            if(e instanceof UnknownHostException || e instanceof ConnectException || e instanceof SSLException)
                return "网络连接失败";
            if(e instanceof TradeReadFailure) {
                switch(((TradeReadFailure)e).kind) {
                    case HTTP: return "链上服务暂不可用";
                    case ACCOUNT: return "查询账户未被网关识别";
                    case REJECTED: return "链上服务拒绝查询";
                    case INVALID: return "链上返回数据不完整";
                }
            }
        }
        return "校验未通过或查询异常";
    }
}
