package app.kotoba.reader;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import org.json.JSONObject;
import java.util.List;

/**
 * What's playing on the phone (Spotify, YouTube Music, NetEase…), for synced lyrics. Android shares other apps' media
 * sessions with a notification listener the user allows once (Settings › Notification access); Kotoba only reads the
 * song, its position and play state from them, and pauses or seeks when asked.
 */
public class NowPlaying extends NotificationListenerService {
    static boolean allowed(Context c){
        String s=android.provider.Settings.Secure.getString(c.getContentResolver(),"enabled_notification_listeners");
        return s!=null&&s.contains(new ComponentName(c,NowPlaying.class).flattenToString());
    }

    static MediaController controller(Context c){
        if(!allowed(c))return null;
        List<MediaController> list=c.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(c,NowPlaying.class));
        MediaController best=null;
        for(MediaController m:list){
            if(m.getMetadata()==null)continue;
            PlaybackState st=m.getPlaybackState();
            boolean playing=st!=null&&st.getState()==PlaybackState.STATE_PLAYING;
            if(best==null||playing){best=m;if(playing)break;}
        }
        return best;
    }

    /** {app, title, artist, album, duration (s), position (s, now), playing, control} or {playing:false,title:""}. */
    static JSONObject now(Context c) throws Exception {
        if(!allowed(c))return new JSONObject().put("playing",false).put("title","").put("access",false);
        MediaController m=controller(c);
        if(m==null)return new JSONObject().put("playing",false).put("title","").put("access",true);
        MediaMetadata md=m.getMetadata();
        PlaybackState st=m.getPlaybackState();
        boolean playing=st!=null&&st.getState()==PlaybackState.STATE_PLAYING;
        double pos=0;
        if(st!=null){
            long p=st.getPosition();
            // The position was true at its last update; carry it forward by the time since, at the playing speed.
            if(playing&&st.getLastPositionUpdateTime()>0)p+=(long)((SystemClock.elapsedRealtime()-st.getLastPositionUpdateTime())*st.getPlaybackSpeed());
            pos=p/1000.0;
        }
        String app=m.getPackageName();
        try{PackageManager pm=c.getPackageManager();app=pm.getApplicationLabel(pm.getApplicationInfo(app,0)).toString();}catch(Exception ignored){}
        String artist=md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if(artist==null||artist.isEmpty())artist=md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        return new JSONObject().put("app",app).put("title",nz(md.getString(MediaMetadata.METADATA_KEY_TITLE))).put("artist",nz(artist))
            .put("album",nz(md.getString(MediaMetadata.METADATA_KEY_ALBUM))).put("duration",md.getLong(MediaMetadata.METADATA_KEY_DURATION)/1000.0)
            .put("position",pos).put("playing",playing).put("control",true).put("access",true);
    }

    static JSONObject control(Context c,String action,double t) throws Exception {
        MediaController m=controller(c);
        if(m!=null){
            MediaController.TransportControls tc=m.getTransportControls();
            switch(action){
                case "pause":tc.pause();break;
                case "play":tc.play();break;
                case "seek":tc.seekTo((long)(t*1000));break;
                default:{PlaybackState st=m.getPlaybackState();if(st!=null&&st.getState()==PlaybackState.STATE_PLAYING)tc.pause();else tc.play();}
            }
        }
        return now(c);
    }

    static String nz(String s){return s==null?"":s;}
}
