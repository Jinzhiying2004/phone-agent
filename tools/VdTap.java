import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * v2.56-spike：往指定虚拟屏注入点击。
 * 用法：app_process ... VdTap <displayId> <x> <y>
 * 必须以 root 运行（uid 0 绕过 INJECT_EVENTS 权限检查）。
 */
public class VdTap {
    public static void main(String[] args) throws Exception {
        int displayId = Integer.parseInt(args[0]);
        float x = Float.parseFloat(args[1]);
        float y = Float.parseFloat(args[2]);

        Class<?> imClass = Class.forName("android.hardware.input.InputManager");
        Object im = imClass.getMethod("getInstance").invoke(null);
        Method inject = imClass.getMethod("injectInputEvent",
                InputEvent.class, int.class);
        // INJECT_INPUT_EVENT_MODE_ASYNC = 0
        int MODE_ASYNC = 0;

        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 80,
                MotionEvent.ACTION_UP, x, y, 0);
        // setDisplayId 可能隐藏，反射调用
        try {
            Method setDid = MotionEvent.class.getMethod("setDisplayId", int.class);
            setDid.invoke(down, displayId);
            setDid.invoke(up, displayId);
        } catch (NoSuchMethodException nsme) {
            System.out.println("WARN: setDisplayId 不存在");
        }

        boolean r1 = (Boolean) inject.invoke(im, down, MODE_ASYNC);
        Thread.sleep(80);
        boolean r2 = (Boolean) inject.invoke(im, up, MODE_ASYNC);
        down.recycle();
        up.recycle();
        System.out.println("inject down=" + r1 + " up=" + r2
                + " display=" + displayId + " x=" + x + " y=" + y);
    }
}
