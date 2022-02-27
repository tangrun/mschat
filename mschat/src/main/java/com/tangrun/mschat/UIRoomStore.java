package com.tangrun.mschat;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.*;

import com.example.mschat.R;
import com.example.mschat.databinding.ItemActionBinding;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.mediasoup.droid.lib.RoomClient;
import org.mediasoup.droid.lib.RoomOptions;
import org.mediasoup.droid.lib.lv.ChangedMutableLiveData;
import org.mediasoup.droid.lib.lv.RoomStore;
import org.mediasoup.droid.lib.model.Buddy;
import org.mediasoup.droid.lib.model.Buddys;
import org.mediasoup.droid.lib.model.RoomState;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.annotations.NonNull;
import io.reactivex.observers.DisposableObserver;

/**
 * @author RainTang
 * @description:
 * @date :2022/2/17 10:51
 */
public class UIRoomStore {

    private static final String TAG = "MS_UIRoomStore";

    private String notificationChannelId = null;
    private final int notificationId = 1;
    private final String notificationTag = "UIRoomStore";
    private static final String NOTIFICATION_CHANNEL_ID = "MSCall";
    private static final String NOTIFICATION_CHANNEL_NAME = "音视频通话";

    private final Context context;
    private final AudioManager audioManager;
    private NotificationManagerCompat notificationManagerCompat;
    private LifecycleOwner lifecycleOwner;
    private final RoomStore roomStore;
    private final RoomClient roomClient;
    private final RoomOptions roomOptions;

    DefaultButtonAction Action_MicDisabledAction;
    DefaultButtonAction Action_CamDisabledAction;
    DefaultButtonAction Action_CamNotIsFrontAction;
    DefaultButtonAction Action_SpeakerOnAction;
    DefaultButtonAction Action_HangupAction;
    DefaultButtonAction Action_JoinAction;

    /**
     * 自己的状态由自己本地维护
     */
    public ChangedMutableLiveData<Buddy.ConversationState> conversationState = new ChangedMutableLiveData<>(Buddy.ConversationState.New);
    public ChangedMutableLiveData<RoomClient.ConnectionState> connectionState = new ChangedMutableLiveData<>(RoomClient.ConnectionState.NEW);
    public ChangedMutableLiveData<RoomState.State> micEnabledState = new ChangedMutableLiveData<>(RoomState.State.Off);
    public ChangedMutableLiveData<RoomState.State> camEnabledState = new ChangedMutableLiveData<>(RoomState.State.Off);
    public ChangedMutableLiveData<RoomState.State> speakerOnState = new ChangedMutableLiveData<>(RoomState.State.Off);
    public ChangedMutableLiveData<RoomState.State> CamIsFrontState = new ChangedMutableLiveData<>(RoomState.State.Off);
    public ChangedMutableLiveData<Long> callTime = new ChangedMutableLiveData<>(null);
    public ChangedMutableLiveData<Boolean> finished = new ChangedMutableLiveData<>();
    public ChangedMutableLiveData<Boolean> showActivity = new ChangedMutableLiveData<>(true);
    public ChangedMutableLiveData<List<BuddyItemViewModel>> buddys = new ChangedMutableLiveData<>(new ArrayList<>());

    private Date callStartTime,callEndTime;

    private int connectedCount;
    private int joinedCount;

    /**
     * 0 单人
     * 1 多人
     * 默认0 单人
     */
    public int roomType;
    /**
     * 连接上自动打开扬声器
     */
    public boolean firstSpeakerOn = false;
    /**
     * 邀请通话者
     */
    public boolean owner = false;
    /**
     * 最开始 自动join 一般是房主
     * 默认 false
     */
    public boolean firstConnectedAutoJoin = false;
    /**
     * 首次连接自动创建音频流
     * 默认 false
     */
    public boolean firstJoinedAutoProduceAudio = false;
    /**
     * 首次连接自动创建视频流
     * 默认 false
     */
    public boolean firstJoinedAutoProduceVideo = false;
    /**
     * 这个只在自动创建音视频流时使用
     * 实际的上传 接收流设置
     *
     * @see RoomOptions
     */
    public boolean audioOnly = true;
    /**
     * 通话已结束标记 0没挂断 1挂断 2超时
     */
    private int callEndType = 0;


    /**
     * 连接状态改变监听
     */
    Observer<RoomClient.ConnectionState> localConnectionStateChangedLogic = connectionState1 -> {
        Log.d(TAG, "ConnectionState changed: " + connectionState1);
        if (connectionState1 == RoomClient.ConnectionState.CONNECTED) {
            boolean needJoin = false;

            // 首次连接上 自动join
            if (firstConnectedAutoJoin && connectedCount == 0) {
                needJoin = true;
            }

            // 扬声器
            if (firstSpeakerOn && connectedCount == 0) {
                switchSpeakerphoneEnable(true);
            }

            // 重连时自动join
            if (joinedCount > 0) {
                needJoin = true;
            }

            if (needJoin) {
                getRoomClient().join();
            }

            // 不是邀请者 且还没join过
            if (!owner && joinedCount == 0) {
                conversationState.setValue(Buddy.ConversationState.Invited);
            }
            connectedCount++;
        } else if (connectionState1 == RoomClient.ConnectionState.JOINED) {
            // 网络中断重连时 join后 重连transport
            // 用重连transport无效 因为socket重连后是新的对象 之前的数据都没了 所以只能根据自己本地的状态判断去在重连上后主动传流
            if (joinedCount > 0) {
                if (camEnabledState.getValue() == RoomState.State.On) {
                    getRoomClient().enableCam();
                }
                if (micEnabledState.getValue() == RoomState.State.On) {
                    getRoomClient().enableMic();
                }
            }

            //接听电话时 开始计时
            if (callStartTime == null && !owner) {
                startCallTime();
            }

            // 首次join后 自动发送流
            if ((firstJoinedAutoProduceAudio || firstJoinedAutoProduceVideo) && joinedCount == 0) {
                // 因为弹出窗口需要页面的content 所以用切换到前台时才开始调用方法 所以页面需要设置content 这里用的lifeOwner强转
                ProcessLifecycleOwner.get().getLifecycle().addObserver(new LifecycleEventObserver() {
                    @Override
                    public void onStateChanged(@androidx.annotation.NonNull @NotNull LifecycleOwner source, @androidx.annotation.NonNull @NotNull Lifecycle.Event event) {
                        if (event == Lifecycle.Event.ON_RESUME) {
                            source.getLifecycle().removeObserver(this);
                            if (lifecycleOwner == null) return;
                            lifecycleOwner.getLifecycle().addObserver(new LifecycleEventObserver() {
                                @Override
                                public void onStateChanged(@androidx.annotation.NonNull @NotNull LifecycleOwner source, @androidx.annotation.NonNull @NotNull Lifecycle.Event event) {
                                    if (event == Lifecycle.Event.ON_RESUME) {
                                        source.getLifecycle().removeObserver(this);
                                        Context context = null;
                                        if (source instanceof Context) {
                                            context = (Context) source;
                                        } else if (source instanceof Fragment) {
                                            context = ((Fragment) source).getContext();
                                        }
                                        if (context != null) {
                                            if (firstJoinedAutoProduceVideo && camEnabledState.getValue() == RoomState.State.Off)
                                                switchCamEnable(context);
                                            if (firstJoinedAutoProduceAudio && micEnabledState.getValue() == RoomState.State.Off)
                                                switchMicEnable(context);
                                        }
                                    }
                                }
                            });
                        }
                    }
                });

            }

            conversationState.setValue(Buddy.ConversationState.Joined);

            joinedCount++;
        } else if (connectionState1 == RoomClient.ConnectionState.CLOSED) {
            if (callEndType == 1) {
                if (owner)
                    conversationState.setValue(Buddy.ConversationState.Left);
                else {
                    if (joinedCount > 0) {
                        conversationState.setValue(Buddy.ConversationState.Left);
                    } else {
                        conversationState.setValue(Buddy.ConversationState.InviteReject);
                    }
                }
            } else if (callEndType == 2) {
                conversationState.setValue(Buddy.ConversationState.InviteTimeout);
            }
            if (callEndType != 0)
                release();
        }
    };

    //region 监听数据变化并经过转化设置到本store成员上

    /**
     * 房间状态监听 连接状态 摄像头 麦克风切换等
     */
    Observer<RoomState> roomStateObserver = new Observer<RoomState>() {
        @Override
        public void onChanged(RoomState roomState) {
            connectionState.applySet(roomState.getConnectionState());
            micEnabledState.applySet(roomState.getMicrophoneEnabledState());
            camEnabledState.applySet(roomState.getCameraEnabledState());
            CamIsFrontState.applySet(roomState.getCameraIsFrontDeviceState());
        }
    };

    /**
     * 用户内置状态改变监听 主要是connect conversation state
     * todo 但是后面加入的东西有点多 声音变化 流的质量变化都会走用户内部状态更新 就比较频繁 后期可以考虑把频繁更新的单独分出来
     */
    Observer<Buddy> buddyObserver = new Observer<Buddy>() {
        @Override
        public void onChanged(Buddy buddy) {
            // 计时逻辑
            // 开始
            if (callStartTime == null && !buddy.isProducer() && joinedCount > 0) {
                if (owner) {
                    // 第一个人进来就算开始通话
                    if (buddy.getConnectionState() == Buddy.ConnectionState.Online && buddy.getConversationState() == Buddy.ConversationState.Joined) {
                        startCallTime();
                    }
                } else {
                    // 自己接听就算开始通话
                    if (buddy.getConversationState() == Buddy.ConversationState.Joined) {
                        startCallTime();
                    }
                }
            }
            // 结束
            if (callStartTime != null && callEndType == 0 && joinedCount > 0) {
                if (buddys.getValue() != null && buddys.getValue().size() == 1) {
                    hangup();
                }
            }
            for (BuddyItemViewModel model : buddys.getValue()) {
                if (model.buddy.getId().equals(buddy.getId())) {
                    model.onChanged(buddy);
                    break;
                }
            }
        }
    };

    /**
     * 用户数量变化监听
     */
    Observer<Buddys> buddysObserver = new Observer<Buddys>() {
        @Override
        public void onChanged(Buddys buddys) {
            List<Buddy> allPeers = buddys.getAllPeers();
            List<BuddyItemViewModel> itemViewModels = new ArrayList<>();
            for (Buddy peer : allPeers) {
                // todo 因为用户内部还有一个监听 防止频繁变化一个人注册多个监听 所以公用一个observer 再通过id取item model  或许有更好的解决办法
                peer.getBuddyLiveData().removeObserver(buddyObserver);
                peer.getBuddyLiveData().observeForever(buddyObserver);
                BuddyItemViewModel model = new BuddyItemViewModel(peer, getRoomClient());
                itemViewModels.add(model);
            }
            UIRoomStore.this.buddys.applySet(itemViewModels);
        }
    };
    //endregion


    public UIRoomStore(Context context, RoomOptions roomOptions) {
        this.context = context;
        this.roomClient = new RoomClient(context, roomOptions);
        this.roomStore = roomClient.getStore();
        this.roomOptions = roomClient.getOptions();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        notificationManagerCompat = NotificationManagerCompat.from(context);
        init();
    }

    public void bindLifeOwner(LifecycleOwner owner) {
        lifecycleOwner = owner;
        camEnabledState.observe(lifecycleOwner, state -> Action_CamDisabledAction.setChecked(state == RoomState.State.Off));
        micEnabledState.observe(lifecycleOwner, state -> Action_MicDisabledAction.setChecked(state == RoomState.State.Off));
        CamIsFrontState.observe(lifecycleOwner, state -> Action_CamNotIsFrontAction.setChecked(state == RoomState.State.Off));
        speakerOnState.observe(lifecycleOwner, state -> Action_SpeakerOnAction.setChecked(state == RoomState.State.On));
    }

    private void init() {
        getRoomStore().getRoomState().observeForever(roomStateObserver);
        getRoomStore().getBuddys().observeForever(buddysObserver);
        connectionState.observeForever(localConnectionStateChangedLogic);
        showActivity.observeForever(new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    openCallActivity();
                } else {
                    openWindowService();
                }
            }
        });
        conversationState.observeForever(conversationState1 -> {
            if (conversationState1 == Buddy.ConversationState.New) {
                setNotification("等待对方接听");
            } else if (conversationState1 == Buddy.ConversationState.Invited) {
                setNotification("待接听");
            } else if (conversationState1 == Buddy.ConversationState.Joined) {
                setNotification("通话中...");
            } else {
                setNotification("通话已结束");
            }
        });
        Action_JoinAction = new DefaultButtonAction("", audioOnly ? R.drawable.selector_call_audio_answer : R.drawable.selector_call_video_answer) {
            @Override
            public void onClick(View v) {
                join();
            }
        };
        Action_HangupAction = new DefaultButtonAction("", R.drawable.selector_call_hangup) {
            @Override
            public void onClick(View v) {
                hangup();
                v.postDelayed(() -> {
                    finished.applySet(true);
                    MSManager.stopCall();
                }, 1000);
            }
        };
        Action_MicDisabledAction = new DefaultButtonAction("麦克风", R.drawable.ms_mic_disabled) {
            @Override
            public void onClick(View v) {
                switchMicEnable(v.getContext());
            }
        };
        Action_SpeakerOnAction = new DefaultButtonAction("免提", R.drawable.ms_speaker_on) {
            @Override
            public void onClick(View v) {
                switchSpeakerphoneEnable();
            }
        };
        Action_CamDisabledAction = new DefaultButtonAction("摄像头", R.drawable.ms_cam_disabled) {
            @Override
            public void onClick(View v) {
                switchCamEnable(v.getContext());
            }
        };
        Action_CamNotIsFrontAction = new DefaultButtonAction("切换摄像头", R.drawable.ms_cam_changed) {
            @Override
            public void onClick(View v) {
                switchCamDevice();
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setLightColor(0);
            channel.setSound(null, null);
            channel.setVibrationPattern(new long[]{});
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationChannelId = NOTIFICATION_CHANNEL_ID;
            NotificationManagerCompat.from(context).createNotificationChannel(channel);
        }
    }

    private void release() {
        getRoomStore().getRoomState().removeObserver(roomStateObserver);
        getRoomStore().getBuddys().removeObserver(buddysObserver);
        connectionState.removeObserver(localConnectionStateChangedLogic);
        stopCallTime();

    }


    //region 通话组件启动

    private Intent getCallActivityIntent() {
        return new Intent(context, RoomActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private Intent getCallServiceIntent() {
        return new Intent(context, CallWindowService.class);
    }

    public void openCallActivity() {
        context.startActivity(getCallActivityIntent());
    }

    private void openWindowService() {
        context.startService(getCallServiceIntent());
    }

    //endregion

    //region 通话时间计时器

    private DisposableObserver<Long> callTimeObserver;

    private void startCallTime() {
        if (callStartTime != null) return;
        callStartTime = new Date();
        callTimeObserver = new DisposableObserver<Long>() {
            @Override
            public void onNext(@NonNull Long aLong) {
                callTime.applyPost(System.currentTimeMillis() - callStartTime.getTime());
            }

            @Override
            public void onError(@NonNull Throwable e) {

            }

            @Override
            public void onComplete() {

            }
        };
        Observable.interval(0, 1, TimeUnit.SECONDS)
                .subscribe(callTimeObserver);
    }

    private void stopCallTime() {
        if (callEndTime !=null)return;
        if (callTimeObserver != null)
            callTimeObserver.dispose();
        callTimeObserver = null;
        callEndTime = new Date();
    }

    //endregion

    // region 功能操作暴露方法

    public void connect() {
        getRoomClient().connect();
    }

    public void join() {
        if (connectionState.getValue() == RoomClient.ConnectionState.CONNECTED)
            getRoomClient().join();
        else {
            toast("请稍等，连接中...");
        }
    }

    public void hangup() {
        stopCallTime();
        callEndType = 1;
        if (connectionState.getValue() == RoomClient.ConnectionState.JOINED
                || connectionState.getValue() == RoomClient.ConnectionState.CONNECTED)
            getRoomClient().hangup();
        else {
            getRoomClient().close();
        }
    }

    public void switchMicEnable(Context context) {
        if (!XXPermissions.isGranted(context, Permission.RECORD_AUDIO)) {
            showDialog(context, "权限请求", "通话中语音需要录音权限，请授予",
                    "取消", null,
                    "好的", new Runnable() {
                        @Override
                        public void run() {
                            XXPermissions.with(context).permission(Permission.RECORD_AUDIO).request((PermissionCallback) (all, never) -> {
                                if (all) {
                                    switchMicEnable(context);
                                } else {
                                    if (never) {
                                        showDialog(context, "权限请求", "麦克风权限已经被永久拒绝了，再次开启需要前往应用权限页面手动开启",
                                                "取消", null,
                                                "打开权限页面", () -> {
                                                    XXPermissions.startPermissionActivity(context, Permission.RECORD_AUDIO);
                                                });
                                    } else {
                                        showDialog(context, "权限请求", "没有麦克风权限将无法进行正常的语音通话，是否重新授予？",
                                                "取消", null,
                                                "是的", this);
                                    }
                                }
                            });
                        }
                    });
            return;
        }
        if (micEnabledState.getValue() == RoomState.State.Off)
            getRoomClient().enableMic();
        else if (micEnabledState.getValue() == RoomState.State.On)
            getRoomClient().disableMic();
    }

    public void switchCamEnable(Context context) {
        if (!XXPermissions.isGranted(context, Permission.CAMERA)) {
            showDialog(context, "权限请求", "通话中视频需要摄像头权限，请授予", "取消", null, "好的", new Runnable() {
                @Override
                public void run() {
                    XXPermissions.with(context).permission(Permission.CAMERA).request((PermissionCallback) (all, never) -> {
                        if (all) {
                            switchCamEnable(context);
                        } else {
                            if (never) {
                                showDialog(context, "权限请求", "摄像头权限已经被永久拒绝了，再次开启需要前往应用权限页面手动开启",
                                        "取消", null,
                                        "打开权限页面", () -> {
                                            XXPermissions.startPermissionActivity(context, Permission.CAMERA);
                                        });
                            } else {
                                showDialog(context, "权限请求", "没有摄像头权限将无法进行正常的视频通话，是否重新授予？",
                                        "取消", null,
                                        "是的", this);
                            }
                        }
                    });
                }
            });
            return;
        }
        if (camEnabledState.getValue() == RoomState.State.Off)
            getRoomClient().enableCam();
        else if (camEnabledState.getValue() == RoomState.State.On)
            getRoomClient().disableCam();
    }

    private void setSpeakerphoneOn(boolean isSpeakerphoneOn) {
        audioManager.setSpeakerphoneOn(isSpeakerphoneOn);
        audioManager.setMode(isSpeakerphoneOn ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
    }

    private void switchSpeakerphoneEnable(boolean enable) {
        setSpeakerphoneOn(enable);
        speakerOnState.setValue(enable ? RoomState.State.On : RoomState.State.Off);
    }

    public void switchSpeakerphoneEnable() {
        if (speakerOnState.getValue() == RoomState.State.On) {
            switchSpeakerphoneEnable(false);
        } else if (camEnabledState.getValue() == RoomState.State.Off) {
            switchSpeakerphoneEnable(true);
        }
    }

    public void switchCamDevice() {
        if (camEnabledState.getValue() != RoomState.State.On) return;
        getRoomClient().changeCam();
    }

    public void onMinimize(Activity activity) {
        if (!XXPermissions.isGranted(activity, Permission.SYSTEM_ALERT_WINDOW)) {
            showDialog(activity, "权限请求", "窗口显示需要开启悬浮窗权限，请授予",
                    "取消", null,
                    "好的", new Runnable() {
                        @Override
                        public void run() {
                            XXPermissions.with(activity).permission(Permission.SYSTEM_ALERT_WINDOW).request((PermissionCallback) (all, never) -> {
                                if (all) {
                                    onMinimize(activity);
                                } else {
                                    if (never) {
                                        showDialog(activity, "权限请求", "悬浮窗权限已经被永久拒绝了，再次开启需要前往应用权限页面手动开启",
                                                "取消", null,
                                                "打开权限页面", () -> {
                                                    XXPermissions.startPermissionActivity(activity, Permission.SYSTEM_ALERT_WINDOW);
                                                });
                                    } else {
                                        showDialog(activity, "权限请求", "没有悬浮窗权限将无法进行窗口显示，是否重新授予？",
                                                "取消", null,
                                                "是的", this);
                                    }
                                }
                            });
                        }
                    },
                    "关闭页面", activity::finish);
            return;
        }
        activity.finish();
    }

    public void onAddUserClick(Context context) {
        List<Buddy> allPeers = getRoomStore().getBuddys().getValue().getAllPeers();
        List<MSManager.User> list = new ArrayList<>();
        for (Buddy buddy : allPeers) {
            MSManager.User user = new MSManager.User();
            user.setId(buddy.getId());
            user.setAvatar(buddy.getAvatar());
            user.setDisplayName(buddy.getDisplayName());
            list.add(user);
        }
        AddUserHandler.start(context, list);
    }

    public void addUser(List<MSManager.User> list) {
        if (list == null || list.isEmpty()) return;
        connectionState.observeForever(new Observer<RoomClient.ConnectionState>() {
            @Override
            public void onChanged(RoomClient.ConnectionState state) {
                if (
                        state == RoomClient.ConnectionState.CONNECTED
                                || state == RoomClient.ConnectionState.JOINED
                ) {
                    connectionState.removeObserver(this);
                    JSONArray jsonArray = new JSONArray();
                    for (MSManager.User user : list) {
                        jsonArray.put(user.toJsonObj());
                    }
                    getRoomClient().addPeers(jsonArray);
                }
            }
        });
    }


    // endregion

    //region 封装UI方法

    private void cancelNotification() {
        notificationManagerCompat.cancel(notificationTag, notificationId);
    }

    private void setNotification(String content) {
        Notification notification = new NotificationCompat.Builder(context, notificationChannelId)
                .setOngoing(true)
                .setSmallIcon(context.getApplicationInfo().icon)
                .setContentIntent(PendingIntent.getActivity(context, 0, getCallActivityIntent(), PendingIntent.FLAG_UPDATE_CURRENT))
                .setContentText(content)
                .build();

        notificationManagerCompat
                .notify(notificationTag, notificationId, notification);
    }

    private void showDialog(Context context, String title, String msg,
                            String negativeText, Runnable negative,
                            String positiveText, Runnable positive,
                            String neutralText, Runnable neutral) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(title).setMessage(msg);
        if (negativeText != null) builder.setNegativeButton(negativeText, (dialog, which) -> {
            dialog.dismiss();
            if (negative != null) negative.run();
        });
        if (positiveText != null) builder.setPositiveButton(positiveText, (dialog, which) -> {
            dialog.dismiss();
            if (positive != null) positive.run();
        });
        if (neutralText != null) builder.setNeutralButton(neutralText, (dialog, which) -> {
            dialog.dismiss();
            if (neutral != null) neutral.run();
        });
        builder.setCancelable(false).show();
    }

    private void showDialog(Context context, String title, String msg,
                            String negativeText, Runnable negative,
                            String positiveText, Runnable positive) {
        showDialog(context, title, msg, negativeText, negative, positiveText, positive, null, null);
    }

    private void toast(String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }


    //endregion


    public RoomStore getRoomStore() {
        return roomStore;
    }

    public RoomClient getRoomClient() {
        return roomClient;
    }

    public RoomOptions getRoomOptions() {
        return roomOptions;
    }

    private interface PermissionCallback extends OnPermissionCallback {
        void onResult(boolean all, boolean never);

        @Override
        default void onGranted(List<String> permissions, boolean all) {
            if (all) onResult(true, false);
        }

        @Override
        default void onDenied(List<String> permissions, boolean never) {
            onResult(false, never);
        }
    }

    public static abstract class ButtonAction<V> implements View.OnClickListener {
        protected String name;
        protected int imgId;
        protected boolean checked;
        protected V v;

        public abstract void bindView(V v);

        public abstract void setChecked(boolean checked);


    }

    public abstract static class DefaultButtonAction extends ButtonAction<ItemActionBinding> {


        public DefaultButtonAction(String name, int imgId) {
            this.name = name;
            this.imgId = imgId;
        }

        @Override
        public void bindView(ItemActionBinding itemActionBinding) {
            this.v = itemActionBinding;
            if (itemActionBinding == null) return;
            itemActionBinding.llContent.setVisibility(View.VISIBLE);
            itemActionBinding.ivImg.setOnClickListener(this);
            itemActionBinding.tvContent.setText(name);
            itemActionBinding.ivImg.setImageResource(imgId);
            itemActionBinding.ivImg.setSelected(checked);
        }


        @Override
        public void setChecked(boolean checked) {
            this.checked = checked;
            if (v != null) {
                v.ivImg.setSelected(checked);
            }
        }

    }


}
