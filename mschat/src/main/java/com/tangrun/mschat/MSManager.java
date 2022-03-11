package com.tangrun.mschat;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.util.Log;
import androidx.lifecycle.Observer;
import com.bumptech.glide.annotation.GlideOption;
import com.tangrun.mschat.enums.CallEnd;
import com.tangrun.mschat.enums.RoomType;
import com.tangrun.mschat.model.UIRoomStore;
import com.tangrun.mschat.model.User;
import com.tangrun.mslib.RoomOptions;
import com.tangrun.mslib.utils.JsonUtils;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.mediasoup.droid.Logger;
import org.mediasoup.droid.MediasoupClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.http.conn.ssl.SSLSocketFactory.SSL;

/**
 * @author RainTang
 * @description:
 * @date :2022/2/14 17:39
 */
public class MSManager {
    private static final String TAG = "MS_UIRoomStore";

    private static String HOST;
    private static String PORT;
    private static UICallback uiCallback;

    private static boolean init = false;

    public static void readLastCall(Context context) {
        FileInputStream fis;
        try {
            fis = context.openFileInput(TAG);
            byte[] bytes = new byte[fis.available()];
            fis.read(bytes);
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            UIRoomStore uiRoomStore = new UIRoomStore();
            UIRoomStore data = parcel.readParcelable(UIRoomStore.class.getClassLoader());
            fis.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static void setCurrentCall(Context context, UIRoomStore uiRoomStore) {
        FileOutputStream fos;
        try {
            fos = context.openFileOutput(TAG,
                    Context.MODE_PRIVATE);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            Parcel parcel = Parcel.obtain();
            parcel.writeParcelable(uiRoomStore, 0);

            bos.write(parcel.marshall());
            bos.flush();
            bos.close();
            fos.flush();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void init(Application application, String host, String port, boolean debug, UICallback uiCallback) {
        if (init) return;
        init = true;
        HOST = host;
        PORT = port;
        MSManager.uiCallback = uiCallback;
        if (debug) {
            Logger.setLogLevel(Logger.LogLevel.LOG_DEBUG);
            Logger.setDefaultHandler();
        }
        MediasoupClient.initialize(application);
    }

    public static void setCallEnd(String id, RoomType roomType, boolean audioOnly, CallEnd callEnd) {
        if (uiCallback != null) {
            uiCallback.onCallEnd(id, roomType, audioOnly, callEnd, null, null);
        }
    }

    public static UIRoomStore getCurrent() {
        return UIRoomStore.getCurrent();
    }


    public static void openCallActivity() {
        if (getCurrent() != null) getCurrent().openCallActivity();
    }

    public static void addUser(List<User> list) {
        if (getCurrent() != null && list != null && !list.isEmpty())
            getCurrent().addUser(list);
    }

    public static void startCall(Context context, String roomId, User me,
                                 boolean audioOnly, boolean multi, boolean owner,
                                 List<User> inviteUser) {
        startCall(context, HOST, PORT, roomId, me, audioOnly, multi, owner, inviteUser, uiCallback);
    }

    public static void startCall(Context context, String host, String port, String roomId, User me,
                                 boolean audioOnly, boolean multi, boolean owner,
                                 List<User> inviteUser, UICallback uiCallback) {
        if (getCurrent() != null) {
            Log.d(TAG, "startCall: ");
            return;
        }
        RoomOptions roomOptions = new RoomOptions();
        // 服务器地址
        roomOptions.serverHost = host;
        roomOptions.serverPort = port;
        // 房间id
        roomOptions.roomId = roomId;
        // 我的信息
        roomOptions.mineAvatar = me.getAvatar();
        roomOptions.mineDisplayName = me.getDisplayName();
        roomOptions.mineId = me.getId();
        // 流的开关
        roomOptions.mConsume = true;
        roomOptions.mConsumeAudio = true;
        roomOptions.mConsumeVideo = !audioOnly;
        roomOptions.mProduce = true;
        roomOptions.mProduceAudio = true;
        roomOptions.mProduceVideo = !audioOnly;
        // 房间信息and状态
        UIRoomStore uiRoomStore = new UIRoomStore();
        // 房间信息
        uiRoomStore.roomType = multi ? RoomType.MultiCall : RoomType.SingleCall;
        uiRoomStore.owner = owner;
        uiRoomStore.audioOnly = audioOnly;
        // 回调
        uiRoomStore.uiCallback = uiCallback;
        // 配置
        uiRoomStore.firstSpeakerOn = !audioOnly || multi;
        uiRoomStore.firstConnectedAutoJoin = owner;
        uiRoomStore.firstJoinedAutoProduceAudio = true;
        uiRoomStore.firstJoinedAutoProduceVideo = !audioOnly && !multi;
        // 初始化 开始通话
        uiRoomStore.init(context, roomOptions);
        uiRoomStore.connect(inviteUser);
        uiRoomStore.openCallActivity();
    }


    //region api接口
    public static void roomExists(String id, ApiCallback<Boolean> apiCallback) {
        if (apiCallback == null) return;
        Observable.create(new ObservableOnSubscribe<Boolean>() {
                    @Override
                    public void subscribe(@NonNull ObservableEmitter<Boolean> emitter) throws Exception {
                        Request.Builder builder = new Request.Builder();
                        HttpUrl.Builder urlBuilder = HttpUrl.parse(getUrl() + "/roomExists").newBuilder();
                        urlBuilder.addQueryParameter("roomId", id);
                        builder.url(urlBuilder.build());
                        String request = request(builder.build());
                        JSONObject jsonObject = JsonUtils.toJsonObject(request);
                        int code = jsonObject.optInt("code");
                        if (code == 0) {
                            emitter.onNext(true);
                        } else {
                            emitter.onNext(false);
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new DisposableObserver<Boolean>() {
                    @Override
                    public void onNext(@NonNull Boolean s) {
                        apiCallback.onSuccess(s);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        dispose();
                        apiCallback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                    }
                });
    }

    public static void busyForUICallback(String roomId, String userId, RoomType roomType, boolean audioOnly) {
        busy(roomId, userId, new ApiCallback<Object>() {
            @Override
            public void onFail(Throwable e) {
                uiCallback.onCallEnd(roomId, roomType, audioOnly, CallEnd.Busy, null, null);
            }

            @Override
            public void onSuccess(Object o) {
                uiCallback.onCallEnd(roomId, roomType, audioOnly, CallEnd.Busy, null, null);
            }
        });
    }

    public static void busy(String roomId, String userId, ApiCallback<Object> apiCallback) {
        if (apiCallback == null) return;
        Observable.create(new ObservableOnSubscribe<Object>() {
                    @Override
                    public void subscribe(@NonNull ObservableEmitter<Object> emitter) throws Exception {
                        Request.Builder builder = new Request.Builder();
                        HttpUrl.Builder urlBuilder = HttpUrl.parse(getUrl() + "/busy").newBuilder();
                        urlBuilder.addQueryParameter("roomId", roomId)
                                .addQueryParameter("peerId", userId);
                        builder.url(urlBuilder.build());
                        String request = request(builder.build());
                        emitter.onComplete();
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new DisposableObserver<Object>() {
                    @Override
                    public void onNext(@NonNull Object s) {

                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        dispose();
                        apiCallback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                        apiCallback.onSuccess(null);
                    }
                });
    }

    public static void createRoom(String id, ApiCallback<String> apiCallback) {
        if (apiCallback == null) return;
        Observable.create(new ObservableOnSubscribe<String>() {
                    @Override
                    public void subscribe(@NonNull ObservableEmitter<String> emitter) throws Exception {
                        Request.Builder builder = new Request.Builder();
                        HttpUrl.Builder urlBuilder = HttpUrl.parse(getUrl() + "/debug_createRoom").newBuilder();
                        urlBuilder.addQueryParameter("roomId", id);
                        builder.url(urlBuilder.build());
                        String request = request(builder.build());
                        JSONObject jsonObject = JsonUtils.toJsonObject(request);
                        int code = jsonObject.optInt("code");
                        if (code == 0) {
                            emitter.onNext(id);
                        } else {
                            if (jsonObject.optString("msg", "").contains("已存在")) {
                                emitter.onNext(id);
                            } else {
                                emitter.onError(new Exception(jsonObject.optString("message")));
                            }
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new DisposableObserver<String>() {
                    @Override
                    public void onNext(@NonNull String s) {
                        apiCallback.onSuccess(s);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        dispose();
                        apiCallback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                    }
                });
    }

    public static void createRoom(ApiCallback<String> apiCallback) {
        if (apiCallback == null) return;
        Observable.create(new ObservableOnSubscribe<String>() {
                    @Override
                    public void subscribe(@NonNull ObservableEmitter<String> emitter) throws Exception {
                        Request.Builder builder = new Request.Builder();
                        HttpUrl.Builder urlBuilder = HttpUrl.parse(getUrl() + "/createRoom").newBuilder();
                        builder.url(urlBuilder.build());
                        String request = request(builder.build());
                        JSONObject jsonObject = JsonUtils.toJsonObject(request);
                        int code = jsonObject.optInt("code");
                        if (code == 0) {
                            emitter.onNext(jsonObject.optJSONObject("data").optString("roomId"));
                        } else {
                            emitter.onError(new Exception(jsonObject.optString("message")));
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new DisposableObserver<String>() {
                    @Override
                    public void onNext(@NonNull String s) {
                        apiCallback.onSuccess(s);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        dispose();
                        apiCallback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                    }
                });
    }

    private static OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts =
                    new TrustManager[]{
                            new X509TrustManager() {

                                @Override
                                public void checkClientTrusted(
                                        java.security.cert.X509Certificate[] chain, String authType)
                                        throws CertificateException {
                                }

                                @Override
                                public void checkServerTrusted(
                                        java.security.cert.X509Certificate[] chain, String authType)
                                        throws CertificateException {
                                }

                                // Called reflectively by X509TrustManagerExtensions.
                                public void checkServerTrusted(
                                        java.security.cert.X509Certificate[] chain, String authType, String host) {
                                }

                                @Override
                                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                    return new java.security.cert.X509Certificate[]{};
                                }
                            }
                    };

            final SSLContext sslContext = SSLContext.getInstance(SSL);
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            HttpLoggingInterceptor httpLoggingInterceptor =
                    new HttpLoggingInterceptor(s -> Logger.d(TAG, s));
            httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

            OkHttpClient.Builder builder =
                    new OkHttpClient.Builder()
                            .addInterceptor(httpLoggingInterceptor)
                            .retryOnConnectionFailure(true);
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);

            builder.hostnameVerifier((hostname, session) -> true);

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String request(Request request) throws IOException {
        OkHttpClient httpClient = getUnsafeOkHttpClient();
        Response response = httpClient.newCall(request).execute();
        return response.body().string();
    }

    private static String getUrl() {
        StringBuilder stringBuilder = new StringBuilder()
                .append("https://")
                .append(HOST);
        if (PORT != null && PORT.trim().length() > 0) {
            stringBuilder.append(":")
                    .append(PORT);
        }
        return stringBuilder.toString();
    }
    //endregion

}
