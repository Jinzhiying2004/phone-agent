import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * v2.60：虚拟屏输入注入（替代 VdTap）。
 * 用法：
 *   VdInput tap <displayId> <x> <y>
 *   VdInput swipe <displayId> <x1> <y1> <x2> <y2> <durationMs>
 *   VdInput key <displayId> <keyCode>
 * 必须以 root 运行：CLASSPATH=... app_process /system/bin VdInput ...
 */
public class VdInput {

    private static Object im;
    private static Method inject;
    private static Method setDisplayId;

    public static void main(String[] args) throws Exception {
        Class<?> imClass = Class.forName("android.hardware.input.InputManager");
        im = imClass.getMethod("getInstance").invoke(null);
        inject = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        try {
            setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
        } catch (NoSuchMethodException e) {
            System.out.println("WARN: setDisplayId 不存在");
        }

        String cmd = args[0];
        int displayId = Integer.parseInt(args[1]);
        if ("tap".equals(cmd)) {
            float x = Float.parseFloat(args[2]);
            float y = Float.parseFloat(args[3]);
            tap(displayId, x, y);
        } else if ("swipe".equals(cmd)) {
            float x1 = Float.parseFloat(args[2]);
            float y1 = Float.parseFloat(args[3]);
            float x2 = Float.parseFloat(args[4]);
            float y2 = Float.parseFloat(args[5]);
            int duration = Integer.parseInt(args[6]);
            swipe(displayId, x1, y1, x2, y2, duration);
        } else if ("key".equals(cmd)) {
            int keyCode = Integer.parseInt(args[2]);
            key(displayId, keyCode);
        } else {
            System.out.println("未知命令: " + cmd);
        }
    }

    private static void setDid(MotionEvent e, int displayId) {
        if (setDisplayId != null) {
            try { setDisplayId.invoke(e, displayId); } catch (Exception ignored) {}
        }
    }

    private static void tap(int displayId, float x, float y) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 80,
                MotionEvent.ACTION_UP, x, y, 0);
        setDid(down, displayId);
        setDid(up, displayId);
        boolean r1 = (Boolean) inject.invoke(im, down, 0);
        Thread.sleep(80);
        boolean r2 = (Boolean) inject.invoke(im, up, 0);
        down.recycle();
        up.recycle();
        System.out.println("tap display=" + displayId + " down=" + r1 + " up=" + r2);
    }

    private static void swipe(int displayId, float x1, float y1,
                              float x2, float y2, int duration) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, x1, y1, 0);
        setDid(down, displayId);
        inject.invoke(im, down, 0);

        int steps = Math.max(2, duration / 16);
        boolean ok = true;
        for (int i = 1; i <= steps; i++) {
            float x = x1 + (x2 - x1) * i / steps;
            float y = y1 + (y2 - y1) * i / steps;
            long t = now + (long) duration * i / steps;
            MotionEvent move = MotionEvent.obtain(now, t,
                    MotionEvent.ACTION_MOVE, x, y, 0);
            setDid(move, displayId);
            ok &= (Boolean) inject.invoke(im, move, 0);
            move.recycle();
            Thread.sleep(16);
        }

        long end = now + duration + 50;
        MotionEvent up = MotionEvent.obtain(now, end,
                MotionEvent.ACTION_UP, x2, y2, 0);
        setDid(up, displayId);
        ok &= (Boolean) inject.invoke(im, up, 0);
        down.recycle();
        up.recycle();
        System.out.println("swipe display=" + displayId + " ok=" + ok);
    }

    private static void key(int displayId, int keyCode) throws Exception {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now,
                KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now + 50,
                KeyEvent.ACTION_UP, keyCode, 0);
        // KeyEvent 也有 setDisplayId（API 30+ 反射）
        try {
            Method setDidKey = KeyEvent.class.getMethod("setDisplayId", int.class);
            setDidKey.invoke(down, displayId);
            setDidKey.invoke(up, displayId);
        } catch (Exception ignored) {}
        boolean r1 = (Boolean) inject.invoke(im, down, 0);
        Thread.sleep(50);
        boolean r2 = (Boolean) inject.invoke(im, up, 0);
        System.out.println("key display=" + displayId
                + " keyCode=" + keyCode + " down=" + r1 + " up=" + r2);
    }
}
