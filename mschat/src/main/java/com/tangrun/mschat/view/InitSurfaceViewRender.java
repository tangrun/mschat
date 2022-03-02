package com.tangrun.mschat.view;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import com.tangrun.mslib.utils.PeerConnectionUtils;
import org.jetbrains.annotations.NotNull;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

/**
 * @author RainTang
 * @description:
 * @date :2022/2/16 16:26
 */
public class InitSurfaceViewRender extends SurfaceViewRenderer implements LifecycleEventObserver {
    public InitSurfaceViewRender(Context context) {
        super(context);
    }

    public InitSurfaceViewRender(Context context, AttributeSet attrs) {
        super(context, attrs);
    }


    boolean init = false;
    boolean register = false;
    VideoTrack videoTrack;

    public void init(LifecycleOwner lifecycleOwner) {
        if (register) return;
        register = true;
        lifecycleOwner.getLifecycle().addObserver(this);
    }

    public void bind(LifecycleOwner lifecycleOwner, VideoTrack videoTrack) {
        if (this.videoTrack != null && this.videoTrack != videoTrack) this.videoTrack.removeSink(this);
        this.videoTrack = videoTrack;
        if (lifecycleOwner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            bind();
        }
    }

    private void bind() {
        if (!register) return;
        if (videoTrack != null) {
            if (!init) {
                init(PeerConnectionUtils.getEglContext(), null);
                init = true;
            }
            videoTrack.addSink(this);
        } else unbind();
    }

    private void unbind() {
        if (init) {
            init = false;
            release();
        }
        if (videoTrack != null) {
            videoTrack.removeSink(this);
        }
    }

    @Override
    public void onStateChanged(@NonNull @NotNull LifecycleOwner source, @NonNull @NotNull Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_DESTROY) {
            source.getLifecycle().removeObserver(this);
            register = false;
            unbind();
            videoTrack = null;
        } else if (event == Lifecycle.Event.ON_RESUME) {
            bind();
        } else if (event == Lifecycle.Event.ON_STOP) {
            unbind();
        }
    }
}
