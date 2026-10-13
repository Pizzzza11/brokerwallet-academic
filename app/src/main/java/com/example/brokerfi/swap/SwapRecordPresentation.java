package com.example.brokerfi.swap;

import com.example.brokerfi.token.TokenTxRecord;

/** Estimated amounts and chain-confirmed amounts are deliberately separate. */
public final class SwapRecordPresentation {
    private SwapRecordPresentation() {}
    public static String status(String state) {
        switch (state) {
            case "SUBMITTING": return "发送中／待核查";
            case "UNKNOWN": return "发送结果未知，请勿重复";
            case "PENDING": return "等待链上确认";
            case "SUCCESS": return "链上成功";
            case "FAILED": return "链上失败";
            case "NOT_SENT": return "未发送";
            default: return "已人工核查";
        }
    }
    public static String details(TokenTxRecord r) {
        if (r.isLiquidity()) return liquidityDetails(r)+SwapSendDiagnostics.details(r);
        boolean approval = "APPROVAL".equals(r.type);
        return status(r.status) + "\n" + (approval ? "授权限额：" : "支付：") + r.amountDisplay + " " + r.fromSymbol
                + (approval ? "\n授权对象：" + r.routerAddress : "\n预计到账：" + r.estimatedOutput + " " + r.toSymbol
                + "\n最低到账：" + r.minimumOutput + " " + r.toSymbol
                + "\n实际到账：" + (r.actualOutput == null ? "待核实（不使用预计值代替）" : r.actualOutput + " " + r.toSymbol)
                + "\n滑点：" + r.slippageBps / 100.0 + "%　价格影响：" + r.impactBps / 100.0 + "%")
                + "\n网络：" + r.chainId + "\nRouter：" + r.routerAddress
                + "\n支付资产合约：" + (r.contractAddress == null || r.contractAddress.isEmpty() ? "原生 BKC" : r.contractAddress)
                + (approval ? "" : "\n到账资产合约：" + (r.toContractAddress == null || r.toContractAddress.isEmpty() ? "原生 BKC" : r.toContractAddress))
                + "\n手续费：" + (r.gasCost == null ? "待核实" : r.gasCost + " BKC")
                + "\n交易编号：" + (r.txHash == null ? "尚未取得，请先核查链上结果" : r.txHash)
                + SwapSendDiagnostics.details(r);
    }
    public static String label(TokenTxRecord r) {
        if (r.isLiquidity()) return r.liquidityAction.startsWith("APPROVE") ? "流动性授权" : "ADD".equals(r.liquidityAction) ? "添加流动性" : "撤出流动性";
        return "APPROVAL".equals(r.type) ? "授权" : "兑换";
    }
    private static String liquidityDetails(TokenTxRecord r) {
        String amounts=r.liquidityAction.startsWith("APPROVE") ? "授权限额："+r.amountDisplay+" "+r.fromSymbol
                : "输入："+r.amountDisplay+"\n预计 LP："+(r.estimatedLp==null?"—":r.estimatedLp)
                +"\n实际 LP："+(r.actualLp==null?"待核实":r.actualLp)
                +"\n实际 BKC："+(r.actualBkc==null?"待核实":r.actualBkc)
                +"\n实际 mUSDT："+(r.actualMusdt==null?"待核实":r.actualMusdt)
                +"\n最低 BKC："+units(r.minimumBkcUnits,18)+"\n最低 mUSDT："+units(r.minimumMusdtUnits,6)
                + ("ADD".equals(r.liquidityAction)?"\n最低 LP："+units(r.minimumLpUnits,18):"");
        return label(r)+" · "+status(r.status)+"\n"+amounts+"\n网络："+r.chainId+"\nRouter："+r.routerAddress
                +"\n手续费："+(r.gasCost==null?"待核实":r.gasCost+" BKC")
                +"\n交易编号："+(r.txHash==null?"尚未取得，请先核查链上结果":r.txHash);
    }
    private static String units(String value,int decimals) {
        return value==null ? "—" : SwapQuoteMath.format(new java.math.BigInteger(value),decimals);
    }
}
