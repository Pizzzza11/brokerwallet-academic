package com.example.brokerfi.swap;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.brokerfi.token.TokenTxHistoryStore;
import com.example.brokerfi.token.TokenTxRecord;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Reuses the durable journal. Opening a row may query its hash, but never resends it. */
final class TradeHistory {
    private final AppCompatActivity activity;
    private final Button toggle;
    private final LinearLayout rows;
    private final boolean liquidity;
    private boolean expanded;
    TradeHistory(AppCompatActivity activity,int toggleId,int rowsId,boolean liquidity) {
        this.activity=activity; this.liquidity=liquidity;
        toggle=activity.findViewById(toggleId); rows=activity.findViewById(rowsId);
        toggle.setVisibility(View.VISIBLE);
    }
    void bind(BrokerSwapConfig config,String wallet,Runnable changed) {
        toggle.setOnClickListener(v -> { expanded=!expanded; refresh(config,wallet,changed); });
        refresh(config,wallet,changed);
    }
    private void refresh(BrokerSwapConfig config,String wallet,Runnable changed) {
        toggle.setText((liquidity?"LP History":"Swap History")+(expanded?" ▴":" ▾"));
        rows.setVisibility(expanded?View.VISIBLE:View.GONE); rows.removeAllViews();
        if (!expanded) return;
        int count=0;
        for (TokenTxRecord r:TokenTxHistoryStore.getAll(activity,wallet)) {
            if (r.status==null || r.isLiquidity()!=liquidity) continue;
            count++;
            TextView row=new TextView(activity); row.setTextSize(13); row.setTextColor(0xff334155);
            int pad=(int)(12*activity.getResources().getDisplayMetrics().density); row.setPadding(pad,pad,pad,pad);
            String time=new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(r.timestampMs));
            String amounts=r.isLiquidity() && !r.liquidityAction.startsWith("APPROVE")
                    ? (r.actualBkc==null?"BKC 待核实":r.actualBkc+" BKC")+" / "+(r.actualMusdt==null?"mUSDT 待核实":r.actualMusdt+" mUSDT")
                        +"\nLP："+(r.actualLp==null?"待核实":r.actualLp)
                    : r.amountDisplay+" "+r.fromSymbol;
            if(!r.isLiquidity() && !"APPROVAL".equals(r.type))amounts+=" → "+(r.actualOutput==null?"待核实":r.actualOutput)+" "+r.toSymbol;
            row.setText(time+" · "+SwapRecordPresentation.label(r)+"\n"+amounts+"\n"+SwapRecordPresentation.status(r.status));
            row.setOnClickListener(v -> showRecord(activity,config,wallet,r,changed)); rows.addView(row);
        }
        if (count==0) { TextView empty=new TextView(activity); empty.setText("暂无本机记录（不会伪造历史）"); rows.addView(empty); }
    }
    static void showRecord(AppCompatActivity activity,BrokerSwapConfig config,String wallet,TokenTxRecord r,Runnable changed) {
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(SwapRecordPresentation.label(r)+"详情")
                .setMessage(SwapRecordPresentation.details(r)).setNegativeButton("关闭",null);
        if (SwapReceipt.validHash(r.txHash)) dialog.setNeutralButton("复制交易编号",(d,w) -> {
            ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("交易编号",r.txHash));
        });
        if(TokenTxHistoryStore.isUnresolvedSwap(r) && SwapReceipt.validHash(r.txHash) && config!=null)
            dialog.setPositiveButton("查询结果（不重发）",(d,w) -> {
                SwapOperationRunner.resume(activity,config,wallet,r,record -> { if(!activity.isDestroyed())changed.run(); }); changed.run();
            });
        else if(TokenTxHistoryStore.isUnresolvedSwap(r) && !SwapOperationRunner.active(wallet))
            dialog.setPositiveButton("我已核查链上结果",(d,w) -> new AlertDialog.Builder(activity)
                    .setTitle("解除锁定可能导致重复交易")
                    .setMessage("没有交易编号不代表未发出。必须先核查链上记录、余额和授权。此操作只解除本机防重复锁，不会撤销或重发交易。确认已核实最终结果？")
                    .setNegativeButton("保留锁定",null).setPositiveButton("已核实，解除锁定",(d2,w2) -> {
                        r.status="ACKNOWLEDGED";
                        if(!TokenTxHistoryStore.upsertSwap(activity,wallet,r))Toast.makeText(activity,"保存失败，仍需核查",Toast.LENGTH_LONG).show();
                        changed.run();
                    }).show());
        dialog.show();
    }
}
