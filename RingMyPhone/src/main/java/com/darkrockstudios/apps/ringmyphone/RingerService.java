package com.darkrockstudios.apps.ringmyphone;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import android.os.Vibrator;
import android.util.Log;

import com.getpebble.android.kit.Constants;
import com.getpebble.android.kit.util.PebbleDictionary;

import org.json.JSONException;

import java.util.Locale;

/**
 * Created by Adam on 10/14/13.
 */
public class RingerService extends Service {
    private static final String TAG = RingerService.class.getSimpleName();
    public static final String ACTION_STOP_RINGING = RingerService.class.getName() + ".STOP_RINGING";
    private static final String RINGS_NOTIFICATION_CHANNEL = "ringsNotificationChannel";
    private static final String RINGS_WITH_VIBRATION_NOTIFICATION_CHANNEL = "ringsWithVibrationNotificationChannel";
    private static final long[] VIBRATION_PATTERN = new long[] { 1000, 1000 };

    private static final int CMD_KEY = 0x1;

    private static final int CMD_START = 0x01;
    private static final int CMD_STOP = 0x02;

    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(context, RINGS_NOTIFICATION_CHANNEL);
            createNotificationChannel(context, RINGS_WITH_VIBRATION_NOTIFICATION_CHANNEL);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static void createNotificationChannel(Context context, String channelId) {
        CharSequence name;
        boolean vibrate = false;

        switch (channelId) {
            case RINGS_NOTIFICATION_CHANNEL:
                name = context.getString(R.string.notification_channel_rings_name);
                break;
            case RINGS_WITH_VIBRATION_NOTIFICATION_CHANNEL:
                name = context.getString(R.string.notification_channel_rings_with_vibration_name);
                vibrate = true;
                break;
            default:
                return;
        }

        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel = new NotificationChannel(channelId, name, importance);
        channel.setSound(null, null);
        channel.enableVibration(vibrate);

        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private Ringtone ringtone;
    private boolean currentlyOverridingVolume = false;
    private int savedVolume;
    private int savedRingerMode;

    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override public void onCreate() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null) {
            if (ACTION_STOP_RINGING.equals(intent.getAction())) {
                silencePhone(this);
            } else {
                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
                final boolean silentMode = settings.getBoolean(Preferences.KEY_SILENT_MODE, false);
                final String jsonData = intent.getStringExtra(Constants.MSG_DATA);

                if (jsonData != null) {
                    try {
                        final long cmd;
                        final PebbleDictionary data = PebbleDictionary.fromJson(jsonData);
                        cmd = data.getUnsignedIntegerAsLong(CMD_KEY);

                        if (cmd == CMD_START) {
                            Log.i(TAG, "Ring Command Received");
                            setMaxVolume(this);
                            ringPhone(this, silentMode);
                        } else if (cmd == CMD_STOP) {
                            Log.i(TAG, "Silence Command Received");
                            silencePhone(this);
                        } else {
                            Log.w(TAG, "Bad command received from pebble app: " + cmd);
                        }
                    } catch (final JSONException e) {
                        Log.w(TAG, "failed retrieved -> dict" + e);
                    }
                } else {
                    Log.w(TAG, "No data from Pebble");
                }
            }
        }

        return START_NOT_STICKY;
    }

    private void dismissRingingNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NotificationId.RINGING);
    }

    @SuppressLint("LaunchActivityFromNotification")
    private void postRingingNotification(boolean vibrate) {
        String channelId = vibrate ? RINGS_WITH_VIBRATION_NOTIFICATION_CHANNEL : RINGS_NOTIFICATION_CHANNEL;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId);
        builder.setTicker(getString(R.string.notification_ringing_ticker));
        builder.setContentTitle(getString(R.string.notification_ringing_ticker));
        builder.setContentText(getString(R.string.notification_ringing_text));
        builder.setSmallIcon(R.drawable.ic_action_volume_up);
        builder.setSound(null);

        if (vibrate) {
            builder.setVibrate(VIBRATION_PATTERN);
        }

        builder.setContentIntent(createStopRingingIntent());
        builder.setOngoing(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NotificationId.RINGING, builder.build());
    }

    private PendingIntent createStopRingingIntent() {
        Intent intent = new Intent(ACTION_STOP_RINGING);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        silencePhone(this);
    }

    private void setMaxVolume(final Context context) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int interruptionFilter = notificationManager.getCurrentInterruptionFilter();
            Log.d(TAG, "Current interruption filter: " + interruptionFilter);
            if (interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                Log.i(TAG, "Detected Do Not Disturb mode");
                if (notificationManager.isNotificationPolicyAccessGranted()) {
                    Log.i(TAG, "We have permission to override Do Not Disturb mode");
                } else {
                    Log.i(TAG, "We do not have permission to override Do Not Disturb mode. "
                            + "Leaving the volume as-is.");
                    return;
                }
            }
        }


        // If the current ringer mode is RINGER_MODE_VIBRATE, then the ring volume will be
        // remembered as 0, which is not correct. To get the user's real ringer volume, we set
        // the ringer mode to RINGER_MODE_NORMAL first, and only then look up the ring volume.
        // Tested on Android 12.

        savedRingerMode = audioManager.getRingerMode();
        Log.i(TAG, "Remembering current ringer mode: " + savedRingerMode);

        audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);

        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
        Log.i(TAG, "Remembering current volume: " + savedVolume);

        audioManager.setStreamVolume(
                AudioManager.STREAM_RING,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0);
        currentlyOverridingVolume = true;
    }

    private void restorePreviousVolume(final Context context) {
        if (currentlyOverridingVolume) {
            Log.i(TAG, "Restoring volume to " + savedVolume);
            audioManager.setStreamVolume(AudioManager.STREAM_RING, savedVolume, 0);

            Log.i(TAG, "Restoring ringer mode to " + savedRingerMode);
            audioManager.setRingerMode(savedRingerMode);
            currentlyOverridingVolume = false;
        } else {
            Log.w(TAG, "Was not currently overriding the volume and ringer mode");
        }
    }

    private void silencePhone(final Context context) {
        if (ringtone != null) {
            Log.i(TAG, "Silencing ringtone...");
            ringtone.stop();
            ringtone = null;
        } else {
            Log.w(TAG, "Ringtone was null, can't silence");
        }

        restorePreviousVolume(context);

        dismissRingingNotification();

        releaseWakeLock();
    }

    private void ringPhone(final Context context, final boolean silentMode) {
        getWakeLock(context);

        int ringerMode = audioManager.getRingerMode();
        double relativeVolume = (double) audioManager.getStreamVolume(AudioManager.STREAM_RING)
                / audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
        Log.d(TAG, String.format(Locale.ENGLISH, "ringerMode=%d relativeVolume=%f", ringerMode, relativeVolume));
        boolean vibrate = false;

        if (!silentMode) {
            // When testing on Android 12, I discovered that in Do Not Disturb mode, the ringer mode
            // is always reported as RINGER_MODE_SILENT, even when it's actually set to VIBRATE or
            // NORMAL and high-priority notifications are allowed through. As a result, we're only
            // checking the ringer volume, not the ringer mode. The ringer volume gets temporarily
            // set to 0 in the SILENT and VIBRATE ringer modes.

            // We don't need to care about the case where we're not in Do Not Disturb mode, because
            // if we're not in Do Not Disturb mode, then we were previously able to set the ringer
            // mode and volume to how we want them (playing sound at max volume).

            // TODO(Noah): Determine if vibration will succeed
            if (relativeVolume < 0.01) {
                Log.w(TAG, "Notifications are muted. Enabling vibration.");
                vibrate = true;
                // TODO(Noah): Inform the watch app that the phone is vibrating but the ringtone is not playing
            } else if (relativeVolume <= 0.5) {
                Log.w(TAG, "Ringer volume is less than half of the maximum. Enabling vibration.");
                vibrate = true;
                // TODO(Noah): Inform the watch app that the phone volume is low and the phone is vibrating
            }
        }

        // TODO(Noah): Keep vibration going
        audioManager.setMode(AudioManager.MODE_RINGTONE);
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        vibrator.vibrate(new long[] { 0, 1000, 500 }, 0);
        // TODO(Noah): Cancel vibration

        postRingingNotification(vibrate);

        if (!silentMode) {
            if (ringtone == null) {
                Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                ringtone = RingtoneManager.getRingtone(context, notification);
            }

            if (ringtone != null && !ringtone.isPlaying()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build();
                    ringtone.setAudioAttributes(audioAttributes);
                } else {
                    ringtone.setStreamType(AudioManager.STREAM_RING);
                }
                ringtone.play();
            }
        }
    }

    private void getWakeLock(final Context context) {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
            wakeLock.acquire(5 * 60 * 1000L /* 5 minutes */);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }

            wakeLock = null;
        }
    }
}
