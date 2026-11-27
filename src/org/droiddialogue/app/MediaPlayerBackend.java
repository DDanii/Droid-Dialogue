package org.droiddialogue.app;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.PowerManager;

import org.droiddialogue.core.Dbg;
import org.droiddialogue.dlna.MediaBackend;

/**
 * Plays what Home Assistant sends to the phone's DLNA speaker, with android.media.MediaPlayer.
 * Everything is synchronized; the MediaPlayer callbacks arrive on the main thread and take the same
 * lock. Callbacks of a player that was replaced in the meantime are ignored.
 */
final class MediaPlayerBackend implements MediaBackend {
    private final Context ctx;
    private final AudioManager audio;

    private MediaPlayer player;
    private boolean prepared;
    private boolean wantPlay;
    private boolean muted;
    private String uri = "";
    private volatile int state = NO_MEDIA;
    private Listener listener;

    MediaPlayerBackend(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public synchronized void setListener(Listener l) {
        listener = l;
    }

    private void setState(int s) {
        if (state != s) {
            state = s;
            Listener l = listener;
            if (l != null) {
                l.onStateChanged(s);
            }
        }
    }

    public synchronized void setUri(String u) {
        releasePlayer();
        uri = u == null ? "" : u;
        wantPlay = false;
        prepared = false;
        if (uri.length() == 0) {
            setState(NO_MEDIA);
            return;
        }
        final MediaPlayer p = new MediaPlayer();
        try {
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    onPreparedEvent(mp);
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    onCompletionEvent(mp);
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    onErrorEvent(mp, what, extra);
                    return true;
                }
            });
            p.setDataSource(uri);
            applyMute(p);
            player = p;
            p.prepareAsync();
            setState(STOPPED);
        } catch (Exception e) {
            try {
                p.release();
            } catch (Exception ignored) {
                // nothing more to do
            }
            player = null;
            setState(STOPPED);
            Dbg.d("speaker: cannot load " + uri + ": " + e);
            throw new IllegalStateException("cannot load the address: " + e.getMessage());
        }
    }

    public synchronized void play() {
        if (player == null || uri.length() == 0) {
            throw new IllegalStateException("no media");
        }
        wantPlay = true;
        if (prepared) {
            startPlayer();
        } else {
            setState(LOADING);
        }
    }

    private void startPlayer() {
        try {
            player.start();
            setState(PLAYING);
        } catch (IllegalStateException e) {
            Dbg.d("speaker: cannot start: " + e);
            wantPlay = false;
            setState(STOPPED);
        }
    }

    public synchronized void pause() {
        wantPlay = false;
        if (player == null) {
            return;
        }
        try {
            if (prepared && player.isPlaying()) {
                player.pause();
                setState(PAUSED);
            } else if (state == LOADING) {
                setState(PAUSED); // will not start when ready, play() starts it
            }
        } catch (IllegalStateException e) {
            Dbg.d("speaker: cannot pause: " + e);
        }
    }

    public synchronized void stop() {
        wantPlay = false;
        if (player != null && prepared) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
                player.seekTo(0);
            } catch (IllegalStateException e) {
                Dbg.d("speaker: cannot stop: " + e);
            }
        }
        if (uri.length() > 0) {
            setState(STOPPED);
        }
    }

    public synchronized void seek(long positionMs) {
        if (player != null && prepared) {
            try {
                player.seekTo((int) Math.max(0, Math.min(Integer.MAX_VALUE, positionMs)));
            } catch (IllegalStateException e) {
                Dbg.d("speaker: cannot seek: " + e);
            }
        }
    }

    public int getState() {
        return state;
    }

    public synchronized long getPositionMs() {
        if (player == null || !prepared) {
            return 0;
        }
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public synchronized long getDurationMs() {
        if (player == null || !prepared) {
            return 0;
        }
        try {
            return Math.max(0, player.getDuration());
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int getVolume() {
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max <= 0 ? 0 : Math.round(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public void setVolume(int percent) {
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent * max / 100f), 0);
        } catch (RuntimeException e) {
            Dbg.d("speaker: cannot set the volume: " + e);
        }
    }

    public synchronized boolean isMuted() {
        return muted;
    }

    public synchronized void setMuted(boolean m) {
        muted = m;
        if (player != null) {
            applyMute(player);
        }
    }

    private void applyMute(MediaPlayer p) {
        float v = muted ? 0f : 1f;
        try {
            p.setVolume(v, v);
        } catch (IllegalStateException e) {
            // not in a state that allows it, the next player picks the flag up
        }
    }

    synchronized void shutdown() {
        releasePlayer();
        wantPlay = false;
        uri = "";
        setState(NO_MEDIA);
        listener = null;
    }

    private void releasePlayer() {
        MediaPlayer p = player;
        player = null;
        prepared = false;
        if (p != null) {
            try {
                p.setOnPreparedListener(null);
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
                p.release();
            } catch (Exception e) {
                // already gone
            }
        }
    }

    // ------------------------------------------------------------------ MediaPlayer callbacks (main thread)

    private synchronized void onPreparedEvent(MediaPlayer mp) {
        if (mp != player) {
            return;
        }
        prepared = true;
        if (wantPlay) {
            startPlayer();
        }
    }

    private synchronized void onCompletionEvent(MediaPlayer mp) {
        if (mp != player) {
            return;
        }
        wantPlay = false;
        setState(STOPPED);
    }

    private synchronized void onErrorEvent(MediaPlayer mp, int what, int extra) {
        if (mp != player) {
            return;
        }
        Dbg.d("speaker: playback error " + what + "/" + extra + " for " + uri);
        wantPlay = false;
        releasePlayer();
        setState(STOPPED);
    }
}
