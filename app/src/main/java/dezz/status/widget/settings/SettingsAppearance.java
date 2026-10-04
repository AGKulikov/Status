/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import dezz.status.widget.R;
import java.util.*;

/** Settings chrome only. User panel fonts, colours and preview renderers are never written. */
public final class SettingsAppearance {
    public static final String PREFS="natro_settings_ui_v1";
    public static final String EXTRA_FOCUS="dezz.status.widget.SETTINGS_FOCUS_TEXT";
    private static final Map<View,Boolean> styled=Collections.synchronizedMap(new WeakHashMap<>());
    private SettingsAppearance(){}
    public static SharedPreferences preferences(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static void configure(AppCompatActivity activity) {
        int mode=preferences(activity).getInt("theme",AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if(mode!=-1&&mode!=1&&mode!=2)mode=-1;
        activity.getDelegate().setLocalNightMode(mode);
    }
    public static void attach(AppCompatActivity activity) {
        View root=activity.findViewById(android.R.id.content);if(root==null)return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->apply(activity,root));
        root.post(()->{apply(activity,root);String query=activity.getIntent().getStringExtra(EXTRA_FOCUS);
            if(query!=null&&!query.isEmpty())focus(root,query);});
    }
    public static void apply(Context context,View view) {
        String name=view.getClass().getName();
        if(name.startsWith("dezz.")&&!name.contains("Settings")&&!name.contains("OptionalColor"))return;
        if(styled.put(view,true)==null) {
            int foreground=ContextCompat.getColor(context,R.color.text_primary);
            int background=ContextCompat.getColor(context,R.color.settings_background);
            if(view.getBackground() instanceof ColorDrawable) {
                int color=((ColorDrawable)view.getBackground()).getColor();
                if(Color.alpha(color)==255&&neutral(color)&&!(view instanceof Button))view.setBackgroundColor(background);
            }
            if(view instanceof TextView) {
                TextView text=(TextView)view;String label=text.getText().toString();
                boolean glyph=label.length()<=2||label.matches("#[0-9a-fA-F]{6,8}");
                if(!glyph) {
                    float minimum=Math.max(18,Math.min(26,preferences(context).getInt("textSp",20)));
                    float current=text.getTextSize()/context.getResources().getDisplayMetrics().scaledDensity;
                    if(current<minimum)text.setTextSize(minimum);
                    if(!(view instanceof Button)&&neutral(text.getCurrentTextColor()))text.setTextColor(foreground);
                    ViewGroup.LayoutParams params=text.getLayoutParams();
                    if(params!=null&&params.height>0&&params.height<dp(context,48)){params.height=ViewGroup.LayoutParams.WRAP_CONTENT;text.setLayoutParams(params);}
                }
                if(view instanceof Button||view instanceof EditText||view instanceof CompoundButton)text.setMinHeight(dp(context,52));
                if(view instanceof Button)((Button)view).setAllCaps(false);
                if(view instanceof EditText)((EditText)view).setHintTextColor(ContextCompat.getColor(context,R.color.settings_secondary_text));
            }
            if(view instanceof Switch) {
                int accent=ContextCompat.getColor(context,R.color.settings_accent),off=ContextCompat.getColor(context,R.color.settings_switch_track_off);
                ((Switch)view).setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,off}));
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(context,group.getChildAt(i));}
    }
    private static boolean neutral(int color){return Math.max(Color.red(color),Math.max(Color.green(color),Color.blue(color)))-Math.min(Color.red(color),Math.min(Color.green(color),Color.blue(color)))<35;}
    private static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    public static boolean focus(View root,String query) {
        String normalized=query.toLowerCase(Locale.ROOT).replace('ё','е').trim();
        View found=find(root,normalized);if(found==null)return false;
        found.requestFocus();View child=found;android.graphics.Rect bounds=new android.graphics.Rect();found.getDrawingRect(bounds);
        android.view.ViewParent parent=found.getParent();
        while(parent instanceof ViewGroup){((ViewGroup)parent).offsetDescendantRectToMyCoords(child,bounds);child=(View)parent;
            if(parent instanceof ScrollView){((ScrollView)parent).smoothScrollTo(0,Math.max(0,bounds.top-24));break;}parent=parent.getParent();}
        found.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);return true;
    }
    private static View find(View root,String query) {
        if(root instanceof TextView&&((TextView)root).getText().toString().toLowerCase(Locale.ROOT).replace('ё','е').contains(query))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View value=find(group.getChildAt(i),query);if(value!=null)return value;}}return null;
    }
}
