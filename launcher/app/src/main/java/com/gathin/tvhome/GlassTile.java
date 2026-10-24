package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.io.File;

/**
 * A dock icon's glass plate: the wallpaper behind it, lensed and blurred, under a specular rim.
 *
 * <p>Android has no backdrop blur before API 31 ({@code RenderEffect}), and blurring a live backdrop every
 * frame is out of the question on a 1 GB TV anyway. It is affordable here because the backdrop is a
 * photograph that never moves: the wallpaper is blurred <em>once</em>, into a bitmap small enough that the
 * blur is simply what downscaling does, and every tile then samples its own slice of it. The whole backdrop
 * costs about 8 KB and one decode at start.
 *
 * <p>The sample is magnified about the tile's centre, so the picture bends outward under the glass the way
 * it does through a lens, and a tile that grows under focus bends it further.
 */
final class GlassTile extends Drawable {

    /** Width the blurred backdrop is reduced to. Small enough that the scaling up is the blur. */
    private static final int BACKDROP_W = 64;
    /** How much the glass magnifies what is behind it. 1.0 would be a window rather than a lens. */
    private static final float LENS = 1.22f;

    private static Bitmap backdrop;
    private static boolean loaded;
    /** Screen size the backdrop maps onto, so a tile can find its own slice. */
    private static int screenW, screenH;

    /**
     * Decodes the theme's wallpaper once and keeps a blurred copy. Safe to call repeatedly; a TV with no
     * wallpaper leaves the backdrop null and the tiles fall back to plain frosted glass.
     */
    static void load(Context ctx, int w, int h) {
        if (loaded && screenW == w) return;
        loaded = true;
        screenW = w;
        screenH = h;
        backdrop = null;
        File dir = Config.dir(ctx);
        File file = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (file == null || !file.exists()) return;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0) return;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // Decode straight to roughly the size we want rather than loading 1080p and throwing it away.
            opts.inSampleSize = Math.max(1, Integer.highestOneBit(bounds.outWidth / (BACKDROP_W * 2)));
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap small = BitmapFactory.decodeFile(file.getPath(), opts);
            if (small == null) return;
            int bh = Math.max(1, Math.round(BACKDROP_W * small.getHeight() / (float) small.getWidth()));
            // Filtered downscale: averaging this many pixels into one is the blur.
            backdrop = Bitmap.createScaledBitmap(small, BACKDROP_W, bh, true);
            if (backdrop != small) small.recycle();
        } catch (OutOfMemoryError | RuntimeException ignored) {
            backdrop = null;
        }
    }

    /** Dropped when the wallpaper changes, so the next tile reloads it. */
    static void invalidateBackdrop() {
        loaded = false;
        backdrop = null;
    }

    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Where this tile sits on screen, so it samples the part of the picture actually behind it. */
    private float screenX, screenY;

    GlassTile(float density) {
        this.density = density;
        if (backdrop != null) {
            lens.setShader(new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            lens.setFilterBitmap(true);
        } else {
            // No wallpaper to refract: plain frosted glass, so the tile still reads as a surface.
            lens.setColor(0x26FFFFFF);
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.4f * density);
    }

    /** The tile's top-left on screen. Set as the dock is packed, since the icons move as focus does. */
    void setScreenPos(float x, float y) {
        if (x == screenX && y == screenY) return;
        screenX = x;
        screenY = y;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        rect.set(b.left, b.top, b.right, b.bottom);
        radius = AppAdapter.cornerRadius(b.width(), density);
        // Specular edge: bright along the top, almost gone by the bottom, as light catches a glass lip.
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x8CFFFFFF, 0x1FFFFFFF, 0x3DFFFFFF}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        // A soft wash down the face, so the glass has body rather than only edges.
        sheen.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x2EFFFFFF, 0x0AFFFFFF, 0x1AFFFFFF}, new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.isEmpty()) return;
        if (backdrop != null && screenW > 0) {
            // Map the little backdrop onto the screen, shift it so this tile's own slice lands in local
            // space, then magnify about the tile's centre: that outward bend is what makes it read as glass.
            float s = screenW / (float) backdrop.getWidth();
            matrix.setScale(s, s);
            matrix.postTranslate(-screenX, -screenY);
            matrix.postScale(LENS, LENS, rect.centerX(), rect.centerY());
            lens.getShader().setLocalMatrix(matrix);
        }
        canvas.drawRoundRect(rect, radius, radius, lens);
        canvas.drawRoundRect(rect, radius, radius, sheen);
        float inset = rim.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, radius, radius, rim);
        rect.inset(-inset, -inset);
    }

    @Override
    public void setAlpha(int alpha) {
        lens.setAlpha(alpha);
        sheen.setAlpha(alpha);
        rim.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        lens.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
