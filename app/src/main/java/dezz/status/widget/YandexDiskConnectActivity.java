/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import dezz.status.widget.files.*;
import java.util.concurrent.*;

/** Native login and optional web login, as in the reference app. No credentials in saved state. */
public final class YandexDiskConnectActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private YandexPassport passport;
    private EditText login,password,code;
    private TextView status;
    private Button enter,webButton;
    private boolean challenge;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,20,32,20);scroll.addView(root);
        TextView title=new TextView(this);title.setText("Подключить Яндекс Диск");title.setTextSize(28);root.addView(title);
        TextView detail=new TextView(this);detail.setText("Вход в аккаунт Яндекса. Пароль не сохраняется. Если нужен QR-код, Яндекс Ключ или другая проверка, откройте веб-вход.");detail.setTextSize(18);root.addView(detail);
        login=field(root,"Логин или почта",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password=field(root,"Пароль",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code=field(root,"Код из письма",InputType.TYPE_CLASS_TEXT);code.setVisibility(View.GONE);
        status=new TextView(this);status.setTextSize(18);root.addView(status);
        enter=button(root,"Войти",this::submit);
        webButton=button(root,"Открыть веб-вход Яндекса",()->startActivityForResult(new Intent(this,YandexDiskLoginActivity.class),1));
        button(root,"Назад",this::finish);setContentView(scroll);
    }
    private EditText field(LinearLayout root,String hint,int type){EditText field=new EditText(this);field.setHint(hint);field.setTextSize(20);field.setSingleLine();field.setInputType(type);field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);root.addView(field,new LinearLayout.LayoutParams(-1,(int)(64*getResources().getDisplayMetrics().density)));return field;}
    private Button button(LinearLayout root,String text,Runnable action){Button button=new Button(this);button.setAllCaps(false);button.setText(text);button.setTextSize(18);button.setOnClickListener(v->action.run());root.addView(button);return button;}
    private void submit(){
        String user=login.getText().toString().trim(),secret=password.getText().toString(),answer=code.getText().toString().trim();
        if((!challenge&&(user.isEmpty()||secret.isEmpty()))||(challenge&&answer.isEmpty())){status.setText("Заполните поля входа");return;}
        password.setText("");code.setText("");enter.setEnabled(false);webButton.setEnabled(false);status.setText("Подключение к Яндексу…");
        android.content.Context app=getApplicationContext();
        worker.execute(()->{try{
            boolean done;if(challenge){passport.confirm(answer);done=true;}else{passport=new YandexPassport();done=passport.login(user,secret);}
            if(done){YandexDiskSession session=YandexDiskSession.get(app);session.replace(passport.cookies());new YandexFiles(session,null).list("/");runOnUiThread(()->{if(!isDestroyed()){setResult(RESULT_OK);finish();}});}
            else runOnUiThread(()->{if(isDestroyed())return;challenge=true;login.setVisibility(View.GONE);password.setVisibility(View.GONE);code.setVisibility(View.VISIBLE);status.setText("Введите код из письма: "+passport.hint);enter.setText("Подтвердить код");enter.setEnabled(true);webButton.setEnabled(true);});
        }catch(Exception failure){runOnUiThread(()->{if(!isDestroyed()){status.setText(FileTaskService.safeMessage(failure));enter.setEnabled(true);webButton.setEnabled(true);}});}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1&&result==RESULT_OK){setResult(RESULT_OK);finish();}}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
