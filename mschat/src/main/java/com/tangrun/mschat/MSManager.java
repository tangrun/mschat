package com.tangrun.mschat;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.mediasoup.droid.Logger;
import org.mediasoup.droid.MediasoupClient;
import org.mediasoup.droid.lib.JsonUtils;
import org.mediasoup.droid.lib.RoomOptions;

import java.io.IOException;
import java.io.Serializable;
import java.security.cert.CertificateException;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

import static org.apache.http.conn.ssl.SSLSocketFactory.SSL;

/**
 * @author RainTang
 * @description:
 * @date :2022/2/14 17:39
 */
public class MSManager {
    private static String HOST;
    private static String PORT;

    static boolean init = false;

    public static void init(Application application, String host, String port, boolean debug) {
        if (init) return;
        init = true;
        HOST = host;
        PORT = port;
        if (debug) {
            Logger.setLogLevel(Logger.LogLevel.LOG_DEBUG);
            Logger.setDefaultHandler();
        }
        MediasoupClient.initialize(application);
    }

    private static final String TAG = "MS_UIRoomStore";
    private static UIRoomStore uiRoomStore;

    public static UIRoomStore getCurrent() {
        return uiRoomStore;
    }

    public static class User implements Serializable {
        private String id, displayName, avatar;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }

        public JSONObject toJsonObj() {
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("id", id);
                jsonObject.put("displayName", displayName);
                jsonObject.put("avatar", avatar);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jsonObject;
        }
    }

    static UICallback uiCallback;

    public interface UICallback {
        Intent onAddUser(Context context, List<User> users);

        void onAddUserResult(int resultCode, Intent intent);
    }

    public static void setUiCallback(UICallback uiCallback) {
        MSManager.uiCallback = uiCallback;
    }

    public static void openCallActivity() {
        if (uiRoomStore !=null)uiRoomStore.openCallActivity();
    }

    public interface Callback<T> {
        void onFail(Throwable e);

        void onSuccess(T t);
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

    public static void roomExists(String id, Callback<Boolean> callback) {
        if (callback == null) return;
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
                        callback.onSuccess(s);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        dispose();
                        callback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                    }
                });
    }

    public static void busy(String roomId, String userId, Callback<Object> callback) {
        if (callback == null) return;
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
                        callback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                        callback.onSuccess(null);
                    }
                });
    }

    public static void createRoom(String id, Callback<String> callback) {
        if (callback == null) return;
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
                    }else {
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
                        callback.onSuccess(s);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        dispose();
                        callback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                    }
                });
    }

    public static void createRoom(Callback<String> callback) {
        if (callback == null) return;
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
                    emitter.onNext(jsonObject.optString("roomId"));
                }else {
                    emitter.onError(new Exception(jsonObject.optString("message")));
                }
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new DisposableObserver<String>() {
                    @Override
                    public void onNext(@NonNull String s) {
                        callback.onSuccess(s);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        dispose();
                        callback.onFail(e);
                    }

                    @Override
                    public void onComplete() {
                        dispose();
                    }
                });
    }

    public static void addUser(List<User> list) {
        if (uiRoomStore != null && list != null && !list.isEmpty())
            uiRoomStore.addUser(list);
    }

    public static void stopCall() {
        uiRoomStore = null;
    }


    public static void startCall(Context context, String roomId, User me,
                                 boolean audioOnly, boolean multi,boolean owner,
                                 List<User> inviteUser) {
        if (getCurrent() != null) {
            Log.d(TAG, "startCall: ");
            return;
        }
        RoomOptions roomOptions = new RoomOptions();
        roomOptions.serverHost = HOST;
        roomOptions.serverPort = PORT;

        roomOptions.roomId = roomId;

        roomOptions.mineAvatar = me.getAvatar();
        roomOptions.mineDisplayName = me.getDisplayName();
        roomOptions.mineId = me.getId();

        roomOptions.mProduceVideo = !audioOnly;
        roomOptions.mConsumeVideo = !audioOnly;

        uiRoomStore = new UIRoomStore(context, roomOptions);

        uiRoomStore.roomType = multi ? 1 : 0;
        uiRoomStore.firstConnectedAutoJoin = owner;
        uiRoomStore.firstJoinedAutoProduceAudio = true;
        uiRoomStore.firstJoinedAutoProduceVideo = !audioOnly && !multi;
        uiRoomStore.audioOnly = audioOnly;

        uiRoomStore.addUser(inviteUser);

        uiRoomStore.connect();
    }

}
