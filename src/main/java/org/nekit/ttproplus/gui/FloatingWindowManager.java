package org.nekit.ttproplus.gui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import dk.bearware.ClientFlag;
import dk.bearware.ClientStatistics;
import dk.bearware.User;
import org.nekit.ttproplus.R;
import org.nekit.ttproplus.backend.TeamTalkService;
import org.nekit.ttproplus.data.Preferences;
import org.nekit.ttproplus.data.MyTextMessage;
import org.nekit.ttproplus.data.TextMessageAdapter;
import dk.bearware.TextMsgType;
import dk.bearware.events.ClientEventListener;

public class FloatingWindowManager {

    private final Context context;
    private final TeamTalkService service;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;

    private View floatingView;
    private WindowManager.LayoutParams params;
    private boolean isShowing = false;

    private ImageView dragHandle;
    private ImageButton btnVoice;
    private ImageButton btnMute;
    private ImageButton btnChat;
    private ImageButton btnChannels;
    private TextView txtPing;

    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isShowing) {
                updateUIInternal();
                updateHandler.postDelayed(this, 1000);
            }
        }
    };

    public FloatingWindowManager(TeamTalkService service) {
        this.service = service;
        this.context = service.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        initView();
    }

    private void initView() {
        floatingView = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null);

        dragHandle = floatingView.findViewById(R.id.drag_handle);
        btnVoice = floatingView.findViewById(R.id.btn_voice);
        btnMute = floatingView.findViewById(R.id.btn_mute);
        btnChat = floatingView.findViewById(R.id.btn_chat);
        btnChannels = floatingView.findViewById(R.id.btn_channels);
        txtPing = floatingView.findViewById(R.id.txt_ping);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 200;

        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        if (isShowing) {
                            windowManager.updateViewLayout(floatingView, params);
                        }
                        return true;
                }
                return false;
            }
        });

        btnVoice.setOnClickListener(v -> {
            boolean tx = !service.isVoiceTransmissionEnabled();
            if (service.isVoiceActivationEnabled()) {
                service.enableVoiceActivation(false);
            }
            service.enableVoiceTransmission(tx);
            updateUI();
        });

        btnMute.setOnClickListener(v -> {
            service.setMute(!service.getCurrentMuteState());
            updateUI();
        });

        btnChat.setOnClickListener(v -> {
            showChatTypeChoiceDialog();
        });

        btnChannels.setOnClickListener(v -> {
            showChannelSelectionDialog();
        });
    }

    public void checkAndShow() {
        boolean enabled = prefs.getBoolean(Preferences.PREF_BG_MGMT_ENABLED, false);
        if (!enabled) {
            hide();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            hide();
            return;
        }

        if (!isShowing) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (!isShowing) {
                        windowManager.addView(floatingView, params);
                        isShowing = true;
                        updateHandler.post(updateRunnable);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } else {
            updateUI();
        }
    }

    public void hide() {
        if (isShowing) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (isShowing) {
                        windowManager.removeView(floatingView);
                        isShowing = false;
                        updateHandler.removeCallbacks(updateRunnable);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }

    public void hideIfAppOpened() {
        if (prefs.getBoolean(Preferences.PREF_BG_MGMT_CLOSE_ON_APP_OPEN, false)) {
            hide();
        }
    }

    public void updateUI() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isShowing) {
                updateUIInternal();
            }
        });
    }

    private void updateUIInternal() {
        btnVoice.setVisibility(prefs.getBoolean(Preferences.PREF_BG_MGMT_SHOW_VOICE, true) ? View.VISIBLE : View.GONE);
        btnMute.setVisibility(prefs.getBoolean(Preferences.PREF_BG_MGMT_SHOW_MUTE, true) ? View.VISIBLE : View.GONE);
        btnChat.setVisibility(prefs.getBoolean(Preferences.PREF_BG_MGMT_SHOW_CHAT, true) ? View.VISIBLE : View.GONE);
        btnChannels.setVisibility(prefs.getBoolean(Preferences.PREF_BG_MGMT_SHOW_CHANNELS, true) ? View.VISIBLE : View.GONE);
        txtPing.setVisibility(prefs.getBoolean(Preferences.PREF_BG_MGMT_SHOW_PING, true) ? View.VISIBLE : View.GONE);

        boolean isTransmitting = service.isVoiceTransmitting();
        btnVoice.setImageResource(isTransmitting ? R.drawable.mic_green : R.drawable.microphone);
        btnVoice.setContentDescription(context.getString(isTransmitting ? 
                R.string.desc_voice_transmitting : R.string.desc_voice_silent));

        boolean isMuted = service.getCurrentMuteState();
        btnMute.setImageResource(isMuted ? R.drawable.mute_blue : R.drawable.speaker_blue);
        btnMute.setContentDescription(context.getString(isMuted ? 
                R.string.desc_sound_muted : R.string.desc_sound_active));

        int flags = service.getTTInstance().getFlags();
        String stateStr;
        int stateColor;

        if ((flags & ClientFlag.CLIENT_AUTHORIZED) == ClientFlag.CLIENT_AUTHORIZED) {
            stateStr = context.getString(R.string.stat_online);
            stateColor = Color.GREEN;
            ClientStatistics stats = new ClientStatistics();
            if (service.getTTInstance().getClientStatistics(stats) && stats.nUdpPingTimeMs >= 0) {
                stateStr += " (" + stats.nUdpPingTimeMs + "ms)";
            }
        } else if ((flags & ClientFlag.CLIENT_CONNECTING) == ClientFlag.CLIENT_CONNECTING) {
            stateStr = context.getString(R.string.stat_connecting);
            stateColor = Color.YELLOW;
        } else {
            stateStr = context.getString(R.string.stat_offline);
            stateColor = Color.RED;
        }

        txtPing.setText(stateStr);
        txtPing.setTextColor(stateColor);
    }

    private void showChatTypeChoiceDialog() {
        dk.bearware.UserAccount myAccount = new dk.bearware.UserAccount();
        boolean isAdmin = service.getTTInstance().getMyUserAccount(myAccount) &&
                (myAccount.uUserType & dk.bearware.UserType.USERTYPE_ADMIN) != 0;

        Context themedContext = new ContextThemeWrapper(context, R.style.AppTheme);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle(R.string.chat_dialog_title);

        final List<String> options = new ArrayList<>();
        options.add(context.getString(R.string.chat_option_global));
        options.add(context.getString(R.string.chat_option_private));
        if (isAdmin) {
            options.add(context.getString(R.string.chat_option_broadcast));
        }

        builder.setItems(options.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) {
                openChatDialog("global", 0);
            } else if (which == 1) {
                showOnlineUsersSelectionDialog();
            } else if (which == 2) {
                openChatDialog("broadcast", 0);
            }
        });

        AlertDialog dialog = builder.create();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } else {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    private void showOnlineUsersSelectionDialog() {
        final List<User> onlineUsers = new ArrayList<>();
        int myUserId = service.getTTInstance().getMyUserID();
        for (User u : service.getUsers().values()) {
            if (u.nUserID != myUserId) {
                onlineUsers.add(u);
            }
        }

        if (onlineUsers.isEmpty()) {
            Toast.makeText(context, R.string.chat_no_online_users, Toast.LENGTH_SHORT).show();
            return;
        }

        Collections.sort(onlineUsers, new Comparator<User>() {
            @Override
            public int compare(User u1, User u2) {
                String name1 = Utils.getDisplayName(context, u1);
                String name2 = Utils.getDisplayName(context, u2);
                return name1.compareToIgnoreCase(name2);
            }
        });

        String[] userNames = new String[onlineUsers.size()];
        for (int i = 0; i < onlineUsers.size(); i++) {
            userNames[i] = Utils.getDisplayName(context, onlineUsers.get(i));
        }

        Context themedContext = new ContextThemeWrapper(context, R.style.AppTheme);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle(R.string.chat_select_user);
        builder.setItems(userNames, (dialog, which) -> {
            User u = onlineUsers.get(which);
            openChatDialog("private", u.nUserID);
        });

        AlertDialog dialog = builder.create();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } else {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    private void openChatDialog(final String type, final int userId) {
        Context themedContext = new ContextThemeWrapper(context, R.style.AppTheme);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);

        String titleStr;
        if ("global".equals(type)) {
            titleStr = context.getString(R.string.chat_option_global);
        } else if ("broadcast".equals(type)) {
            titleStr = context.getString(R.string.chat_option_broadcast);
        } else {
            String title = context.getString(R.string.chat_option_private);
            User user = service.getUsers().get(userId);
            if (user != null) {
                titleStr = title + " - " + Utils.getDisplayName(context, user);
            } else {
                titleStr = title;
            }
        }
        builder.setTitle(titleStr);

        View chatView = LayoutInflater.from(themedContext).inflate(R.layout.activity_simple_chat, null);
        builder.setView(chatView);

        final dk.bearware.TeamTalkBase ttclient = service.getTTInstance();
        final TextMessageAdapter adapter;
        if ("global".equals(type) || "broadcast".equals(type)) {
            adapter = new TextMessageAdapter(themedContext, null,
                    service.getChatLogTextMsgs(),
                    ttclient.getMyUserID());
        } else {
            adapter = new TextMessageAdapter(themedContext, null,
                    service.getUserTextMsgs(userId),
                    ttclient.getMyUserID());
        }

        ListView lv = chatView.findViewById(R.id.simple_chat_listview);
        lv.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        lv.setAdapter(adapter);
        adapter.setListView(lv);

        final EditText send_msg = chatView.findViewById(R.id.simple_chat_edittext);
        Button send_btn = chatView.findViewById(R.id.simple_chat_sendbtn);

        send_btn.setOnClickListener(v -> {
            String msgText = send_msg.getText().toString();
            if (msgText.isEmpty())
                return;

            User myself = service.getUsers().get(ttclient.getMyUserID());
            String name = Utils.getDisplayName(context, myself);
            
            MyTextMessage textmsg = new MyTextMessage(myself == null ? "" : name);
            textmsg.nFromUserID = ttclient.getMyUserID();
            textmsg.szMessage = msgText;

            if ("global".equals(type)) {
                textmsg.nMsgType = TextMsgType.MSGTYPE_CHANNEL;
                textmsg.nChannelID = ttclient.getMyChannelID();

                int cmdid = 0;
                for (MyTextMessage m : textmsg.split()) {
                    cmdid = ttclient.doTextMessage(m);
                }

                if (cmdid > 0) {
                    send_msg.setText("");
                    adapter.notifyDataSetChanged();
                } else {
                    Toast.makeText(context, R.string.text_con_cmderr, Toast.LENGTH_LONG).show();
                }
            } else if ("broadcast".equals(type)) {
                textmsg.nMsgType = TextMsgType.MSGTYPE_BROADCAST;

                int cmdid = 0;
                for (MyTextMessage m : textmsg.split()) {
                    cmdid = ttclient.doTextMessage(m);
                }

                if (cmdid > 0) {
                    send_msg.setText("");
                    adapter.notifyDataSetChanged();
                } else {
                    Toast.makeText(context, R.string.text_con_cmderr, Toast.LENGTH_LONG).show();
                }
            } else {
                textmsg.nMsgType = TextMsgType.MSGTYPE_USER;
                textmsg.nChannelID = 0;
                textmsg.nToUserID = userId;

                boolean sent = true;
                for (MyTextMessage m : textmsg.split()) {
                    sent = sent && ttclient.doTextMessage(m) > 0;
                    service.getUserTextMsgs(userId).add(m);
                }
                if (sent) {
                    send_msg.setText("");
                    adapter.notifyDataSetChanged();
                } else {
                    Toast.makeText(context, R.string.err_send_text_message, Toast.LENGTH_LONG).show();
                }
            }
        });

        final ClientEventListener.OnCmdUserTextMessageListener messageListener = new ClientEventListener.OnCmdUserTextMessageListener() {
            @Override
            public void onCmdUserTextMessage(dk.bearware.TextMessage textmessage) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if ("global".equals(type) || "broadcast".equals(type)) {
                        if (textmessage.nMsgType == TextMsgType.MSGTYPE_CHANNEL || textmessage.nMsgType == TextMsgType.MSGTYPE_BROADCAST) {
                            adapter.notifyDataSetChanged();
                        }
                    } else {
                        if (textmessage.nFromUserID == userId && textmessage.nMsgType == TextMsgType.MSGTYPE_USER) {
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            }
        };

        service.getEventHandler().registerOnCmdUserTextMessage(messageListener, true);

        builder.setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> dialogInterface.dismiss());

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(dialogInterface -> {
            service.getEventHandler().registerOnCmdUserTextMessage(messageListener, false);
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } else {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
        }

        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        dialog.show();
    }

    private String getChannelPath(dk.bearware.Channel channel, java.util.Map<Integer, dk.bearware.Channel> allChannels) {
        if (channel.nParentID == 0) {
            String rootName = context.getString(R.string.root_channel);
            return rootName;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        dk.bearware.Channel curr = channel;
        while (curr != null && curr.nChannelID != 0) {
            parts.add(curr.szName);
            curr = allChannels.get(curr.nParentID);
        }
        java.util.Collections.reverse(parts);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(p);
        }
        return sb.toString();
    }

    private void showChannelSelectionDialog() {
        java.util.Map<Integer, dk.bearware.Channel> allChannels = service.getChannels();
        if (allChannels == null || allChannels.isEmpty()) {
            Toast.makeText(context, R.string.err_connection, Toast.LENGTH_SHORT).show();
            return;
        }

        final dk.bearware.TeamTalkBase ttclient = service.getTTInstance();
        final java.util.List<dk.bearware.Channel> list = new java.util.ArrayList<>(allChannels.values());
        java.util.Collections.sort(list, new java.util.Comparator<dk.bearware.Channel>() {
            @Override
            public int compare(dk.bearware.Channel c1, dk.bearware.Channel c2) {
                return getChannelPath(c1, allChannels).compareToIgnoreCase(getChannelPath(c2, allChannels));
            }
        });

        java.util.List<String> names = new java.util.ArrayList<>();
        for (dk.bearware.Channel c : list) {
            names.add(getChannelPath(c, allChannels));
        }

        Context themedContext = new ContextThemeWrapper(context, R.style.AppTheme);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle(R.string.desc_btn_channels);
        builder.setItems(names.toArray(new String[0]), (dialog, which) -> {
            dk.bearware.Channel selected = list.get(which);
            if (selected.bPassword) {
                AlertDialog.Builder passBuilder = new AlertDialog.Builder(themedContext);
                passBuilder.setTitle(R.string.chanpswlab);
                final EditText input = new EditText(themedContext);
                input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                passBuilder.setView(input);
                passBuilder.setPositiveButton(android.R.string.ok, (d, w) -> {
                    String pass = input.getText().toString();
                    ttclient.doJoinChannelByID(selected.nChannelID, pass);
                });
                passBuilder.setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss());
                
                AlertDialog passDialog = passBuilder.create();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    passDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                } else {
                    passDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
                }
                passDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                passDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                passDialog.show();
            } else {
                ttclient.doJoinChannelByID(selected.nChannelID, "");
            }
        });
        builder.setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> dialogInterface.dismiss());

        AlertDialog dialog = builder.create();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } else {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }
}
