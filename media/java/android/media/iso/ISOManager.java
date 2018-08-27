package android.media.iso;

import android.media.MediaPlayer;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.File;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.MediaStore;
import android.database.Cursor;

import android.util.Log;


/**
 * {@hide}
 */
public class ISOManager
{
    private final static String TAG = "ISOManager";
    private MediaPlayer mMediaPlayer = null;

    public ISOManager(MediaPlayer player) {
        mMediaPlayer = player;
    }

    public static boolean existInBackUp(String path,String name,boolean isDirectory) {
        if((path == null) || (name == null))
            return false;

        File backup = new File(path);
        if(!backup.exists() || !backup.isDirectory()) {
            return false;
        }

        File file = new File(path+File.separator+name);
            if(file.exists()) {
                if(isDirectory) {
                    if(file.isDirectory()) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    if(file.isFile()) {
                        return true;
                    } else {
                        return false;
                    }
                }
            }

        return false;
    }

    public static String isBDDirectory(Context context, Uri uri) {
        String path = null;
        String mine = null;
        ContentResolver resolver = context.getContentResolver();
        if(resolver != null) {
            mine = resolver.getType(uri);
            Log.v(TAG,"mine = "+mine);
        }

        if((mine != null) && (mine.equals("video/iso"))) {
            String pathString = uri.toString();
            if(pathString != null && pathString.startsWith("file://")) {
                String prefix = new String("file://");
                if(pathString.startsWith(prefix)) {
                    path = pathString.substring(prefix.length());
                }
            } else {
                try {
                    String[] mCols = new String[] {
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.MIME_TYPE,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.BOOKMARK
                    };

                    Cursor cursor = resolver.query(uri, mCols, null, null, null);
                    if(cursor != null && cursor.getCount() >= 0) {
                        cursor.moveToFirst();
                        path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA));
                        cursor.close();
                    }
                } finally {}
            }
        }

        return path;
    }

    public static boolean isBDDirectory(String pathStr) {
        if(pathStr == null) {
            return false;
        }

        Uri uri = Uri.parse(pathStr);
        String path = pathStr;
        String scheme = uri.getScheme();
        if(scheme == null || scheme.equals("file")) {
            String temp = uri.toString();
            String prefix = new String("file://");
            if(temp != null && temp.startsWith(prefix)) {
                path = temp.substring(prefix.length());
            }
        }

        File file = new File(path);
        if(file.isDirectory()) {
            String bdmvName = path+File.separator+"BDMV";
            String backup = bdmvName+File.separator+"BACKUP";
            File bdmv = new File(bdmvName);
            if(bdmv.exists() && bdmv.isDirectory()) {
                String stream = bdmvName+File.separator+"STREAM";
                File streamFile = new File(stream);
                if(!streamFile.exists() && !existInBackUp(backup,"STREAM",true)) {
                    return false;
                }
                String playlist = bdmvName+File.separator+"PLAYLIST";
                File playlistFile = new File(playlist);
                if(!playlistFile.exists() && !existInBackUp(backup,"PLAYLIST",true)) {
                    return false;
                }
                String clip = bdmvName+File.separator+"CLIPINF";
                File clipFile = new File(clip);
                if(!clipFile.exists() && !existInBackUp(backup,"CLIPINF",true)) {
                    return false;
                }

                return true;
            } else {
                return false;
            }
        }

        return false;
    }
}

