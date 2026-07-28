/*
 * Copyright (c) 2005-2018, BearWare.dk
 * 
 * Contact Information:
 *
 * Bjoern D. Rasmussen
 * Kirketoften 5
 * DK-8260 Viby J
 * Denmark
 * Email: contact@bearware.dk
 * Phone: +45 20 20 54 59
 * Web: http://www.bearware.dk
 *
 * This source code is part of the TeamTalk SDK owned by
 * BearWare.dk. Use of this file, or its compiled unit, requires a
 * TeamTalk SDK License Key issued by BearWare.dk.
 *
 * The TeamTalk SDK License Agreement along with its Terms and
 * Conditions are outlined in the file License.txt included with the
 * TeamTalk SDK distribution.
 *
 */

package org.nekit.ttproplus.backend;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import dk.bearware.AudioPreprocessor;
import dk.bearware.AudioPreprocessorType;
import dk.bearware.Channel;
import dk.bearware.ClientErrorMsg;
import dk.bearware.ClientEvent;
import dk.bearware.ClientFlag;
import dk.bearware.EncryptionContext;
import dk.bearware.FileTransfer;
import dk.bearware.FileTransferStatus;
import dk.bearware.MediaFileInfo;
import dk.bearware.MediaFileStatus;
import dk.bearware.RemoteFile;
import dk.bearware.ServerProperties;
import dk.bearware.SoundDeviceConstants;
import dk.bearware.SoundLevel;
import dk.bearware.StreamType;
import dk.bearware.Subscription;
import dk.bearware.TeamTalk5;
import dk.bearware.TeamTalkBase;
import dk.bearware.TextMessage;
import dk.bearware.TextMsgType;
import dk.bearware.User;
import dk.bearware.UserAccount;
import dk.bearware.UserRight;
import dk.bearware.WebRTCConstants;
import org.nekit.ttproplus.data.AppInfo;
import org.nekit.ttproplus.data.License;
import org.nekit.ttproplus.data.MyTextMessage;
import org.nekit.ttproplus.data.Preferences;
import org.nekit.ttproplus.data.ServerEntry;
import org.nekit.ttproplus.data.UserCached;
import dk.bearware.MediaFilePlayback;
import dk.bearware.events.ClientEventListener;
import dk.bearware.events.TeamTalkEventHandler;
import org.nekit.ttproplus.gui.CmdComplete;
import org.nekit.ttproplus.gui.MainActivity;
import org.nekit.ttproplus.gui.MediaButtonEventReceiver;
import org.nekit.ttproplus.R;
import org.nekit.ttproplus.gui.Utils;

import static org.nekit.ttproplus.gui.CmdComplete.CMD_COMPLETE_NONE;

public class TeamTalkService extends Service
        implements BluetoothHeadsetHelper.HeadsetConnectionListener,
        BluetoothHeadsetHelper.ScoAudioConnectionListener,
        ClientEventListener.OnConnectSuccessListener,
        ClientEventListener.OnConnectFailedListener,
        ClientEventListener.OnConnectionLostListener,
        ClientEventListener.OnEncryptionErrorListener,
        ClientEventListener.OnCmdSuccessListener,
        ClientEventListener.OnCmdProcessingListener,
        ClientEventListener.OnCmdMyselfLoggedInListener,
        ClientEventListener.OnCmdMyselfKickedFromChannelListener,
        ClientEventListener.OnCmdErrorListener,
        ClientEventListener.OnCmdUserLoggedInListener,
        ClientEventListener.OnCmdUserLoggedOutListener,
        ClientEventListener.OnCmdUserUpdateListener,
        ClientEventListener.OnCmdUserJoinedChannelListener,
        ClientEventListener.OnCmdUserLeftChannelListener,
        ClientEventListener.OnCmdUserTextMessageListener,
        ClientEventListener.OnCmdChannelNewListener,
        ClientEventListener.OnCmdChannelRemoveListener,
        ClientEventListener.OnCmdServerUpdateListener,
        ClientEventListener.OnCmdChannelUpdateListener,
        ClientEventListener.OnCmdFileNewListener,
        ClientEventListener.OnCmdFileRemoveListener,
        ClientEventListener.OnUserStateChangeListener,
        ClientEventListener.OnVoiceActivationListener,
        ClientEventListener.OnFileTransferListener,
        ClientEventListener.OnStreamMediaFileListener {

    public static final String CANCEL_TRANSFER = "cancel_transfer";

    public static final String TAG = "bearware";

    private static final int UI_WIDGET_ID = 1;
    private static final String UI_CHANNEL_ID = "TeamtalkConnection";
    /** Delay before re-connecting Bluetooth SCO after a phone call ends (system needs time to release SCO). */
    private static final long BLUETOOTH_SCO_RECONNECT_DELAY_MS = 500;

    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();

    private BluetoothHeadsetHelper bluetoothHeadsetHelper;
    private TelephonyManager telephonyManager;
    OnVoiceTransmissionToggleListener onVoiceTransmissionToggleListener;
    private boolean listeningPhoneStateChanges;
    private boolean txSuspended;
    private boolean voxSuspended;
    private boolean permanentMuteState;
    private boolean currentMuteState;
    private Notification widget = null;
    private NotificationManager notificationManager;
    private volatile boolean inPhoneCall;
    private MediaSessionCompat mediaSession;
    Handler reconnectHandler = new Handler();
    Runnable reconnectTimer = this::reconnect;
    private Runnable reconnectBluetoothScoAfterCall;

    TeamTalkBase ttclient;
    ServerEntry ttserver;
    Channel joinchannel, /* the channel to join after login */
            mychannel; /* the channel 'ttclient' is currently in */
    private final TeamTalkEventHandler mEventHandler = new TeamTalkEventHandler();
    CountDownTimer eventTimer;
    SparseArray<CmdComplete> activecmds = new SparseArray<>();

    Map<Integer, Channel> channels = new HashMap<>();
    Map<Integer, RemoteFile> remoteFiles = new HashMap<>();
    Map<Integer, FileTransfer> fileTransfers = new HashMap<>();
    Map<Integer, User> users = new HashMap<>();
    Map<Integer, Vector<MyTextMessage>> usertxtmsgs = new HashMap<>();
    Vector<MyTextMessage> chatlogtxtmsgs = new Vector<>();
    Map<String, UserCached> usercache = new HashMap<>();

    private org.nekit.ttproplus.gui.FloatingWindowManager mFloatingWindowManager;
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (org.nekit.ttproplus.data.Preferences.PREF_BG_MGMT_ENABLED.equals(key) ||
                org.nekit.ttproplus.data.Preferences.PREF_BG_MGMT_SHOW_VOICE.equals(key) ||
                org.nekit.ttproplus.data.Preferences.PREF_BG_MGMT_SHOW_MUTE.equals(key) ||
                org.nekit.ttproplus.data.Preferences.PREF_BG_MGMT_SHOW_PING.equals(key) ||
                org.nekit.ttproplus.data.Preferences.PREF_BG_MGMT_SHOW_CHAT.equals(key) ||
                org.nekit.ttproplus.data.Preferences.PREF_BG_MGMT_SHOW_CHANNELS.equals(key)) {
                if (mFloatingWindowManager != null) {
                    mFloatingWindowManager.checkAndShow();
                }
            }
        }
    };

    public void updateFloatingWindow() {
        if (mFloatingWindowManager != null) {
            mFloatingWindowManager.updateUI();
        }
    }

    public org.nekit.ttproplus.gui.FloatingWindowManager getFloatingWindowManager() {
        return mFloatingWindowManager;
    }

    private long antispam_window_start = 0;
    private int antispam_count = 0;
    private boolean antispam_triggered = false;
    private final HashSet<Integer> antispam_blocked = new HashSet<>();
    private final HashMap<Integer, Integer> antispam_user_counts = new HashMap<>();

    public void resetState() {
        antispam_triggered = false;
        antispam_count = 0;
        antispam_window_start = 0;
        antispam_blocked.clear();
        antispam_user_counts.clear();
        reconnectHandler.removeCallbacks(reconnectTimer);
        disablePhoneCallReaction();

        syncToUserCache();

        if(ttclient != null)
            ttclient.disconnect();

        displayNotification(false);
        joinchannel = null;
        setMyChannel(null);
        activecmds.clear();
        channels.clear();
        remoteFiles.clear();
        fileTransfers.clear();
        users.clear();
        usertxtmsgs.clear();
        chatlogtxtmsgs.clear();
    }

    public Map<Integer, Channel> getChannels() {
        return channels;
    }
    public Map<Integer, RemoteFile> getRemoteFiles() {
        return remoteFiles;
    }
    public Map<Integer, FileTransfer> getFileTransfers() {
        return fileTransfers;
    }
    public Map<Integer, User> getUsers() {
        return users;
    }

    private final MediaSessionCompat.Callback mMediaSessionCallback = new MediaSessionCompat.Callback() {

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
            super.onMediaButtonEvent(mediaButtonEvent);
            final String intentAction = mediaButtonEvent.getAction();
            if (Intent.ACTION_MEDIA_BUTTON.equals(intentAction)) {
                final KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null) {
                    return false;
                }
                final int keycode = event.getKeyCode();
                final int action = event.getAction();
                if (event.getRepeatCount() == 0 && action == KeyEvent.ACTION_DOWN) {
                    switch (keycode) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                            if (isVoiceActivationEnabled())
                                enableVoiceActivation(false);
                            else
                                enableVoiceTransmission(!isVoiceTransmissionEnabled());
                            break;
                    }
                    return true;
                }
            }
            return false;
        }
    };


    public class LocalBinder extends Binder {
        public TeamTalkService getService() {
            // Return this instance of LocalService so clients can call public methods
            return TeamTalkService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // make sure DLL is loaded 
        TeamTalk5.loadLibrary();

        TeamTalk5.setLicenseInformation(License.REGISTRATION_NAME, License.REGISTRATION_KEY);

        ttclient = new TeamTalk5();
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        listeningPhoneStateChanges = false;
        txSuspended = false;
        voxSuspended = false;
        permanentMuteState = false;
        currentMuteState = false;
        inPhoneCall = false;

        //register self as event handler so 'users' and 'channels' can be updated
        mEventHandler.registerOnConnectSuccessListener(this, true);
        mEventHandler.registerOnConnectFailedListener(this, true);
        mEventHandler.registerOnConnectionLostListener(this, true);
        mEventHandler.registerOnEncryptionErrorListener(this, true);

        mEventHandler.registerOnCmdError(this, true);
        mEventHandler.registerOnCmdSuccess(this, true);
        mEventHandler.registerOnCmdProcessing(this, true);
        mEventHandler.registerOnCmdMyselfLoggedIn(this, true);
        mEventHandler.registerOnCmdMyselfKickedFromChannel(this, true);
        mEventHandler.registerOnCmdUserLoggedIn(this,true);
        mEventHandler.registerOnCmdUserLoggedOut(this, true);
        mEventHandler.registerOnCmdUserUpdate(this, true);
        mEventHandler.registerOnCmdUserJoinedChannel(this, true);
        mEventHandler.registerOnCmdUserLeftChannel(this, true);
        mEventHandler.registerOnCmdUserTextMessage(this, true);
        mEventHandler.registerOnCmdChannelNew(this, true);
        mEventHandler.registerOnCmdChannelUpdate(this, true);
        mEventHandler.registerOnCmdChannelRemove(this, true);
        mEventHandler.registerOnCmdServerUpdate(this, true);
        mEventHandler.registerOnCmdFileNew(this, true);
        mEventHandler.registerOnCmdFileRemove(this, true);

        mEventHandler.registerOnUserStateChange(this, true);

        mEventHandler.registerOnVoiceActivation(this, true);
        mEventHandler.registerOnFileTransfer(this, true);
        mEventHandler.registerOnStreamMediaFile(this, true);

        //create timer to process 'mEventHandler'
        createEventTimer();

        bluetoothHeadsetHelper = new BluetoothHeadsetHelper(this);
        reconnectBluetoothScoAfterCall = this::reconnectBluetoothScoAfterCallRun;

        ComponentName receiver = new ComponentName(getPackageName(), MediaButtonEventReceiver.class.getName());
        //mediaSession = new MediaSessionCompat(this, "MediaService", receiver, null);
        mediaSession = new MediaSessionCompat(this, "TeamTalkService");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0)
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .build());
        mediaSession.setCallback(mMediaSessionCallback);

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.requestAudioFocus(focusChange -> {
            // Ignore
        }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        mediaSession.setActive(true);
        Log.d(TAG, "Created TeamTalk 5 service");

        mFloatingWindowManager = new org.nekit.ttproplus.gui.FloatingWindowManager(this);
        mFloatingWindowManager.checkAndShow();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        prefs.registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null) && intent.hasExtra(CANCEL_TRANSFER)) {
            int transferId = intent.getIntExtra(CANCEL_TRANSFER, 0);
            if ((ttclient != null) && ttclient.cancelFileTransfer(transferId)) {
                fileTransfers.remove(transferId);
                Toast.makeText(this, R.string.transfer_stopped, Toast.LENGTH_LONG).show();
            }
        }
        if (mediaSession.getController().getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING) {
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE).build());
        } else {
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE).build());
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        eventTimer.cancel();

        mEventHandler.unregisterListener(this);
        disablePhoneCallReaction();
        unwatchBluetoothHeadset();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        prefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        if (mFloatingWindowManager != null) {
            mFloatingWindowManager.hide();
            mFloatingWindowManager = null;
        }

        if (ttclient != null)
            ttclient.closeTeamTalk();

        super.onDestroy();
        mediaSession.release();

        Log.d(TAG, "Destroyed TeamTalk 5 service");
    }

    private String getNotificationText() {
        return (mychannel != null) ?
            String.format("%s / %s", ttserver.servername, mychannel.szName) :
            ttserver.servername;
    }

    @SuppressLint("NewApi")
    private void displayNotification(boolean enabled) {
        if (enabled) {
            if (widget == null) {
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                Intent ui = new Intent(this, MainActivity.class);
                ui.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel mChannel = new NotificationChannel(UI_CHANNEL_ID, "Teamtalk connection", NotificationManager.IMPORTANCE_DEFAULT);
                    mChannel.enableVibration(false);
                    mChannel.setVibrationPattern(null);
                    mChannel.enableLights(false);
                    mChannel.setSound(null, null);
                    notificationManager.createNotificationChannel(mChannel);
                }
                int intentFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
                widget = new NotificationCompat.Builder(this, UI_CHANNEL_ID)
                    .setSmallIcon(R.drawable.teamtalk_green)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentIntent(PendingIntent.getActivity(this, 0, ui, intentFlags))
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentText(getNotificationText())
                    .setShowWhen(false)
                    .build();
                ServiceCompat.startForeground(this, UI_WIDGET_ID, widget, getMyForegroundServiceType());
            } else {
                widget = new NotificationCompat.Builder(this, widget)
                    .setContentText(getNotificationText())
                    .build();
                notificationManager.notify(UI_WIDGET_ID, widget);
            }
        } else if (widget != null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            widget = null;
        }
    }

    private int getMyForegroundServiceType() {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (mediaProjection != null) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }
        return type;
    }

    private void adjustMuteOnTx(boolean txEnabled) {
        if (PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean(Preferences.PREF_SOUNDSYSTEM_MUTE_ON_TRANSMISSION, false)) {
            boolean isMuted = isMute();
            if ((txEnabled && !isMuted) || (isMuted && !txEnabled && !permanentMuteState))
                ttclient.setSoundOutputMute(txEnabled);
        }
    }

    private final PhoneStateListener phoneStateListener = new PhoneStateListener() {
            int myStatus = 0;

            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                User myself = users.get(ttclient.getMyUserID());
                if (myself == null) // event may have been generated before ttclient.disconnect() was called
                    return;

                switch (state) {
                case TelephonyManager.CALL_STATE_IDLE:
                    if (voxSuspended)
                        enableVoiceActivation(true);
                    else if (txSuspended)
                        enableVoiceTransmission(true);
                    setMute(permanentMuteState);
                    if ((myself != null) && ((myStatus & TeamTalkConstants.STATUSMODE_AWAY) == 0))
                        ttclient.doChangeStatus(myself.nStatusMode & ~TeamTalkConstants.STATUSMODE_AWAY, myself.szStatusMsg);
                    inPhoneCall = false;
                    scheduleReconnectBluetoothScoAfterCall();
                    break;
                case TelephonyManager.CALL_STATE_RINGING:
                    inPhoneCall = true;
                    if (!isMute()) {
                        ttclient.setSoundOutputMute(true);
                        currentMuteState = true;
                    }
                    if (isVoiceActivationEnabled()) {
                        voxSuspended = true;
                        enableVoiceActivation(false);
                    }
                    else if (isVoiceTransmissionEnabled()) {
                        txSuspended = true;
                        enableVoiceTransmission(false);
                    }
                    myStatus = myself.nStatusMode;
                    if ((myStatus & TeamTalkConstants.STATUSMODE_AWAY) == 0)
                        ttclient.doChangeStatus(myStatus | TeamTalkConstants.STATUSMODE_AWAY, myself.szStatusMsg);
                    break;
                default:
                    break;
                }
            }
        };

    public void enablePhoneCallReaction() {
        txSuspended = false;
        voxSuspended = false;
        inPhoneCall = false;
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        listeningPhoneStateChanges = true;
    }

    public void disablePhoneCallReaction() {
        if (listeningPhoneStateChanges) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            listeningPhoneStateChanges = false;
        }
        txSuspended = false;
        voxSuspended = false;
        inPhoneCall = false;
    }

    public boolean isInPhoneCall() {
        return inPhoneCall;
    }

    public void watchBluetoothHeadset() {
        if (bluetoothHeadsetHelper.start()) {
            if (bluetoothHeadsetHelper.isHeadsetConnected())
                bluetoothHeadsetHelper.scoAudioConnect();
            bluetoothHeadsetHelper.registerHeadsetConnectionListener(this);
            bluetoothHeadsetHelper.registerScoAudioConnectionListener(this);
        }
    }

    public void unwatchBluetoothHeadset() {
        reconnectHandler.removeCallbacks(reconnectBluetoothScoAfterCall);
        bluetoothHeadsetHelper.unregisterScoAudioConnectionListener(this);
        bluetoothHeadsetHelper.unregisterHeadsetConnectionListener(this);
        bluetoothHeadsetHelper.stop();
    }

    /** After a phone call ends, Bluetooth returns to A2DP and SCO is released. Schedule re-connecting SCO
     * so that the headset microphone works again when the user returns to TeamTalk. */
    private void scheduleReconnectBluetoothScoAfterCall() {
        reconnectHandler.removeCallbacks(reconnectBluetoothScoAfterCall);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (!prefs.getBoolean(Preferences.PREF_SOUNDSYSTEM_BLUETOOTH_HEADSET, false))
            return;
        if (bluetoothHeadsetHelper == null || !bluetoothHeadsetHelper.isStarted())
            return;
        reconnectHandler.postDelayed(reconnectBluetoothScoAfterCall, BLUETOOTH_SCO_RECONNECT_DELAY_MS);
    }

    private void reconnectBluetoothScoAfterCallRun() {
        if (bluetoothHeadsetHelper == null || !bluetoothHeadsetHelper.isStarted())
            return;
        if (bluetoothHeadsetHelper.isHeadsetConnected() && !bluetoothHeadsetHelper.isOnHeadsetSco())
            bluetoothHeadsetHelper.scoAudioConnect();
    }

    /** When "use bluetooth headset microphone" is on and headset SCO is active, use VOICECOM
     * so that input is routed to the Bluetooth headset mic. */
	private int getPreferredSoundInputDeviceId() {
		return shouldUseBluetoothVoiceCom()
				? SoundDeviceConstants.TT_SOUNDDEVICE_ID_OPENSLES_VOICECOM
				: SoundDeviceConstants.TT_SOUNDDEVICE_ID_OPENSLES_DEFAULT;
	}

	private boolean shouldUseBluetoothVoiceCom() {
		if (bluetoothHeadsetHelper == null || !bluetoothHeadsetHelper.isStarted()) {
			return false;
		}

		SharedPreferences prefs =
				PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

		if (!prefs.getBoolean(Preferences.PREF_SOUNDSYSTEM_BLUETOOTH_HEADSET, false)) {
			return false;
		}

		return bluetoothHeadsetHelper.isHeadsetConnected()
				&& bluetoothHeadsetHelper.isOnHeadsetSco();
	}

    /** Re-initialize sound input with the preferred device (e.g. after SCO connect/disconnect). */
    private void reinitSoundInputDevice() {
        if (ttclient == null) return;
        boolean tx = (ttclient.getFlags() & ClientFlag.CLIENT_TX_VOICE) != 0;
        boolean vox = (ttclient.getFlags() & (ClientFlag.CLIENT_SNDINPUT_VOICEACTIVATED | ClientFlag.CLIENT_SNDINPUT_VOICEACTIVE)) != 0;
        if (!tx && !vox) return;
        ttclient.closeSoundInputDevice();
        int indevid = getPreferredSoundInputDeviceId();
        if (!ttclient.initSoundInputDevice(indevid)) return;
        if (tx) ttclient.enableVoiceTransmission(true);
        if (vox) ttclient.enableVoiceActivation(true);
    }

    private boolean isRecording = false;
    private java.io.File currentRecordingFile = null;

    public boolean isRecording() {
        return isRecording;
    }

    public java.io.File getCurrentRecordingFile() {
        return currentRecordingFile;
    }

    public void startRecording() {
        if (isRecording) return;
        if (mychannel == null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        // Determine recording directory
        String customPath = prefs.getString(Preferences.PREF_RECORDING_PATH, "");
        java.io.File dir;
        if (!customPath.isEmpty()) {
            dir = new java.io.File(customPath);
        } else {
            dir = new java.io.File(getExternalFilesDir(null), "Recordings");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Determine format and extension
        String format = prefs.getString(Preferences.PREF_RECORDING_FORMAT, "wav");
        int audioFormat;
        String extension;
        switch (format) {
            case "mp3":
                String bitrate = prefs.getString(Preferences.PREF_RECORDING_MP3_BITRATE, "128");
                switch (bitrate) {
                    case "16": audioFormat = dk.bearware.AudioFileFormat.AFF_MP3_16KBIT_FORMAT; break;
                    case "32": audioFormat = dk.bearware.AudioFileFormat.AFF_MP3_32KBIT_FORMAT; break;
                    case "64": audioFormat = dk.bearware.AudioFileFormat.AFF_MP3_64KBIT_FORMAT; break;
                    case "256": audioFormat = dk.bearware.AudioFileFormat.AFF_MP3_256KBIT_FORMAT; break;
                    case "320": audioFormat = dk.bearware.AudioFileFormat.AFF_MP3_320KBIT_FORMAT; break;
                    default: audioFormat = dk.bearware.AudioFileFormat.AFF_MP3_128KBIT_FORMAT; break;
                }
                extension = ".mp3";
                break;
            case "codec":
                audioFormat = dk.bearware.AudioFileFormat.AFF_CHANNELCODEC_FORMAT;
                extension = ".ogg";
                break;
            default:
                audioFormat = dk.bearware.AudioFileFormat.AFF_WAVE_FORMAT;
                extension = ".wav";
                break;
        }

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US);
        String name = mychannel.szName.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + sdf.format(new java.util.Date()) + extension;
        java.io.File file = new java.io.File(dir, name);
        currentRecordingFile = file;

        isRecording = ttclient.startRecordingMuxedAudioFile(mychannel.audiocodec, file.getAbsolutePath(), audioFormat);
        if (isRecording) {
            Log.d("bearware", "Recording started: " + file.getAbsolutePath());
            Toast.makeText(getApplicationContext(), getString(R.string.recording_started, file.getName()), Toast.LENGTH_SHORT).show();
        } else {
            Log.e("bearware", "Failed to start recording");
            Toast.makeText(getApplicationContext(), R.string.recording_start_failed, Toast.LENGTH_SHORT).show();
            currentRecordingFile = null;
        }
    }

    public java.io.File stopRecording() {
        if (!isRecording) return null;
        ttclient.stopRecordingMuxedAudioFile();
        isRecording = false;
        java.io.File recordedFile = currentRecordingFile;
        currentRecordingFile = null;
        Log.d("bearware", "Recording stopped");
        Toast.makeText(getApplicationContext(), R.string.recording_stopped, Toast.LENGTH_SHORT).show();
        return recordedFile;
    }

    public boolean shouldShowRecordingDialog() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
            .getBoolean(Preferences.PREF_RECORDING_SHOW_DIALOG, true);
    }

    private void setMyChannel(Channel chan) {
        this.mychannel = chan;

        setupAudioPreprocessor();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (chan != null) {
            if (prefs.getBoolean("auto_record_conversations", false)) {
                startRecording();
            }
        } else {
            stopRecording();
        }
    }

    public TeamTalkBase getTTInstance() {
        return ttclient;
    }

    public TeamTalkEventHandler getEventHandler() { return mEventHandler; }

    public ServerEntry getServerEntry() {
        return ttserver;
    }

    // set TT server which service should connect to
    public void setServerEntry(ServerEntry entry) {
        ttserver = entry;
    }

    // set channel which service should join
    public void setJoinChannel(Channel channel) {
        joinchannel = channel;
    }

    public void setOnVoiceTransmissionToggleListener(OnVoiceTransmissionToggleListener listener) {
        onVoiceTransmissionToggleListener = listener;
    }

    public boolean getCurrentMuteState() {
        return currentMuteState;
    }

    public boolean isMute() {
        return ((ttclient.getFlags() & ClientFlag.CLIENT_SNDOUTPUT_MUTE) != 0);
    }

    public boolean isVoiceTransmissionEnabled() {
        return (ttclient.getFlags() & ClientFlag.CLIENT_TX_VOICE) != 0;
    }

    public boolean isVoiceTransmitting() {
        final int voiceActivationMask = ClientFlag.CLIENT_SNDINPUT_VOICEACTIVATED | ClientFlag.CLIENT_SNDINPUT_VOICEACTIVE;
        int flags = ttclient.getFlags();
        return ((flags & ClientFlag.CLIENT_TX_VOICE) != 0) ||
            ((flags & voiceActivationMask) == voiceActivationMask);
    }

    public boolean isVoiceActivationEnabled() {
        return (ttclient.getFlags() & (ClientFlag.CLIENT_SNDINPUT_VOICEACTIVATED | ClientFlag.CLIENT_SNDINPUT_VOICEACTIVE)) != 0;
    }

    public void setMute(boolean state) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        permanentMuteState = state;
        currentMuteState = state;
        if ((isMute() != permanentMuteState) &&
            !(prefs.getBoolean(Preferences.PREF_SOUNDSYSTEM_MUTE_ON_TRANSMISSION, false) && isVoiceTransmitting()))
            ttclient.setSoundOutputMute(permanentMuteState);
        updateFloatingWindow();
    }

    public void enableVoiceTransmission(boolean enable) {
        String inputSource = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("audio_input_source", "mic");
        boolean useInternal = "internal".equals(inputSource) || "mixed".equals(inputSource);
        if (enable) {
            txSuspended = false;
            voxSuspended = false;
            if (useInternal) {
                ttclient.enableVoiceTransmission(true);
                startInternalAudioCapture();
            } else {
                int indevid = getPreferredSoundInputDeviceId();
                if (((ttclient.getFlags() & ClientFlag.CLIENT_SNDINPUT_READY) != 0) || ttclient.initSoundInputDevice(indevid))
                    ttclient.enableVoiceTransmission(true);
            }
        }
        else {
            if (useInternal) {
                stopInternalAudioCapture();
            }
            ttclient.enableVoiceTransmission(false);
            ttclient.closeSoundInputDevice();
        }
        adjustMuteOnTx(enable);
        updateFloatingWindow();
    }

    // Media projection and internal audio recording variables
    private android.media.projection.MediaProjection mediaProjection;
    private android.media.AudioRecord internalAudioRecord;
    private android.media.AudioRecord micAudioRecord;
    private Thread internalAudioThread;
    private boolean isInternalAudioRunning = false;
    private static int mediaProjectionResultCode = 0;
    private static Intent mediaProjectionData = null;

    private static void mixPcm(byte[] buffer1, byte[] buffer2, byte[] outBuffer, int length) {
        for (int i = 0; i < length; i += 2) {
            short s1 = (short) ((buffer1[i] & 0xFF) | (buffer1[i + 1] << 8));
            short s2 = (short) ((buffer2[i] & 0xFF) | (buffer2[i + 1] << 8));
            int mixed = s1 + s2;
            if (mixed > 32767) mixed = 32767;
            else if (mixed < -32768) mixed = -32768;
            outBuffer[i] = (byte) (mixed & 0xFF);
            outBuffer[i + 1] = (byte) ((mixed >> 8) & 0xFF);
        }
    }

    public void setMediaProjectionData(int resultCode, Intent data) {
        mediaProjectionResultCode = resultCode;
        mediaProjectionData = data;
    }

    public static boolean hasMediaProjectionData() {
        return mediaProjectionData != null;
    }

    private void startInternalAudioCapture() {
        if (isInternalAudioRunning) return;
        if (mediaProjectionData == null) {
            Log.e("bearware", "No media projection data available");
            return;
        }

        isInternalAudioRunning = true;
        internalAudioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.media.projection.MediaProjectionManager projectionManager = 
                    (android.media.projection.MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (projectionManager == null) return;
                
                if (mediaProjection == null) {
                    try {
                        mediaProjection = projectionManager.getMediaProjection(mediaProjectionResultCode, (Intent) mediaProjectionData.clone());
                    } catch (Exception e) {
                        Log.e("bearware", "Failed to get MediaProjection", e);
                        isInternalAudioRunning = false;
                        return;
                    }
                }

                if (mediaProjection == null) {
                    isInternalAudioRunning = false;
                    return;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        android.media.AudioPlaybackCaptureConfiguration config = new android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                            .build();

                        int sampleRate = 48000;
                        int channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO;
                        int audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT;
                        int minBufSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
                        if (minBufSize < 3840) minBufSize = 3840;

                        internalAudioRecord = new android.media.AudioRecord.Builder()
                            .setAudioFormat(new android.media.AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build())
                            .setAudioPlaybackCaptureConfig(config)
                            .setBufferSizeInBytes(minBufSize)
                            .build();

                        internalAudioRecord.startRecording();

                        boolean mixMic = "mixed".equals(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("audio_input_source", "mic"));
                        if (mixMic) {
                            int micMinBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
                            if (micMinBuf < 3840) micMinBuf = 3840;
                            try {
                                micAudioRecord = new android.media.AudioRecord(
                                    android.media.MediaRecorder.AudioSource.MIC,
                                    sampleRate,
                                    channelConfig,
                                    audioFormat,
                                    micMinBuf
                                );
                                micAudioRecord.startRecording();
                            } catch (Exception e) {
                                Log.e("bearware", "Failed to start microphone for mixed mode", e);
                                mixMic = false;
                            }
                        }

                        int blockSize = 960;
                        byte[] buffer = new byte[blockSize * 2];
                        byte[] micBuffer = mixMic ? new byte[blockSize * 2] : null;
                        byte[] mixedBuffer = mixMic ? new byte[blockSize * 2] : null;
                        int sampleIndex = 0;

                        while (isInternalAudioRunning) {
                            byte[] finalBuffer = null;
                            int finalRead = 0;

                            if (mixMic && micAudioRecord != null) {
                                int micRead = micAudioRecord.read(micBuffer, 0, micBuffer.length);
                                if (micRead > 0) {
                                    finalBuffer = micBuffer;
                                    finalRead = micRead;
                                    
                                    int intRead = internalAudioRecord.read(buffer, 0, micRead, android.media.AudioRecord.READ_NON_BLOCKING);
                                    if (intRead > 0) {
                                        int mixLen = Math.min(micRead, intRead);
                                        mixPcm(buffer, micBuffer, mixedBuffer, mixLen);
                                        finalBuffer = mixedBuffer;
                                        finalRead = mixLen;
                                    }
                                }
                            } else {
                                int read = internalAudioRecord.read(buffer, 0, buffer.length);
                                if (read > 0) {
                                    finalBuffer = buffer;
                                    finalRead = read;
                                }
                            }

                            if (finalRead > 0 && finalBuffer != null) {
                                dk.bearware.AudioBlock block = new dk.bearware.AudioBlock();
                                block.nStreamID = 0;
                                block.nSampleRate = sampleRate;
                                block.nChannels = 1;
                                block.lpRawAudio = new byte[finalRead];
                                System.arraycopy(finalBuffer, 0, block.lpRawAudio, 0, finalRead);
                                block.nSamples = finalRead / 2;
                                block.uSampleIndex = sampleIndex;
                                block.uStreamTypes = dk.bearware.StreamType.STREAMTYPE_VOICE;

                                ttclient.insertAudioBlock(block);
                                sampleIndex += block.nSamples;
                            } else {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        }
                    } catch (SecurityException | IllegalArgumentException e) {
                        Log.e("bearware", "Error recording internal audio", e);
                    } finally {
                        stopInternalAudioCapture();
                    }
                } else {
                    Log.e("bearware", "Internal audio capture requires Android 10+");
                    isInternalAudioRunning = false;
                }
            }
        }, "InternalAudioCaptureThread");
        internalAudioThread.start();
    }

    private void stopInternalAudioCapture() {
        isInternalAudioRunning = false;
        if (internalAudioRecord != null) {
            try {
                if (internalAudioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    internalAudioRecord.stop();
                }
            } catch (Exception e) {}
            internalAudioRecord.release();
            internalAudioRecord = null;
        }
        if (micAudioRecord != null) {
            try {
                if (micAudioRecord.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    micAudioRecord.stop();
                }
            } catch (Exception e) {}
            micAudioRecord.release();
            micAudioRecord = null;
        }
        // We DO NOT stop mediaProjection here because the Intent can only be used once.
        // Stopping it would break subsequent internal audio capture sessions until the app is restarted
        // or a new permission prompt is requested.
        // if (mediaProjection != null) {
        //     try {
        //         mediaProjection.stop();
        //     } catch (Exception e) {}
        //     mediaProjection = null;
        // }
        if (internalAudioThread != null) {
            internalAudioThread.interrupt();
            internalAudioThread = null;
        }
    }

    // Media streaming state
    private String currentStreamPath = "";
    private boolean isStreamingMedia = false;
    private int localPlaybackId = 0;
    private dk.bearware.MediaFileInfo currentMediaFileInfo = null;
    private dk.bearware.MediaFilePlayback currentPlayback = null;

    public String getCurrentStreamPath() { return currentStreamPath; }
    public boolean isStreamingMedia() { return isStreamingMedia; }
    public int getLocalPlaybackId() { return localPlaybackId; }
    public dk.bearware.MediaFileInfo getCurrentMediaFileInfo() { return currentMediaFileInfo; }
    public dk.bearware.MediaFilePlayback getCurrentPlayback() { return currentPlayback; }

    public void setCurrentStreamPath(String path) { this.currentStreamPath = path; }
    public void setStreamingMedia(boolean streaming) { this.isStreamingMedia = streaming; }
    public void setLocalPlaybackId(int id) { this.localPlaybackId = id; }
    public void setCurrentMediaFileInfo(dk.bearware.MediaFileInfo info) { this.currentMediaFileInfo = info; }
    public void setCurrentPlayback(dk.bearware.MediaFilePlayback playback) { this.currentPlayback = playback; }

    public void enableVoiceActivation(boolean enable) {
        String inputSource = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("audio_input_source", "mic");
        boolean useInternal = "internal".equals(inputSource) || "mixed".equals(inputSource);
        if (enable) {
            txSuspended = false;
            voxSuspended = false;
            if (useInternal) {
                ttclient.enableVoiceActivation(true);
                startInternalAudioCapture();
            } else {
                int indevid = getPreferredSoundInputDeviceId();
                if (((ttclient.getFlags() & ClientFlag.CLIENT_SNDINPUT_READY) != 0) || ttclient.initSoundInputDevice(indevid))
                    ttclient.enableVoiceActivation(true);
            }
        }
        else {
            if (useInternal) {
                stopInternalAudioCapture();
            }
            ttclient.enableVoiceActivation(false);
            ttclient.closeSoundInputDevice();
        }
        adjustMuteOnTx(enable);
        updateFloatingWindow();
    }

    public void syncToUserCache(User user) {
        String cacheid = UserCached.getCacheID(user);
        if (!cacheid.isEmpty()) {
            usercache.put(cacheid, new UserCached(user));
        }
    }

    public void syncToUserCache() {
        // sync user settings to cache
        for (Map.Entry<Integer, User> entry : users.entrySet()) {
            syncToUserCache(entry.getValue());
        }
    }

    public void syncFromUserCache(User user) {
        String cacheid = UserCached.getCacheID(user);
        if (cacheid.isEmpty())
            return;

        UserCached userprop = usercache.get(cacheid);
        if (userprop != null) {
            userprop.sync(ttclient, user);
        }
    }

    public boolean reconnect() {
        if(ttserver == null || ttclient == null)
            return false;

        syncToUserCache();

        ttclient.disconnect();

        if (!setupEncryption())
            return false;
        
        if(!ttclient.connect(ttserver.ipaddr, ttserver.tcpport,
                             ttserver.udpport, 0, 0, ttserver.encrypted)) {
            ttclient.disconnect();
            return false;
        }
        
        return true;
    }

    private boolean setupEncryption() {
        if (!this.ttserver.encrypted)
            return true;

        File outputDir = getBaseContext().getCacheDir();
        try {
            File cacertfile = File.createTempFile("cacert", "pem", outputDir);
            File clientcertfile = File.createTempFile("clientcert", "pem", outputDir);
            File clientkeyfile = File.createTempFile("clientkey", "pem", outputDir);
            try (FileWriter cawriter = new FileWriter(cacertfile);
                 FileWriter certwriter = new FileWriter(clientcertfile);
                 FileWriter keywriter = new FileWriter(clientkeyfile)) {
                cawriter.write(this.ttserver.cacert);
                certwriter.write(this.ttserver.clientcert);
                keywriter.write(this.ttserver.clientcertkey);
            }
            EncryptionContext context = new EncryptionContext();
            if (!this.ttserver.cacert.isEmpty())
                context.szCAFile = cacertfile.getAbsolutePath();
            if (!this.ttserver.clientcert.isEmpty())
                context.szCertificateFile = clientcertfile.getAbsolutePath();
            if (!this.ttserver.clientcertkey.isEmpty())
                context.szPrivateKeyFile = clientkeyfile.getAbsolutePath();
            context.bVerifyPeer = ttserver.verifypeer;
            if (!context.bVerifyPeer) {
                context.nVerifyDepth = -1;
            }
            return ttclient.setEncryptionContext(context);
        } catch (IOException e) {
            return false;
        }
    }

    public int HISTORY_CHATLOG_MSG_MAX = 100;
    public int HISTORY_USER_MSG_MAX = 100;

    public Vector<MyTextMessage> getUserTextMsgs(int userid) {
        Vector<MyTextMessage> msgs;
        if(usertxtmsgs.get(userid) == null) {
            msgs = new Vector<>();
            usertxtmsgs.put(userid, msgs);
        }
        msgs = usertxtmsgs.get(userid);
        if(msgs.size() > HISTORY_USER_MSG_MAX)
            msgs.remove(0);
        return msgs;
    }

    public Vector<MyTextMessage> getChatLogTextMsgs() {
        if(chatlogtxtmsgs.size()>HISTORY_CHATLOG_MSG_MAX)
            chatlogtxtmsgs.remove(0);

        return chatlogtxtmsgs;
    }

    void createEventTimer() {
        eventTimer = new CountDownTimer(10000, 100) {
            private boolean prevVoiceTransmissionState = isVoiceTransmissionEnabled();
            private boolean prevVoiceActivationState = isVoiceActivationEnabled();

            public void onTick(long millisUntilFinished) {
                int events = 0;
                while(events++ < 50 && mEventHandler.processEvent(ttclient, 0));
                boolean newVoiceTransmissionState = isVoiceTransmissionEnabled();
                boolean newVoiceActivationState = isVoiceActivationEnabled();
                if (onVoiceTransmissionToggleListener != null) {
                    if (newVoiceTransmissionState != prevVoiceTransmissionState) {
                        onVoiceTransmissionToggleListener.onVoiceTransmissionToggle(newVoiceTransmissionState, txSuspended);
                        prevVoiceTransmissionState = newVoiceTransmissionState;
                    }
                    if (newVoiceActivationState != prevVoiceActivationState) {
                        onVoiceTransmissionToggleListener.onVoiceActivationToggle(newVoiceActivationState, voxSuspended);
                        prevVoiceActivationState = newVoiceActivationState;
                    }
                }
            }

            public void onFinish() {
                start();
            }
        };
        eventTimer.start();
    }

    void createReconnectTimer(long delayMsec) {
        
        reconnectHandler.removeCallbacks(reconnectTimer);
        reconnectHandler.postDelayed(reconnectTimer, delayMsec);
    }

    private void login() {

        String nickname = ttserver.nickname;
        if (TextUtils.isEmpty(nickname)) {
            nickname = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(Preferences.PREF_GENERAL_NICKNAME, "");
        }

        String clientName = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .getString(Preferences.PREF_GENERAL_CLIENTNAME, "");
        if (TextUtils.isEmpty(clientName)) {
            clientName = AppInfo.APPNAME_SHORT;
        }
        int loginCmdId = ttclient.doLoginEx(nickname, ttserver.username, ttserver.password, clientName);
        if(loginCmdId<0) {
            Toast.makeText(this, getResources().getString(R.string.text_cmderr_login),
                    Toast.LENGTH_LONG).show();
        }
        else {
            activecmds.put(loginCmdId, CmdComplete.CMD_COMPLETE_LOGIN);
        }

        MyTextMessage msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
                getResources().getString(R.string.text_con_success));
        getChatLogTextMsgs().add(msg);
    }

    private void loginComplete() {
        if (joinchannel == null) {

            // join channel specified in ServerEntry
            if (ttserver.channel != null && !ttserver.channel.isEmpty()) {
                int chanid = ttclient.getChannelIDFromPath(ttserver.channel);
                joinchannel = getChannels().get(chanid);
                if (joinchannel != null) {
                    joinchannel.szPassword = ttserver.chanpasswd;
                }
            }

            // if last channel is not set then join initial channel
            UserAccount useraccount = new UserAccount();
            ttclient.getMyUserAccount(useraccount);
            if (joinchannel == null && !useraccount.szInitChannel.isEmpty()) {
                int chanid = ttclient.getChannelIDFromPath(useraccount.szInitChannel);
                joinchannel = getChannels().get(chanid);
            }

            // otherwise join root channel
            boolean joinroot = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean(Preferences.PREF_JOIN_ROOT_CHAN, true);
            if (joinroot && joinchannel == null) {
                joinchannel = getChannels().get(ttclient.getRootChannelID());
                if (joinchannel != null) {
                    joinchannel.szPassword = ttserver.chanpasswd;
                }
            }
        }

        if(joinchannel != null) {
            int cmdid = ttclient.doJoinChannel(joinchannel);
            activecmds.put(cmdid, CmdComplete.CMD_COMPLETE_JOIN);
        }
    }

    private void setupAudioPreprocessor() {
        if (mychannel != null && mychannel.audiocfg.bEnableAGC) {
            AudioPreprocessor ap = new AudioPreprocessor(AudioPreprocessorType.WEBRTC_AUDIOPREPROCESSOR, true);
            ap.webrtc.gaincontroller2.bEnable = true;
            float gainPercent = mychannel.audiocfg.nGainLevel / (float)TeamTalkConstants.CHANNEL_AUDIOCONFIG_MAX;
            ap.webrtc.gaincontroller2.fixeddigital.fGainDB = WebRTCConstants.WEBRTC_GAINCONTROLLER2_FIXEDGAIN_MAX * gainPercent;
            ttclient.setSoundInputPreprocess(ap);
            ttclient.setSoundInputGainLevel(SoundLevel.SOUND_GAIN_DEFAULT);
        }
        else {
            ttclient.setSoundInputPreprocess(new AudioPreprocessor());
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            int gain = prefs.getInt(Preferences.PREF_SOUNDSYSTEM_MICROPHONEGAIN, SoundLevel.SOUND_GAIN_DEFAULT);
            ttclient.setSoundInputGainLevel(gain);
        }
    }

    @Override
    public void onConnectSuccess() {
        
        assert (ttserver != null);

        if (Utils.isWebLogin(ttserver.username)) {
            new WebLoginAccessToken().execute();
        }
        else {
            login();
        }
        updateFloatingWindow();
    }

    @Override
    public void onEncryptionError(int opensslErrorNo, ClientErrorMsg errmsg) {
        Log.i(TAG, "Encryption error: " + errmsg.szErrorMsg + " connecting to " + ttserver.ipaddr + ":" + ttserver.tcpport);
        Toast.makeText(this, getResources().getString(R.string.text_con_encryption_error, errmsg.szErrorMsg),
                       Toast.LENGTH_LONG).show();
    }    

    @Override
    public void onConnectFailed() {
        
        Log.i(TAG, "Failed to connect " + ttserver.ipaddr + ":" + ttserver.tcpport);
        
        Toast.makeText(this, getResources().getString(R.string.text_con_failed),
                       Toast.LENGTH_SHORT).show();
        
        createReconnectTimer(5000);
        updateFloatingWindow();
    }

    @Override
    public void onConnectionLost() {
        
        Log.i(TAG, "Connection lost to " + ttserver.ipaddr + ":" + ttserver.tcpport);
        
        activecmds.clear();
        
        Toast.makeText(this, getResources().getString(R.string.text_con_lost),
                       Toast.LENGTH_LONG).show();

        createReconnectTimer(5000);
        
        MyTextMessage msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_ERROR,
            getResources().getString(R.string.text_con_lost));
        getChatLogTextMsgs().add(msg);
        updateFloatingWindow();
    }

    @Override
    public void onCmdError(int cmdId, ClientErrorMsg errmsg) {
        
        Utils.notifyError(this, errmsg);
        
        if(activecmds.get(cmdId) == CmdComplete.CMD_COMPLETE_LOGIN) {
            
            //don't try to reconnect if we get a server login error
            reconnectHandler.removeCallbacks(reconnectTimer);
        }
    }

    @Override
    public void onCmdSuccess(int cmdId) {
        if(activecmds.get(cmdId) == CmdComplete.CMD_COMPLETE_LOGIN) {
            
            //stop reconnect timer since we're now connected and logged in
            reconnectHandler.removeCallbacks(reconnectTimer);

            //update status bar widget
            displayNotification(true);
        }
    }

    @Override
    public void onCmdProcessing(int cmdId, boolean complete) {

        if (!complete) {
            switch (activecmds.get(cmdId, CMD_COMPLETE_NONE)) {
                case CMD_COMPLETE_LOGIN:
                    //new users and channels will be posted for new login, so delete old ones
                    users.clear();
                    remoteFiles.clear();
                    fileTransfers.clear();
                    channels.clear();
                    break;
            }
        }
        else {
            switch (activecmds.get(cmdId, CMD_COMPLETE_NONE)) {
                case CMD_COMPLETE_LOGIN : {
                    loginComplete();
                }
                break;
            }
            activecmds.delete(cmdId);
        }
    }

    @Override
    public void onCmdMyselfLoggedIn(int my_userid, UserAccount useraccount) {
        MyTextMessage msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
            getResources().getString(R.string.text_cmd_loggedin));
        getChatLogTextMsgs().add(msg);

        // check whether to switch to female icon and put status message per server
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        int statusmode = TeamTalkConstants.STATUSMODE_AVAILABLE;
        String statusmsg = ttserver.statusmsg;

        if (TextUtils.isEmpty(statusmsg))
        {
            statusmsg = prefs.getString(Preferences.PREF_GENERAL_STATUSMSG, "");
        }

        if (prefs.getBoolean(Preferences.PREF_GENERAL_GENDER, false))
            statusmode |= TeamTalkConstants.STATUSMODE_FEMALE;

        ttclient.doChangeStatus(statusmode, statusmsg);
        updateFloatingWindow();
    }

    @Override
    public void onCmdMyselfKickedFromChannel() {
    }

    @Override
    public void onCmdMyselfKickedFromChannel(User kicker) {
        users.put(kicker.nUserID, kicker);
    }

    @Override
    public void onCmdUserLoggedIn(User user) {
        users.put(user.nUserID, user);

        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        int def_unsub = Subscription.SUBSCRIBE_NONE;
        if(!pref.getBoolean(Preferences.PREF_SUB_TEXTMESSAGE, true))
            def_unsub |= Subscription.SUBSCRIBE_USER_MSG;
        if(!pref.getBoolean(Preferences.PREF_SUB_CHANMESSAGE, true))
            def_unsub |= Subscription.SUBSCRIBE_CHANNEL_MSG;
        if(!pref.getBoolean(Preferences.PREF_SUB_BCAST_MESSAGES, true))
            def_unsub |= Subscription.SUBSCRIBE_BROADCAST_MSG;
        if(!pref.getBoolean(Preferences.PREF_SUB_VOICE, true))
            def_unsub |= Subscription.SUBSCRIBE_VOICE;
        if(!pref.getBoolean(Preferences.PREF_SUB_VIDCAP, true))
            def_unsub |= Subscription.SUBSCRIBE_VIDEOCAPTURE;
        if(!pref.getBoolean(Preferences.PREF_SUB_DESKTOP, true))
            def_unsub |= Subscription.SUBSCRIBE_DESKTOP;
        if(!pref.getBoolean(Preferences.PREF_SUB_MEDIAFILE, true))
            def_unsub |= Subscription.SUBSCRIBE_MEDIAFILE;

        if((user.uLocalSubscriptions & def_unsub) != 0) {
            int cmdid = ttclient.doUnsubscribe(user.nUserID, def_unsub);
            if(cmdid > 0)
                activecmds.put(cmdid, CmdComplete.CMD_COMPLETE_UNSUBSCRIBE);
        }

        String name = Utils.getDisplayName(getBaseContext(), user);
        MyTextMessage msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
            name + " " + getResources().getString(R.string.text_cmd_userloggedin));
        getChatLogTextMsgs().add(msg);

        // sync weblogin user settings from cache
        syncFromUserCache(user);
    }

    @Override
    public void onCmdUserLoggedOut(User user) {
        users.remove(user.nUserID);

        String name = Utils.getDisplayName(getBaseContext(), user);
        MyTextMessage msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
            name + " " + getResources().getString(R.string.text_cmd_userloggedout));
        getChatLogTextMsgs().add(msg);

        // sync user settings to cache
        syncToUserCache(user);
    }

    @Override
    public void onCmdUserUpdate(User user) {
        users.put(user.nUserID, user);
        updateFloatingWindow();
    }

    @Override
    public void onCmdUserJoinedChannel(User user) {
        users.put(user.nUserID, user);        
        if (ttserver.rememberLastChannel && (user.nUserID == ttclient.getMyUserID()) && (joinchannel != null)) {
            ttserver.channel = ttclient.getChannelPath(joinchannel.nChannelID);
            ttserver.chanpasswd = joinchannel.szPassword;
        }
        
        if(user.nUserID == ttclient.getMyUserID()) {
            //myself joined channel
            setMyChannel(getChannels().get(user.nChannelID));
            displayNotification(true);

            MyTextMessage msg;
            if (mychannel.nParentID == 0) {
                msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
                    getResources().getString(R.string.text_cmd_joinroot));
            }
            else {
                msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
                    getResources().getString(R.string.text_cmd_joinchan) + " " + mychannel.szName);
            }
            getChatLogTextMsgs().add(msg);
        }
        else if (mychannel != null && mychannel.nChannelID == user.nChannelID) {
            //other user joined current channel
            
            String name = Utils.getDisplayName(getBaseContext(), user);
            MyTextMessage msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
                name + " " + getResources().getString(R.string.text_cmd_userjoinchan));
            getChatLogTextMsgs().add(msg);
        }
        
        // set media file volume
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        int mf_volume = pref.getInt(Preferences.PREF_SOUNDSYSTEM_MEDIAFILE_VOLUME, 50);
        mf_volume = Utils.refVolume(mf_volume);
        ttclient.setUserVolume(user.nUserID, StreamType.STREAMTYPE_MEDIAFILE_AUDIO, mf_volume);
        ttclient.pumpMessage(ClientEvent.CLIENTEVENT_USER_STATECHANGE, user.nUserID);

        // sync user settings from cache
        if (!UserCached.getCacheID(user).isEmpty()) {
            UserAccount myaccount = new UserAccount();
            if (ttclient.getMyUserAccount(myaccount) && (myaccount.uUserRights & UserRight.USERRIGHT_VIEW_ALL_USERS) == UserRight.USERRIGHT_NONE) {
                // sync weblogin user settings from cache
                syncFromUserCache(user);
            }
        }
    }

    @Override
    public void onCmdUserLeftChannel(int channelid, User user) {
        users.put(user.nUserID, user);
        
        if (mychannel != null && mychannel.nChannelID == channelid) {
            
            Channel chan = getChannels().get(channelid);
            MyTextMessage msg;
            if(user.nUserID == ttclient.getMyUserID()) {
                // myself left channel
                if(chan.nParentID == 0) {
                    msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
                        getResources().getString(R.string.text_cmd_leftroot));
                }
                else {
                    msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
                        getResources().getString(R.string.text_cmd_leftchan) + " " + chan.szName);
                }
            }
            else {
                // other user left current channel
                String name = Utils.getDisplayName(getBaseContext(), user);
                msg = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_INFO,
                    name + " " + getResources().getString(R.string.text_cmd_userleftchan));                
            }
            getChatLogTextMsgs().add(msg);
        }
        
        if(user.nUserID == ttclient.getMyUserID()) {
            setMyChannel(null);
        }

        // sync user settings to cache
        String cacheid = UserCached.getCacheID(user);
        if (!cacheid.isEmpty()) {
            UserAccount myaccount = new UserAccount();
            if (ttclient.getMyUserAccount(myaccount) && (myaccount.uUserRights & UserRight.USERRIGHT_VIEW_ALL_USERS) == UserRight.USERRIGHT_NONE) {
                syncToUserCache(user);
            }
        }
    }

    @Override
    public void onCmdUserTextMessage(TextMessage textmessage) {

        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if(pref.getBoolean(Preferences.PREF_ANTISPAM_ENABLED, false) && !antispam_blocked.isEmpty()) {
            if(antispam_blocked.contains(textmessage.nFromUserID))
                return;
        }

        if(pref.getBoolean(Preferences.PREF_ANTISPAM_ENABLED, false)) {
            int uid = textmessage.nFromUserID;
            Integer cnt = antispam_user_counts.get(uid);
            if(cnt == null) cnt = 0;
            antispam_user_counts.put(uid, cnt + 1);

            long now = SystemClock.elapsedRealtime();
            if(now - antispam_window_start > 1000) {
                antispam_count = 0;
                antispam_window_start = now;
                antispam_user_counts.clear();
            }
            antispam_count++;

            int limit;
            try {
                limit = Integer.parseInt(pref.getString(Preferences.PREF_ANTISPAM_MSG_LIMIT, "20"));
            }
            catch(NumberFormatException e) {
                limit = 20;
            }

            if(cnt + 1 >= 5 || antispam_count > limit) {
                antispam_triggered = true;
                antispam_blocked.add(uid);
                Log.e(TAG, "ANTISPAM TRIGGERED uid=" + uid + " msgs_from_user=" + (cnt+1) + " total=" + antispam_count + " limit=" + limit);

                if(pref.getBoolean(Preferences.PREF_ANTISPAM_UNSUB_ALL, true)) {
                    int all = Subscription.SUBSCRIBE_USER_MSG
                        | Subscription.SUBSCRIBE_CHANNEL_MSG
                        | Subscription.SUBSCRIBE_BROADCAST_MSG
                        | Subscription.SUBSCRIBE_CUSTOM_MSG
                        | Subscription.SUBSCRIBE_VOICE
                        | Subscription.SUBSCRIBE_VIDEOCAPTURE
                        | Subscription.SUBSCRIBE_DESKTOP
                        | Subscription.SUBSCRIBE_DESKTOPINPUT
                        | Subscription.SUBSCRIBE_MEDIAFILE
                        | Subscription.SUBSCRIBE_INTERCEPT_USER_MSG
                        | Subscription.SUBSCRIBE_INTERCEPT_CHANNEL_MSG
                        | Subscription.SUBSCRIBE_INTERCEPT_CUSTOM_MSG
                        | Subscription.SUBSCRIBE_INTERCEPT_VOICE
                        | Subscription.SUBSCRIBE_INTERCEPT_VIDEOCAPTURE
                        | Subscription.SUBSCRIBE_INTERCEPT_DESKTOP
                        | Subscription.SUBSCRIBE_INTERCEPT_MEDIAFILE;
                    ttclient.doUnsubscribe(uid, all);
                }
                else {
                    int text_flags = Subscription.SUBSCRIBE_USER_MSG
                        | Subscription.SUBSCRIBE_CHANNEL_MSG
                        | Subscription.SUBSCRIBE_BROADCAST_MSG
                        | Subscription.SUBSCRIBE_CUSTOM_MSG;
                    ttclient.doUnsubscribe(uid, text_flags);
                }

                String msg = String.format(getString(R.string.antispam_triggered), limit);
                MyTextMessage log = MyTextMessage.createLogMsg(MyTextMessage.MSGTYPE_LOG_ERROR, msg);
                getChatLogTextMsgs().add(log);

                return;
            }
        }

        User user = getUsers().get(textmessage.nFromUserID);
        MyTextMessage newmsg = new MyTextMessage(textmessage, 
                                                 user == null? "" : Utils.getDisplayName(getBaseContext(), user));

        switch(textmessage.nMsgType) {
            case TextMsgType.MSGTYPE_USER : {
                getUserTextMsgs(textmessage.nFromUserID).add(newmsg);
                break;
            }
            case TextMsgType.MSGTYPE_BROADCAST : {
                getChatLogTextMsgs().add(newmsg);
                break;
            }
            case TextMsgType.MSGTYPE_CHANNEL : {
                getChatLogTextMsgs().add(newmsg);
                break;
            }
        }
    }

    @Override
    public void onCmdChannelNew(Channel channel) {
        channels.put(channel.nChannelID, channel);
    }

    @Override
    public void onCmdChannelUpdate(Channel channel) {
        channels.put(channel.nChannelID, channel);

        if (mychannel != null && mychannel.nChannelID == channel.nChannelID) {
            setMyChannel(channel);
        }
    }

    @Override
    public void onCmdChannelRemove(Channel channel) {
        channels.remove(channel.nChannelID);
    }

    @Override
    public void onCmdServerUpdate(ServerProperties serverproperties) {
        MyTextMessage msg;
        msg = MyTextMessage.createUserDefMsg(MyTextMessage.MSGTYPE_SERVERPROP,
                                             serverproperties);
        getChatLogTextMsgs().add(msg);
    }

    @Override
    public void onCmdFileNew(RemoteFile remotefile) {
        remoteFiles.put(remotefile.nFileID, remotefile);
    }

    @Override
    public void onCmdFileRemove(RemoteFile remotefile) {
        remoteFiles.remove(remotefile.nFileID);
    }
    
    @Override
    public void onUserStateChange(User user) {
        users.put(user.nUserID, user);
        updateFloatingWindow();
    }


    @Override
    public void onVoiceActivation(boolean bVoiceActive) {
        adjustMuteOnTx(bVoiceActive);
        updateFloatingWindow();
    }

    @Override
    public void onFileTransfer(FileTransfer transfer) {
        if (transfer.nStatus == FileTransferStatus.FILETRANSFER_ACTIVE) {
            fileTransfers.put(transfer.nTransferID, transfer);
        }
        else {
            fileTransfers.remove(transfer.nTransferID);
        }
    }

    @Override
    public void onStreamMediaFile(MediaFileInfo mediafileinfo) {
        User myself = users.get(ttclient.getMyUserID());
        if (myself == null) // event may have been generated before ttclient.disconnect() was called
            return;

        switch (mediafileinfo.nStatus) {
            case MediaFileStatus.MFS_STARTED :
                ttclient.doChangeStatus(myself.nStatusMode | TeamTalkConstants.STATUSMODE_STREAM_MEDIAFILE, myself.szStatusMsg);
                break;
            case MediaFileStatus.MFS_ERROR:
            case MediaFileStatus.MFS_ABORTED:
            case MediaFileStatus.MFS_FINISHED:
                ttclient.doChangeStatus(myself.nStatusMode & ~TeamTalkConstants.STATUSMODE_STREAM_MEDIAFILE, myself.szStatusMsg);
                break;
            case MediaFileStatus.MFS_PLAYING :
            case MediaFileStatus.MFS_PAUSED :
            case MediaFileStatus.MFS_CLOSED :
            default :
                break;
        }
    }

    @Override
    public void onHeadsetConnected() {
        bluetoothHeadsetHelper.scoAudioConnect();
    }

    @Override
    public void onHeadsetDisconnected() {
        bluetoothHeadsetHelper.scoAudioDisconnect();
    }

    @Override
    public void onScoAudioConnected() {
        reinitSoundInputDevice();
    }

    @Override
    public void onScoAudioDisconnected() {
        reinitSoundInputDevice();
    }


    class WebLoginAccessToken extends AsyncTask<Void, Void, Void> {

        String username = "", token = "", accesstoken = "";

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

            this.username = prefs.getString(Preferences.PREF_GENERAL_BEARWARE_USERNAME, "");
            this.token = prefs.getString(Preferences.PREF_GENERAL_BEARWARE_TOKEN, "");

            ServerProperties srvprop = new ServerProperties();
            if (ttclient.getServerProperties(srvprop))
                accesstoken = srvprop.szAccessToken;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);

            if (username.length() > 0) {
                ttserver.username = this.username;
                login();
            }
            else {
                Toast.makeText(TeamTalkService.this, getResources().getString(R.string.text_weblogin_authfailure),
                        Toast.LENGTH_LONG).show();
            }
        }

        @Override
        protected Void doInBackground(Void... voids) {
            String xml = Utils.getURL(AppInfo.getBearWareAccessTokenUrl(getBaseContext(),
                    this.username, this.token, accesstoken));
            Log.d(AppInfo.TAG, xml);

            try {
                InputSource src = new InputSource(new StringReader(xml));
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document document = db.parse(src);
                XPathFactory factory = XPathFactory.newInstance();
                XPath xPath = factory.newXPath();

                this.username = (String)xPath.evaluate("/teamtalk/bearware/username", document, XPathConstants.STRING);

            } catch (XPathExpressionException e) {
                Log.e(AppInfo.TAG, "XPath failed: " + e);
            } catch (ParserConfigurationException e) {
                Log.e(AppInfo.TAG, "Parser cfg failed: " + e);
            } catch (IOException e) {
                Log.e(AppInfo.TAG, "XML IOException: " + e);
            } catch (SAXException e) {
                Log.e(AppInfo.TAG, "XML SAXException: " + e);
            }

            return null;
        }
    }
}
