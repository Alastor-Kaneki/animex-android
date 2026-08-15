package one.animex.plus;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import org.mozilla.geckoview.GeckoView;

/**
 * GeckoView variant that preserves normal one-finger touch/scroll behavior but
 * suppresses multi-touch gestures so the site cannot be pinch-zoomed.
 */
public final class NoZoomGeckoView extends GeckoView {
    private boolean suppressingMultiTouch;

    public NoZoomGeckoView(Context context) {
        super(context);
    }

    public NoZoomGeckoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final boolean multiTouch = event.getPointerCount() > 1
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP;

        if (multiTouch) {
            if (!suppressingMultiTouch) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel);
                cancel.recycle();
                suppressingMultiTouch = true;
            }
            return true;
        }

        if (suppressingMultiTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                suppressingMultiTouch = false;
            }
            return true;
        }

        return super.onTouchEvent(event);
    }
}
