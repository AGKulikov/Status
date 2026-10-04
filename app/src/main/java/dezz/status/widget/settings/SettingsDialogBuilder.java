/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import androidx.appcompat.app.AlertDialog;

/** The same form navigation and typography inside nested editor windows. */
public final class SettingsDialogBuilder extends AlertDialog.Builder {
    private CharSequence title = "";
    private View content;
    private static final java.util.Map<DialogInterface,SettingsEditSession.Savepoint> checkpoints=new java.util.WeakHashMap<>();
    public SettingsDialogBuilder(Context context) { super(context); }
    private View preview(){
        Context context=getContext();
        while(context instanceof android.content.ContextWrapper){
            if(context instanceof SettingsPreviewProvider)return ((SettingsPreviewProvider)context).settingsPreview();
            Context next=((android.content.ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
        }
        return null;
    }
    @Override public SettingsDialogBuilder setTitle(CharSequence title) {
        this.title = title; super.setTitle(title); return this;
    }
    @Override public SettingsDialogBuilder setView(View view) { content = view; return this; }
    @Override public SettingsDialogBuilder setPositiveButton(CharSequence text,DialogInterface.OnClickListener listener){
        super.setPositiveButton(text,listener==null?null:(dialog,which)->{listener.onClick(dialog,which);accept(dialog);});return this;
    }
    @Override public SettingsDialogBuilder setPositiveButton(int text,DialogInterface.OnClickListener listener){return setPositiveButton(getContext().getText(text),listener);}
    @Override public SettingsDialogBuilder setItems(CharSequence[] items,DialogInterface.OnClickListener listener){
        super.setItems(items,(dialog,which)->{if(listener!=null)listener.onClick(dialog,which);accept(dialog);});return this;
    }
    public static void accept(DialogInterface dialog){SettingsEditSession.Savepoint checkpoint=checkpoints.get(dialog);if(checkpoint!=null)checkpoint.accept();}
    public static void commitAndDismiss(DialogInterface dialog){accept(dialog);dialog.dismiss();}
    @Override public AlertDialog create() {
        if (content != null) {
            if(content instanceof android.widget.LinearLayout
                    &&((android.widget.LinearLayout)content).getOrientation()==android.widget.LinearLayout.VERTICAL
                    &&((android.widget.LinearLayout)content).getChildCount()>=4){
                android.widget.ScrollView scroll=new android.widget.ScrollView(getContext());scroll.addView(content);content=scroll;
            }
            View preview=preview();
            if(preview!=null&&(content instanceof android.widget.ScrollView||content instanceof androidx.core.widget.NestedScrollView)){
                android.view.ViewGroup scroller=(android.view.ViewGroup)content;
                if(scroller.getChildCount()==1&&scroller.getChildAt(0) instanceof android.widget.LinearLayout){
                    android.widget.LinearLayout form=(android.widget.LinearLayout)scroller.getChildAt(0);
                    if(form.getChildCount()>=4)form.addView(new SettingsPreviewView(getContext(),preview));
                }
            }
            View wrapped = SettingsEditorLayout.wrap(getContext(), content,
                    getContext().getClass().getName() + ":" + title);
            if (wrapped == content) SettingsEditorLayout.install(content, getContext().getClass().getName() + ":" + title);
            if (wrapped instanceof SettingsEditorLayout) {
                float density = getContext().getResources().getDisplayMetrics().density;
                int height = getContext().getResources().getDisplayMetrics().heightPixels;
                wrapped.setMinimumHeight(Math.max(Math.round(250 * density), height - Math.round(190 * density)));
            }
            super.setView(wrapped);
        }
        AlertDialog dialog = super.create();
        SettingsEditSession session=SettingsEditSession.find(getContext());
        if(session!=null){
            SettingsEditSession.Savepoint checkpoint=session.checkpoint();checkpoints.put(dialog,checkpoint);
            dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View view){}
                @Override public void onViewDetachedFromWindow(View view){checkpoint.finish();checkpoints.remove(dialog);}
            });
        }
        if (content != null) content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.post(() -> {
                    if (dialog.getWindow() != null) SettingsAppearance.apply(getContext(), dialog.getWindow().getDecorView());
                });
            }
            @Override public void onViewDetachedFromWindow(View view) {}
        });
        return dialog;
    }
}
