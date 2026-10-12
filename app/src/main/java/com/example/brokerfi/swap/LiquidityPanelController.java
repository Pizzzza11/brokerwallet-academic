package com.example.brokerfi.swap;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.brokerfi.R;
import com.example.brokerfi.token.TokenTxHistoryStore;
import com.example.brokerfi.token.TokenTxRecord;
import com.example.brokerfi.token.TokenWalletHelper;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

/** Wallet UI adapter for Du's existing atomic LP router. Does not deploy or alter contracts. */
final class LiquidityPanelController {
    interface Busy { boolean get(); }
    private final AppCompatActivity activity;
    private final BrokerSwapConfig config;
    private final BrokerSwapClient client;
    private final Busy otherBusy;
    private final Runnable parentChanged;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final EditText bkc,musdt;
    private final TextView status,balanceBkc,balanceMusdt,addQuote,owned,position,fees,removeQuote,percentLabel;
    private final Button approveAdd,add,approveRemove,remove;
    private final SeekBar slider;
    private final TradeHistory history;
    private String wallet="",error="";
    private Snapshot snapshot;
    private long validatedAt;
    private volatile int generation;
    private int percent,readProgress;
    private volatile boolean visible,resumed,preparing;
    private boolean loading,confirming,updating,lastEditedBkc=true;
    private final Runnable tick=new Runnable() { public void run() {
        if(!resumed) return;
        if(visible) { render(); if(!loading && !busy() && error.isEmpty() && !SwapOperationRunner.active(wallet))fetch(); }
        main.postDelayed(this,15000);
    }};
    private static final class Snapshot {
        final SwapPoolSnapshot pool;
        final BigInteger supply,nativeBalance,musdtBalance,lpBalance,musdtAllowance,lpAllowance,gas;
        Snapshot(SwapPoolSnapshot pool,BigInteger supply,BigInteger n,BigInteger m,BigInteger lp,BigInteger ma,BigInteger la,BigInteger gas) {
            if(supply.signum()<=0 || lp.compareTo(supply)>0)throw new IllegalArgumentException("Invalid LP supply");
            this.pool=pool; this.supply=supply; nativeBalance=n; musdtBalance=m; lpBalance=lp; musdtAllowance=ma; lpAllowance=la; this.gas=gas;
        }
        boolean fresh() { return pool.isFresh(SystemClock.elapsedRealtime()); }
    }
    LiquidityPanelController(AppCompatActivity activity,BrokerSwapConfig config,Busy otherBusy,Runnable parentChanged) {
        this.activity=activity; this.config=config; this.otherBusy=otherBusy; this.parentChanged=parentChanged;
        client=config==null?null:new BrokerSwapClient(config);
        bkc=activity.findViewById(R.id.lp_bkc_amount); musdt=activity.findViewById(R.id.lp_musdt_amount);
        status=activity.findViewById(R.id.lp_status); balanceBkc=activity.findViewById(R.id.lp_bkc_balance);
        balanceMusdt=activity.findViewById(R.id.lp_musdt_balance); addQuote=activity.findViewById(R.id.lp_add_quote);
        owned=activity.findViewById(R.id.lp_owned); position=activity.findViewById(R.id.lp_position); fees=activity.findViewById(R.id.lp_fees);
        removeQuote=activity.findViewById(R.id.lp_remove_quote); percentLabel=activity.findViewById(R.id.lp_remove_percent);
        approveAdd=activity.findViewById(R.id.lp_approve_add); add=activity.findViewById(R.id.lp_add);
        approveRemove=activity.findViewById(R.id.lp_approve_remove); remove=activity.findViewById(R.id.lp_remove);
        slider=activity.findViewById(R.id.lp_remove_slider);
        history=new TradeHistory(activity,R.id.lp_history_toggle,R.id.lp_history_rows,true);
        bkc.addTextChangedListener(watcher(true)); musdt.addTextChangedListener(watcher(false));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int value,boolean user) { percent=value; render(); }
            public void onStartTrackingTouch(SeekBar bar) {} public void onStopTrackingTouch(SeekBar bar) {}
        });
        int[] ids={R.id.lp_percent_25,R.id.lp_percent_50,R.id.lp_percent_75,R.id.lp_percent_100};
        for(int i=0;i<ids.length;i++) { final int value=(i+1)*25; activity.findViewById(ids[i]).setOnClickListener(v -> { if(!locked())slider.setProgress(value); }); }
        approveAdd.setOnClickListener(v -> confirm(true,true)); add.setOnClickListener(v -> confirm(true,false));
        approveRemove.setOnClickListener(v -> confirm(false,true)); remove.setOnClickListener(v -> confirm(false,false));
        status.setOnClickListener(v -> { TokenTxRecord pending=unresolved(); if(pending!=null)TradeHistory.showRecord(activity,config,wallet,pending,this::afterHistory); });
        render();
    }
    private TextWatcher watcher(boolean fromBkc) { return new TextWatcher() {
        public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
        public void onTextChanged(CharSequence s,int start,int before,int count) {
            if(updating)return; lastEditedBkc=fromBkc; matchOther(); render();
        }
        public void afterTextChanged(Editable text) {}
    }; }
    private void matchOther() {
        if(snapshot==null || !snapshot.fresh())return;
        EditText input=lastEditedBkc?bkc:musdt, output=lastEditedBkc?musdt:bkc;
        try {
            BigInteger units=SwapQuoteMath.parseUnits(input.getText().toString(),lastEditedBkc?18:6);
            BigInteger other=LiquidityMath.counterpart(snapshot.pool,units,lastEditedBkc);
            updating=true; output.setText(SwapQuoteMath.format(other,lastEditedBkc?6:18));
        } catch(Exception ignored) { updating=true; output.setText(""); }
        finally { updating=false; }
    }
    boolean busy() { return confirming || preparing; }
    private boolean locked() { return busy() || otherBusy.get() || SwapOperationRunner.active(wallet); }
    void setVisible(boolean value) { if(value!=visible)generation++; visible=value; if(value) { render(); fetch(); } }
    void onResume(String account) {
        if(!wallet.equalsIgnoreCase(account)) {
            wallet=account; generation++; validatedAt=0; snapshot=null; error="";
            updating=true; bkc.setText(""); musdt.setText(""); updating=false; slider.setProgress(0);
        }
        resumed=true; main.removeCallbacks(tick); main.postDelayed(tick,15000);
        if(visible)fetch(); render();
    }
    void onPause() { resumed=false; generation++; main.removeCallbacks(tick); }
    void onDestroy() { resumed=false; generation++; main.removeCallbacks(tick); }
    private boolean sameAccount(String address) { return address.equalsIgnoreCase(BrokerSwapConfig.walletId(TokenWalletHelper.getWalletAddress(activity))); }
    private TokenTxRecord unresolved() {
        for(TokenTxRecord record:TokenTxHistoryStore.getAll(activity,wallet))if(TokenTxHistoryStore.isUnresolvedSwap(record))return record;
        return null;
    }
    void fetch() {
        if(!resumed || !visible || loading || busy() || config==null || !config.hasLiquidity() || wallet.isEmpty()) { parentChanged.run(); return; }
        loading=true; readProgress=0; error=""; render();
        final int id=generation; final String account=wallet;
        final boolean validate=validatedAt==0 || SystemClock.elapsedRealtime()-validatedAt>600000;
        TradeReadSession session=new TradeReadSession(
                () -> resumed && visible && !preparing && id==generation && sameAccount(account),
                number -> main.post(() -> {
                    if(resumed && visible && id==generation && sameAccount(account)) {
                        readProgress=number; render(); parentChanged.run();
                    }
                }));
        BrokerSwapClient readClient=new BrokerSwapClient(config,session);
        TradeReadQueue.execute(() -> {
            String stage="账户";
            try {
                session.check();
                if(!sameAccount(account))throw new IllegalStateException("Account changed");
                String key=TokenWalletHelper.getCurrentPrivateKey(activity.getApplicationContext());
                if(validate) {
                    stage="流动性合约校验"; readClient.validateLiquidity(key);
                    long checked=SystemClock.elapsedRealtime();
                    main.post(() -> { if(!activity.isDestroyed() && resumed && visible && id==generation && sameAccount(account))validatedAt=checked; });
                }
                stage="余额、LP 和授权额度";
                Snapshot read=readSnapshot(readClient,key,account);
                main.post(() -> {
                    loading=false;
                    if(activity.isDestroyed())return;
                    if(!resumed || !visible || id!=generation || !sameAccount(account)) { fetch(); return; }
                    snapshot=read; error=""; matchOther(); render(); parentChanged.run();
                });
            } catch(Exception e) {
                final String failed=stage, reason=TradeReadFailure.describe(e);
                final boolean cancelled=e instanceof TradeReadSession.Cancelled;
                main.post(() -> {
                    loading=false;
                    if(activity.isDestroyed())return;
                    if(cancelled || !resumed || !visible || id!=generation || !sameAccount(account)) { fetch(); return; }
                    snapshot=null; error=failed+"："+reason+"。请下拉刷新；不会使用旧报价交易。"; render();
                    parentChanged.run();
                });
            }
        });
    }
    boolean loading() { return loading; }
    private Snapshot readSnapshot(String key,String address) throws Exception {
        return readSnapshot(client,key,address);
    }
    private Snapshot readSnapshot(BrokerSwapClient client,String key,String address) throws Exception {
        BigInteger n=client.balance(key,address,""), m=client.balance(key,address,config.musdt());
        BigInteger lp=client.balance(key,address,config.pair()), supply=client.totalSupply(key);
        BigInteger ma=client.allowance(key,address,config.musdt(),config.liquidityRouter());
        BigInteger la=client.allowance(key,address,config.pair(),config.liquidityRouter()), gas=client.gasReserve();
        // Reserve quote LAST: signed gateway reads can be slow. Every action still does fresh preflight.
        return new Snapshot(client.pool(key),supply,n,m,lp,ma,la,gas);
    }
    private String baseProblem(Snapshot s) {
        if(config==null || !config.hasLiquidity())return "流动性部署未就绪";
        if(wallet.isEmpty())return "请先登录钱包";
        if(!sameAccount(wallet))return "账户已切换，请重新进入";
        if(unresolved()!=null)return "有未确认交易，点击上方提示核查";
        if(s==null)return loading?"正在查询链上数据…":"请下拉刷新链上数据";
        if(!s.fresh())return "报价已过期，请下拉刷新";
        return "";
    }
    private String addProblem(Snapshot s,BigInteger desiredM,BigInteger desiredB) {
        String problem=baseProblem(s); if(!problem.isEmpty())return problem;
        if(s.musdtBalance.compareTo(desiredM)<0)return "mUSDT 余额不足";
        BigInteger reserve=LiquiditySafety.reserve(s.gas,s.musdtAllowance.compareTo(desiredM)<0);
        if(s.nativeBalance.compareTo(desiredB.add(reserve))<0)return "BKC 金额加手续费不足";
        return "";
    }
    private String removeProblem(Snapshot s,BigInteger lp) {
        String problem=baseProblem(s); if(!problem.isEmpty())return problem;
        if(lp.signum()<=0)return "请选择撤出比例";
        if(s.lpBalance.compareTo(lp)<0)return "LP 余额不足";
        BigInteger reserve=LiquiditySafety.reserve(s.gas,s.lpAllowance.compareTo(lp)<0);
        if(s.nativeBalance.compareTo(reserve)<0)return "BKC 手续费不足";
        return "";
    }
    private void render() {
        Snapshot s=snapshot; boolean lock=locked(); TokenTxRecord pending=unresolved();
        String state=pending!=null?"有未确认交易："+SwapRecordPresentation.status(pending.status)+"（点击核查，不重发）"
                : busy()?"发送前重新检查…":SwapOperationRunner.active(wallet)?"交易处理中，请勿重复操作"
                : loading?loadingText():!error.isEmpty()?error:s==null?"下拉刷新读取链上持仓":"网络 1051 · 允许到账浮动 0.5%";
        status.setText("mUSDT 是测试资产。\n"+state+(pending!=null?loading?"\n"+loadingText():!error.isEmpty()?"\n"+error:"":"")); status.setTextColor(pending!=null || !error.isEmpty()?0xff991B1B:0xff475569);
        bkc.setEnabled(!lock); musdt.setEnabled(!lock); slider.setEnabled(!lock);
        for(int id:new int[]{R.id.lp_percent_25,R.id.lp_percent_50,R.id.lp_percent_75,R.id.lp_percent_100})activity.findViewById(id).setEnabled(!lock);
        balanceBkc.setText("余额："+(s==null?"—":TradeDisplay.amount(s.nativeBalance,18))+" BKC");
        balanceMusdt.setText("余额："+(s==null?"—":TradeDisplay.amount(s.musdtBalance,6))+" mUSDT");
        owned.setText("Your LP tokens："+(s==null?"—":TradeDisplay.amount(s.lpBalance,18)));
        percentLabel.setText("撤出比例："+percent+"%");
        String base=baseProblem(s); String idle=!base.isEmpty()?base:lock?"处理中，请勿重复操作":"请输入两项金额";
        boolean blocked=pending!=null || !error.isEmpty() || s!=null && !s.fresh();
        TradeButtons.state(approveAdd,"Approve mUSDT · "+idle,false,blocked); TradeButtons.state(add,"Add Liquidity · "+idle,false,blocked);
        addQuote.setText("输入任一金额，另一项按池内比例联动。滑点 0.5%，多余金额由合约退回。");
        try {
            BigInteger desiredM=SwapQuoteMath.parseUnits(musdt.getText().toString(),6), desiredB=SwapQuoteMath.parseUnits(bkc.getText().toString(),18);
            if(s!=null && s.fresh()) {
                LiquidityMath.AddQuote q=LiquidityMath.add(s.pool,s.supply,desiredM,desiredB);
                addQuote.setText("预计使用："+TradeDisplay.amount(q.bkc,18)+" BKC + "+TradeDisplay.amount(q.musdt,6)+" mUSDT"
                        +"\nEstimated LP："+TradeDisplay.amount(q.lp,18)+"\nEstimated pool share："+share(q.lp,s.supply.add(q.lp))+"%");
                String problem=addProblem(s,desiredM,desiredB); boolean approved=s.musdtAllowance.compareTo(desiredM)>=0;
                boolean valid=problem.isEmpty() && !lock;
                TradeButtons.state(approveAdd,approved?"mUSDT 已授权":problem.isEmpty()?"Approve mUSDT（仅本次金额）":problem,valid && !approved,blocked || !problem.isEmpty());
                TradeButtons.state(add,!problem.isEmpty()?problem:lock?"处理中，请勿重复操作":approved?"Add Liquidity":"Add Liquidity · 请先授权 mUSDT",valid && approved,blocked || !problem.isEmpty());
            }
        } catch(Exception ignored) { if(bkc.length()>0 && musdt.length()>0)addQuote.setText("金额精度不符或金额太小，无法添加流动性。"); }
        String removeIdle=!base.isEmpty()?base:lock?"处理中，请勿重复操作":"请选择撤出比例";
        TradeButtons.state(approveRemove,"Approve LP · "+removeIdle,false,blocked); TradeButtons.state(remove,"Remove Liquidity · "+removeIdle,false,blocked);
        removeQuote.setText("Estimated receive：—");
        try {
            if(s!=null && s.fresh()) {
                BigInteger lp=LiquidityMath.portion(s.lpBalance,percent);
                BigInteger[] outputs=LiquidityMath.remove(s.pool,s.supply,lp);
                if(LiquidityMath.minimum(outputs[0]).signum()==0 || LiquidityMath.minimum(outputs[1]).signum()==0)throw new IllegalArgumentException("Too small");
                removeQuote.setText("撤出 LP："+TradeDisplay.amount(lp,18)+"\nEstimated receive："+TradeDisplay.amount(outputs[1],18)+" BKC + "+TradeDisplay.amount(outputs[0],6)+" mUSDT");
                String problem=removeProblem(s,lp); boolean approved=s.lpAllowance.compareTo(lp)>=0, valid=problem.isEmpty() && !lock;
                TradeButtons.state(approveRemove,approved?"LP 已授权":problem.isEmpty()?"Approve LP（仅本次份额）":problem,valid && !approved,blocked || !problem.isEmpty());
                TradeButtons.state(remove,!problem.isEmpty()?problem:lock?"处理中，请勿重复操作":approved?"Remove Liquidity":"Remove Liquidity · 请先授权 LP",valid && approved,blocked || !problem.isEmpty());
            }
        } catch(Exception ignored) { if(percent>0)removeQuote.setText(s!=null && s.lpBalance.signum()==0?"当前没有 LP 持仓。":"份额太小或数据未就绪，无法撤出。"); }
        renderPosition(s); history.bind(config,wallet,this::afterHistory);
    }
    private static String share(BigInteger lp,BigInteger total) { return TradeDisplay.decimal(new BigDecimal(lp).multiply(new BigDecimal("100")).divide(new BigDecimal(total),12,RoundingMode.DOWN)); }
    private void renderPosition(Snapshot s) {
        if(s==null) { position.setText("链上持仓：—"); fees.setText("Estimated earned fees：—"); return; }
        BigInteger pooledM=s.pool.musdtReserve.multiply(s.lpBalance).divide(s.supply), pooledB=s.pool.wbkcReserve.multiply(s.lpBalance).divide(s.supply);
        BigInteger value=pooledM.add(pooledB.multiply(s.pool.musdtReserve).divide(s.pool.wbkcReserve));
        position.setText("Pooled BKC："+TradeDisplay.amount(pooledB,18)+"\nPooled mUSDT："+TradeDisplay.amount(pooledM,6)
                +"\nPool share："+share(s.lpBalance,s.supply)+"%\nPosition value：≈ "+TradeDisplay.amount(value,6)+" mUSDT"+(s.fresh()?"":"\n数据已过期，仅供参考"));
        // Ownership changes invalidate the observation basis; do not attribute old pool growth to new LP.
        SharedPreferences prefs=activity.getSharedPreferences("lp_observation_v1",Context.MODE_PRIVATE);
        String prefix=wallet+"_"+config.pair()+"_"; BigInteger root=LiquidityMath.sqrt(s.pool.musdtReserve.multiply(s.pool.wbkcReserve));
        String oldLp=prefs.getString(prefix+"lp","");
        if(s.fresh() && !oldLp.equals(s.lpBalance.toString()))prefs.edit().putString(prefix+"lp",s.lpBalance.toString())
                .putString(prefix+"root",root.toString()).putString(prefix+"supply",s.supply.toString()).apply();
        try {
            BigInteger earned=LiquidityMath.estimatedFeesMusdt(s.pool,s.supply,s.lpBalance,
                    new BigInteger(prefs.getString(prefix+"root",root.toString())),new BigInteger(prefs.getString(prefix+"supply",s.supply.toString())));
            fees.setText("Estimated earned fees：≈ "+TradeDisplay.amount(earned,6)+" mUSDT\n仅估算本机观察以来每 LP 的池内增长；非实际盈利。");
        } catch(Exception e) { fees.setText("Estimated earned fees：暂无可靠观察数据。"); }
    }
    private void confirm(boolean adding,boolean approval) {
        if(locked())return;
        try {
            Snapshot s=snapshot; String base=baseProblem(s); if(!base.isEmpty()) { toast(base); return; }
            BigInteger desiredM=adding?SwapQuoteMath.parseUnits(musdt.getText().toString(),6):BigInteger.ZERO;
            BigInteger desiredB=adding?SwapQuoteMath.parseUnits(bkc.getText().toString(),18):BigInteger.ZERO;
            BigInteger lp=adding?BigInteger.ZERO:LiquidityMath.portion(s.lpBalance,percent);
            LiquidityMath.AddQuote q=adding?LiquidityMath.add(s.pool,s.supply,desiredM,desiredB):null;
            BigInteger[] out=adding?null:LiquidityMath.remove(s.pool,s.supply,lp);
            BigInteger minM=LiquidityMath.minimum(adding?q.musdt:out[0]),minB=LiquidityMath.minimum(adding?q.bkc:out[1]),minLp=adding?LiquidityMath.minimum(q.lp):BigInteger.ZERO;
            if(minM.signum()==0 || minB.signum()==0)throw new IllegalArgumentException("Amount too small");
            String problem=adding?addProblem(s,desiredM,desiredB):removeProblem(s,lp); if(!problem.isEmpty()) { toast(problem); return; }
            boolean approved=(adding?s.musdtAllowance.compareTo(desiredM):s.lpAllowance.compareTo(lp))>=0;
            if(approval==approved) { toast(approved?"额度已足够，无需重复授权":"请先授权本次金额"); return; }
            String action=approval?adding?"APPROVE_MUSDT":"APPROVE_LP":adding?"ADD":"REMOVE";
            String message=approval?"仅授权 "+SwapQuoteMath.format(adding?desiredM:lp,adding?6:18)+" "+(adding?"mUSDT":"LP")+" 给已验证的 Liquidity Router。\n授权成功后不会自动添加／撤出，请再次确认。"
                    : adding?"最多支付："+SwapQuoteMath.format(desiredB,18)+" BKC + "+SwapQuoteMath.format(desiredM,6)+" mUSDT"
                        +"\n预计 LP："+SwapQuoteMath.format(q.lp,18)+"\n最低 LP："+SwapQuoteMath.format(minLp,18)+"\n多余金额由合约退回。"
                    : "撤出 LP："+SwapQuoteMath.format(lp,18)+"\n预计到账："+SwapQuoteMath.format(out[1],18)+" BKC + "+SwapQuoteMath.format(out[0],6)+" mUSDT";
            message+="\n最低 BKC："+SwapQuoteMath.format(minB,18)+"\n最低 mUSDT："+SwapQuoteMath.format(minM,6)
                    +"\n滑点：0.5%\n手续费预留："+SwapQuoteMath.format(s.gas.multiply(BigInteger.valueOf(approved?1:2)),18)+" BKC（估算，非保证）"
                    +"\n账户："+wallet+"\n网络：1051\n授权／操作 Router："+config.liquidityRouter();
            final int id=generation; final String account=wallet;
            confirming=true; render(); parentChanged.run();
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(approval?"确认限额授权":adding?"确认添加流动性":"确认撤出流动性")
                    .setMessage(message).setNegativeButton("取消",null)
                    .setPositiveButton("确认",(d,w) -> prepare(id,account,action,desiredM,desiredB,lp,minM,minB,minLp,q==null?null:q.lp)).create();
            dialog.setOnDismissListener(d -> { confirming=false; render(); parentChanged.run(); }); dialog.show();
        } catch(Exception e) { toast("请刷新链上数据并检查金额／撤出比例"); }
    }
    private void prepare(int id,String account,String action,BigInteger desiredM,BigInteger desiredB,BigInteger lp,
                         BigInteger minM,BigInteger minB,BigInteger minLp,BigInteger estimatedLp) {
        preparing=true; render(); parentChanged.run();
        TradeReadQueue.execute(() -> {
            try {
                if(id!=generation || !sameAccount(account))throw new IllegalStateException("Account changed");
                String key=TokenWalletHelper.getCurrentPrivateKey(activity.getApplicationContext());
                // Always check code/getters again for a financial action, even if display validation is cached.
                client.validateLiquidity(key);
                Snapshot current=readSnapshot(key,account);
                boolean adding="ADD".equals(action)||"APPROVE_MUSDT".equals(action), approval=action.startsWith("APPROVE");
                String problem=adding?addProblem(current,desiredM,desiredB):removeProblem(current,lp);
                if(!problem.isEmpty())throw new IllegalStateException(problem);
                if(adding) {
                    LiquidityMath.AddQuote q=LiquidityMath.add(current.pool,current.supply,desiredM,desiredB);
                    LiquiditySafety.addMinimums(q,minM,minB,minLp);
                } else {
                    BigInteger[] out=LiquidityMath.remove(current.pool,current.supply,lp);
                    LiquiditySafety.removeMinimums(out,minM,minB);
                }
                if(!current.fresh())throw new IllegalStateException("Quote expired");
                BigInteger amount=adding?desiredM:lp;
                if(!approval && (adding?current.musdtAllowance:current.lpAllowance).compareTo(amount)<0)throw new IllegalStateException("Allowance insufficient");
                String target=approval?adding?config.musdt():config.pair():config.liquidityRouter();
                long deadline=System.currentTimeMillis()/1000+300;
                String data=approval?BrokerSwapAbi.call("approve",new Address(config.liquidityRouter()),new Uint256(amount))
                        :adding?LiquidityAbi.add(desiredM,minM,minB,minLp,account,deadline):LiquidityAbi.remove(lp,minM,minB,account,deadline);
                BigInteger value=!approval && adding?desiredB:BigInteger.ZERO;
                TokenTxRecord record=new TokenTxRecord(); record.operationId=UUID.randomUUID().toString(); record.timestampMs=System.currentTimeMillis();
                record.type=approval?"APPROVAL":"LIQUIDITY"; record.liquidityAction=action; record.routerAddress=config.liquidityRouter(); record.chainId=config.chainId();
                record.contractAddress=approval?target:config.pair(); record.inputUnits=amount.toString();
                if(!approval)record.toContractAddress=config.musdt();
                record.fromSymbol=approval?adding?"mUSDT":"LP":adding?"BKC + mUSDT":"LP"; record.toSymbol=adding?"LP":"BKC + mUSDT";
                record.amountDisplay=approval?SwapQuoteMath.format(amount,adding?6:18):adding?SwapQuoteMath.format(desiredB,18)+" BKC + "+SwapQuoteMath.format(desiredM,6)+" mUSDT":SwapQuoteMath.format(lp,18)+" LP";
                record.musdtUnits=desiredM.toString(); record.bkcUnits=desiredB.toString(); record.lpUnits=lp.toString();
                record.minimumMusdtUnits=minM.toString(); record.minimumBkcUnits=minB.toString(); record.minimumLpUnits=minLp.toString(); record.slippageBps=50;
                if(estimatedLp!=null)record.estimatedLp=SwapQuoteMath.format(estimatedLp,18);
                main.post(() -> {
                    preparing=false;
                    if(activity.isDestroyed() || id!=generation || !sameAccount(account))return;
                    if(!current.fresh()) { snapshot=null; error="报价已过期，未发交易。请刷新后重新确认。"; render(); parentChanged.run(); return; }
                    if(!SwapOperationRunner.submit(activity,config,account,record,target,data,value,this::completed))toast("已有交易处理中");
                    render(); parentChanged.run();
                });
            } catch(Exception e) {
                main.post(() -> {
                    preparing=false;
                    if(activity.isDestroyed())return;
                    if(id==generation && sameAccount(account)) { snapshot=null; error="发送前检查未通过，未发交易。请刷新后重新确认。"; toast(error); render(); }
                    parentChanged.run();
                });
            }
        });
    }
    private void completed(TokenTxRecord record) {
        if(activity.isDestroyed())return;
        toast(SwapRecordPresentation.status(record.status)); snapshot=null; fetch(); render(); parentChanged.run();
    }
    void changed() { if(visible) { snapshot=null; fetch(); render(); } }
    private void afterHistory() { render(); if(!SwapOperationRunner.active(wallet))fetch(); parentChanged.run(); }
    private String loadingText() {
        return readProgress==0?"查询排队中…":"正在查询链上数据（第 "+readProgress+" 项，首次可能需数分钟）…";
    }
    private void toast(String message) { Toast.makeText(activity,message,Toast.LENGTH_LONG).show(); }
}
