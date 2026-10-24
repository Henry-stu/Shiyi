package com.hh.recall;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Read-only final Release verification. Outputs hashes/counts, never article or draft contents. */
public final class ReleaseAuditInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle status=new Bundle();
        try {
            Context app=getTargetContext();
            if((app.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)throw new IllegalStateException("Expected Release");
            JSONObject result=new JSONObject(),tables=new JSONObject(),models=new JSONObject();
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(app.getDatabasePath("recall.db").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                result.put("database_version",db.getVersion());
                for(String table:new String[]{"articles","folders","drafts"}){
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    try(Cursor cursor=db.rawQuery("SELECT * FROM "+table+" ORDER BY "+(table.equals("drafts")?"articleId":"id"),null)){
                        String[] columns=cursor.getColumnNames().clone();Arrays.sort(columns);
                        while(cursor.moveToNext())for(String column:columns){
                            add(digest,column.getBytes(StandardCharsets.UTF_8));int index=cursor.getColumnIndexOrThrow(column);
                            String value=cursor.isNull(index)?"N":cursor.getType(index)==Cursor.FIELD_TYPE_INTEGER?"I"+cursor.getLong(index):"S"+cursor.getString(index);
                            add(digest,value.getBytes(StandardCharsets.UTF_8));
                        }
                        tables.put(table,new JSONObject().put("count",cursor.getCount()).put("sha256",hex(digest.digest())));
                    }
                }
            }
            for(String name:new String[]{"PP-OCRv6_det_small.onnx","PP-OCRv6_rec_small.onnx"}){
                String asset,cache;try(InputStream in=app.getAssets().open("models/"+name)){asset=hash(in);}
                try(InputStream in=new FileInputStream(new File(new File(app.getNoBackupFilesDir(),"import-models"),name))){cache=hash(in);}
                if(!asset.equals(cache))throw new IllegalStateException("Model mismatch");
                models.put(name,new JSONObject().put("asset_sha256",asset).put("cache_sha256",cache));
            }
            result.put("tables",tables).put("models",models).put("debuggable",false)
                .put("version",app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName);
            status.putString("stream","RECALL_RELEASE_AUDIT "+result+"\n");finish(Activity.RESULT_OK,status);
        } catch(Exception error) {
            status.putString("stream","RECALL_RELEASE_AUDIT_FAILED "+error.getClass().getSimpleName()+"\n");finish(Activity.RESULT_CANCELED,status);
        }
    }
    private static void add(MessageDigest digest,byte[] value){
        int n=value.length;digest.update(new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n});digest.update(value);
    }
    private static String hash(InputStream in)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);return hex(digest.digest());}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
