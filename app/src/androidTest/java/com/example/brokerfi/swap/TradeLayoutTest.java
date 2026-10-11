package com.example.brokerfi.swap;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.widget.NestedScrollView;
import com.example.brokerfi.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Layout-only regression tests: no account access, navigation, networking or transactions. */
@RunWith(AndroidJUnit4.class)
public class TradeLayoutTest {
    private View inflate(int layout) {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        return LayoutInflater.from(new ContextThemeWrapper(app,R.style.Theme_BrokerFi)).inflate(layout,null,false);
    }
    @Test public void legacySwapDefaultsHideTradeOnlyUi() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root=inflate(R.layout.activity_token_swap);
            assertEquals(View.GONE,root.findViewById(R.id.trade_tabs).getVisibility());
            assertEquals(View.GONE,root.findViewById(R.id.trade_liquidity_content).getVisibility());
            assertEquals(View.VISIBLE,root.findViewById(R.id.trade_swap_content).getVisibility());
            assertNotNull(root.findViewById(R.id.token_pay_amount));
            assertFalse(root.findViewById(R.id.token_confirm_button).isEnabled());
        });
    }
    @Test public void liquidityControlsExistAndStartDisabled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root=inflate(R.layout.activity_token_swap);
            for(int id:new int[]{R.id.lp_approve_add,R.id.lp_add,R.id.lp_approve_remove,R.id.lp_remove}) {
                assertTrue(root.findViewById(id) instanceof Button); assertFalse(root.findViewById(id).isEnabled());
            }
            SeekBar slider=root.findViewById(R.id.lp_remove_slider);
            assertEquals(100,slider.getMax()); assertEquals(0,slider.getProgress());
            assertEquals(View.GONE,root.findViewById(R.id.lp_history_rows).getVisibility());
            assertNotNull(root.findViewById(R.id.lp_position));
        });
    }
    @Test public void assetsUsesTradeActionAndPreservesSendReceive() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root=inflate(R.layout.activity_token_detail);
            assertNotNull(root.findViewById(R.id.token_action_send)); assertNotNull(root.findViewById(R.id.token_action_receive));
            assertNotNull(root.findViewById(R.id.token_action_wrap));
            Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
            assertEquals("Assets",app.getString(R.string.token_home_label)); assertEquals("Trade",app.getString(R.string.trade_title));
        });
    }
    @Test public void restoredHomepageLayoutRemainsAvailable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertNotNull(inflate(R.layout.activity_swap)));
    }
    @Test public void liquidityContentCanScrollToRemovalAndHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root=inflate(R.layout.activity_token_swap);
            root.findViewById(R.id.trade_tabs).setVisibility(View.VISIBLE);
            root.findViewById(R.id.trade_swap_content).setVisibility(View.GONE);
            root.findViewById(R.id.trade_liquidity_content).setVisibility(View.VISIBLE);
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2200,View.MeasureSpec.EXACTLY));
            root.layout(0,0,1080,2200);
            NestedScrollView scroll=root.findViewById(R.id.trade_scroll);
            assertTrue("LP content must extend beyond the viewport",scroll.getChildAt(0).getHeight()>scroll.getHeight());
            scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
            assertTrue("Removal/history must be scrollable",scroll.getScrollY()>0);
        });
    }
}
