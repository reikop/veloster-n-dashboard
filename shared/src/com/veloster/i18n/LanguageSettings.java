package com.veloster.i18n;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import java.util.Locale;

/** Per-app display preference. No system locale writes or cross-app settings. */
public final class LanguageSettings {
    private LanguageSettings() {}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("ui_language",Context.MODE_PRIVATE);}
    public static String selection(Context context){
        try{return UiText.selection(prefs(context).getString("language","auto"));}
        catch(ClassCastException e){return "auto";}
    }
    public static void init(Context context){UiText.configure(selection(context),Locale.getDefault().getLanguage());}
    public static boolean save(Context context,String language){
        boolean saved=prefs(context).edit().putString("language",UiText.selection(language)).commit();
        if(saved)init(context);
        return saved;
    }
    public static void show(final Activity activity,final Runnable changed){
        final String[] modes={"auto","ko","en"};
        final int[] selected={0};String current=selection(activity);
        for(int i=0;i<modes.length;i++)if(modes[i].equals(current))selected[0]=i;
        new AlertDialog.Builder(activity).setTitle("Language / 언어")
            .setSingleChoiceItems(new String[]{"System / 시스템","한국어","English"},selected[0],new DialogInterface.OnClickListener(){
                public void onClick(DialogInterface dialog,int which){selected[0]=which;}
            }).setPositiveButton(UiText.t("저장"),new DialogInterface.OnClickListener(){
                public void onClick(DialogInterface dialog,int which){
                    if(save(activity,modes[selected[0]])){if(changed!=null)changed.run();}
                    else new AlertDialog.Builder(activity).setMessage(UiText.t("언어 설정을 저장하지 못했습니다.")).setPositiveButton(UiText.t("확인"),null).show();
                }
            }).setNegativeButton(UiText.t("취소"),null).show();
    }
}
