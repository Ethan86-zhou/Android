package com.zhou.musiccache;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** 仅debug的短时音频焦点测试；自动归还焦点，不更改音量或手机音频设置。 */
public final class AudioFocusProbeActivity extends Activity {
    private AudioManager manager;
    private AudioFocusRequest request;
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        manager=(AudioManager)getSystemService(AUDIO_SERVICE);
        request=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change->{},main).build();
        int result=manager.requestAudioFocus(request);
        TextView text=new TextView(this);text.setTextSize(20);text.setPadding(40,140,40,40);
        text.setText("播放中断恢复验证\n\n临时音频焦点："+(result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED?"已获得":"未获得")+"\n10秒后自动返回播放器");
        setContentView(text);main.postDelayed(this::finish,10_000);
    }
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);if(manager!=null&&request!=null)manager.abandonAudioFocusRequest(request);super.onDestroy();}
}
