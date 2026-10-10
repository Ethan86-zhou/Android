package com.ethan.fruitslice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Offline artwork. Photoreal fruit assets and procedural effects are decoded/rasterized once. */
public final class GameArt {
    private static final int SIZE = 320;
    private static final float CACHED_BODY_DIAMETER = 384f;
    private static final String[] FRUIT_NAMES = {
            "watermelon", "orange", "kiwi", "apple", "pineapple", "plum"
    };
    private static final int[] JUICE = {
            0xffff4260, 0xffffa523, 0xffb6e948, 0xffff4a42,
            0xffffd145, 0xffce78ed, 0xff70fff1, 0xffff9d35
    };
    private final Sprite[] fruits = new Sprite[8];
    private final Sprite[][] halves = new Sprite[8][2];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private Bitmap board;
    private int boardW, boardH;
    private int wholeAssets, cutAssets;

    private static final class Sprite {
        final Bitmap bitmap;
        final float centerX, centerY, radius, bodyWidth, bodyHeight;
        final boolean explicitBodyBounds;

        Sprite(Bitmap bitmap, float centerX, float centerY, float bodyWidth,
               float bodyHeight, boolean explicitBodyBounds) {
            this.bitmap = bitmap;
            this.centerX = centerX;
            this.centerY = centerY;
            this.bodyWidth = bodyWidth;
            this.bodyHeight = bodyHeight;
            this.radius = Math.max(bodyWidth, bodyHeight) / 2f;
            this.explicitBodyBounds = explicitBodyBounds;
        }
    }

    public GameArt(Context context) {
        JSONObject bodyBounds = readBodyBounds(context);
        for (int type = 0; type < FRUIT_NAMES.length; type++) {
            String name = FRUIT_NAMES[type];
            fruits[type] = readFruit(context, "fruits/" + name + ".png",
                    bodyBounds.optJSONArray(name), false);
            wholeAssets++;
            Sprite face = readFruit(context, "fruits/cut/" + name + ".png", null, true);
            halves[type][0] = splitFace(face, true);
            halves[type][1] = splitFace(face, false);
            face.bitmap.recycle();
            cutAssets++;
        }
        for (int type = 6; type < 8; type++) {
            fruits[type] = proceduralSprite(type, -1);
            halves[type][0] = proceduralSprite(type, 0);
            halves[type][1] = proceduralSprite(type, 1);
        }
    }

    public boolean usesRealisticFruitAssets() {
        return wholeAssets == FRUIT_NAMES.length && cutAssets == FRUIT_NAMES.length;
    }

    /** Geometry is exposed for device acceptance without a debug overlay in the game. */
    public String assetDiagnostics() {
        JSONObject result = new JSONObject();
        try {
            result.put("wholeAssets", wholeAssets);
            result.put("cutAssets", cutAssets);
            long spriteBytes = 0;
            for (int type = 0; type < fruits.length; type++) {
                spriteBytes += fruits[type].bitmap.getAllocationByteCount();
                spriteBytes += halves[type][0].bitmap.getAllocationByteCount();
                spriteBytes += halves[type][1].bitmap.getAllocationByteCount();
            }
            result.put("spriteCacheBytes", spriteBytes);
            for (int type = 0; type < FRUIT_NAMES.length; type++) {
                Sprite whole = fruits[type], half = halves[type][0];
                JSONObject geometry = new JSONObject();
                geometry.put("cachedWidth", whole.bitmap.getWidth());
                geometry.put("cachedHeight", whole.bitmap.getHeight());
                geometry.put("bodyWidth", whole.bodyWidth);
                geometry.put("bodyHeight", whole.bodyHeight);
                geometry.put("bodyDiameter", whole.radius * 2);
                geometry.put("bodyCenterX", whole.centerX);
                geometry.put("bodyCenterY", whole.centerY);
                geometry.put("explicitBodyBounds", whole.explicitBodyBounds);
                geometry.put("cutBodyDiameter", half.radius * 2);
                result.put(FRUIT_NAMES[type], geometry);
            }
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("Invalid fruit geometry", impossible);
        }
        return result.toString();
    }

    private static JSONObject readBodyBounds(Context context) {
        try (InputStream stream = context.getAssets().open("fruits/body-bounds.json")) {
            return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.FileNotFoundException absent) {
            return new JSONObject();
        } catch (IOException absent) {
            return new JSONObject();
        } catch (org.json.JSONException invalid) {
            throw new IllegalStateException("Invalid fruits/body-bounds.json", invalid);
        }
    }

    private static Sprite readFruit(Context context, String assetPath, JSONArray coordinates,
                                    boolean cutFace) {
        Bitmap decoded;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream stream = context.getAssets().open(assetPath)) {
            decoded = BitmapFactory.decodeStream(stream, null, options);
        } catch (IOException failure) {
            throw new IllegalStateException("Missing required fruit artwork: " + assetPath, failure);
        }
        if (decoded == null) throw new IllegalStateException("Cannot decode fruit artwork: " + assetPath);
        int width = decoded.getWidth(), height = decoded.getHeight();
        int[] pixels = new int[width * height];
        decoded.getPixels(pixels, 0, width, 0, 0, width, height);
        Rect visible = alphaBounds(pixels, width, height);
        if (visible.isEmpty()) {
            decoded.recycle();
            throw new IllegalStateException("Fruit artwork is transparent: " + assetPath);
        }
        if (!decoded.hasAlpha()) {
            decoded.recycle();
            throw new IllegalStateException("Fruit artwork must have a transparent background: " + assetPath);
        }
        RectF body = cutFace ? new RectF(visible) : estimateBody(pixels, width, visible);
        if (coordinates != null) {
            if (coordinates.length() != 4) {
                decoded.recycle();
                throw new IllegalStateException("Expected four body bounds for " + assetPath);
            }
            try {
                body.set((float) coordinates.getDouble(0) * width,
                        (float) coordinates.getDouble(1) * height,
                        (float) coordinates.getDouble(2) * width,
                        (float) coordinates.getDouble(3) * height);
            } catch (org.json.JSONException invalid) {
                decoded.recycle();
                throw new IllegalStateException("Invalid body bounds for " + assetPath, invalid);
            }
        }
        if (body.width() <= 1 || body.height() <= 1 || body.left < 0 || body.top < 0
                || body.right > width || body.bottom > height) {
            decoded.recycle();
            throw new IllegalStateException("Body bounds outside fruit artwork: " + assetPath);
        }
        // Trim transparent padding while retaining every visible leaf/crown. Body geometry,
        // rather than image dimensions, sets the gameplay radius. Neither axis is squashed.
        float scale = CACHED_BODY_DIAMETER / Math.max(body.width(), body.height());
        Bitmap cached = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(visible.width() * scale)),
                Math.max(1, (int) Math.ceil(visible.height() * scale)), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(Color.WHITE);
        new Canvas(cached).drawBitmap(decoded, visible,
                new RectF(0, 0, visible.width() * scale, visible.height() * scale), p);
        Sprite result = new Sprite(cached, (body.centerX() - visible.left) * scale,
                (body.centerY() - visible.top) * scale, body.width() * scale,
                body.height() * scale, coordinates != null);
        decoded.recycle();
        return result;
    }

    private static Rect alphaBounds(int[] pixels, int width, int height) {
        int left = width, right = -1, top = height, bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) <= 12) continue;
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        return right < left ? new Rect() : new Rect(left, top, right + 1, bottom + 1);
    }

    private static RectF estimateBody(int[] pixels, int width, Rect visible) {
        // The dense lower silhouette excludes thin stems/leaves. Explicit normalized bounds
        // can override this estimate for a broad crown such as a pineapple's.
        int[] rowMass = new int[visible.bottom];
        int maximum = 0;
        for (int y = visible.top; y < visible.bottom; y++) {
            for (int x = visible.left; x < visible.right; x++) {
                if ((pixels[y * width + x] >>> 24) >= 128) rowMass[y]++;
            }
            maximum = Math.max(maximum, rowMass[y]);
        }
        int threshold = Math.max(1, (int) (maximum * .6f));
        int coreBottom = visible.bottom - 1;
        while (coreBottom > visible.top && rowMass[coreBottom] < threshold) coreBottom--;
        int coreTop = coreBottom;
        while (coreTop > visible.top && rowMass[coreTop - 1] >= threshold) coreTop--;
        int left = visible.right, right = visible.left;
        for (int y = coreTop; y <= coreBottom; y++) {
            for (int x = visible.left; x < visible.right; x++) {
                if ((pixels[y * width + x] >>> 24) < 128) continue;
                left = Math.min(left, x); right = Math.max(right, x + 1);
            }
        }
        if (right <= left || coreBottom <= coreTop) return new RectF(visible);
        float edge = (coreBottom - coreTop + 1) * .125f;
        return new RectF(left, Math.max(visible.top, coreTop - edge), right,
                Math.min(visible.bottom, coreBottom + 1 + edge));
    }

    private static Sprite splitFace(Sprite face, boolean left) {
        int cropLeft = left ? 0 : (int) Math.floor(face.centerX);
        int cropRight = left ? (int) Math.ceil(face.centerX) : face.bitmap.getWidth();
        Bitmap half = Bitmap.createBitmap(Math.max(1, cropRight - cropLeft), face.bitmap.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(half);
        c.clipRect(left ? 0 : face.centerX - cropLeft, 0,
                left ? face.centerX : half.getWidth(), half.getHeight());
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(Color.WHITE);
        c.drawBitmap(face.bitmap, -cropLeft, 0, p);
        return new Sprite(half, face.centerX - cropLeft, face.centerY,
                face.bodyWidth, face.bodyHeight, face.explicitBodyBounds);
    }

    public int juiceColor(int type) {
        return JUICE[Math.max(0, Math.min(JUICE.length - 1, type))];
    }

    public void background(Canvas c, float w, float h) {
        int width = Math.max(1, Math.round(w));
        int height = Math.max(1, Math.round(h));
        if (board == null || boardW != width || boardH != height) {
            if (board != null) board.recycle();
            boardW = width;
            boardH = height;
            board = makeBoard(width, height);
        }
        paint.reset();
        paint.setFilterBitmap(true);
        destination.set(0, 0, w, h);
        c.drawBitmap(board, null, destination, paint);
    }

    public void fruit(Canvas c, int type, float x, float y, float r, float angle, float alpha) {
        draw(c, fruits[Math.max(0, Math.min(7, type))], x, y, r, angle, alpha);
    }

    public void half(Canvas c, int type, float x, float y, float r, float angle,
                     boolean left, float alpha) {
        draw(c, halves[Math.max(0, Math.min(7, type))][left ? 0 : 1],
                x, y, r, angle, alpha);
    }

    private void draw(Canvas c, Sprite image, float x, float y, float r,
                      float angle, float alpha) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255))));
        c.save();
        c.translate(x, y);
        c.rotate(angle);
        c.scale(r / image.radius, r / image.radius);
        c.drawBitmap(image.bitmap, -image.centerX, -image.centerY, paint);
        c.restore();
    }

    private Sprite proceduralSprite(int type, int side) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.translate(SIZE / 2f, SIZE / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (side < 0) {
            p.setColor(type == 7 ? 0x35000000 : 0x18000000);
            c.drawOval(-87, -72, 94, 102, p);
            if (type == 6) prism(c, p); else bomb(c, p);
        } else {
            c.save();
            c.clipRect(side == 0 ? -150 : 0, -150, side == 0 ? 0 : 150, 150);
            if (type == 6) prism(c, p); else bomb(c, p);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(type == 6 ? 0xb0ffffff : 0xaafff6c4);
            c.drawLine(side == 0 ? -1.5f : 1.5f, -91, side == 0 ? -1.5f : 1.5f, 91, p);
        }
        return new Sprite(bitmap, SIZE / 2f, SIZE / 2f, 200, 200, false);
    }

    private static void radial(Paint p, float x, float y, float r, int light, int mid, int dark) {
        p.setStyle(Paint.Style.FILL);
        // Shader colors do not reset Paint alpha. Never inherit a shadow/texture's transparency.
        p.setColor(Color.WHITE);
        p.setShader(new RadialGradient(x, y, r, new int[]{light, mid, dark},
                new float[]{0, .58f, 1}, Shader.TileMode.CLAMP));
    }

    private static void solid(Paint p, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
    }

    private static void stroke(Paint p, int color, float width) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
    }

    private static void shine(Canvas c, Paint p, float x, float y, float rx, float ry) {
        solid(p, 0x57ffffff);
        c.save(); c.rotate(-28, x, y);
        c.drawOval(x - rx, y - ry, x + rx, y + ry, p);
        solid(p, 0x8affffff);
        c.drawOval(x - rx * .7f, y - ry * .65f, x + rx * .3f, y + ry * .15f, p);
        c.restore();
    }

    private static Path star(float radius, float inner, int points) {
        Path path = new Path();
        for (int i = 0; i < points * 2; i++) {
            double a = -Math.PI / 2 + i * Math.PI / points;
            float r = i % 2 == 0 ? radius : inner;
            float x = (float) Math.cos(a) * r, y = (float) Math.sin(a) * r;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close(); return path;
    }

    private static void prism(Canvas c, Paint p) {
        radial(p, 0, 0, 144, 0x9797fff0, 0x457b9cff, 0x007b9cff);
        c.drawCircle(0, 0, 145, p);
        stroke(p, 0x5093fff1, 2.8f); c.drawCircle(0, 0, 121, p);
        stroke(p, 0xb5ddfff4, 1.2f); c.drawArc(-125, -125, 125, 125, 198, 60, false, p);
        Path star = star(103, 61, 5);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setShader(new SweepGradient(0, 0,
                new int[]{0xff7dffeb, 0xff72c0ff, 0xffc68fff, 0xffff97c5,
                        0xffffeb91, 0xffa9ff9d, 0xff7dffeb}, null));
        c.drawPath(star, p);
        for (int i = 0; i < 5; i++) {
            double a = -Math.PI / 2 + i * Math.PI * 2 / 5;
            Path facet = new Path(); facet.moveTo(0, 0);
            facet.lineTo((float) Math.cos(a) * 102, (float) Math.sin(a) * 102);
            facet.lineTo((float) Math.cos(a + Math.PI / 5) * 61,
                    (float) Math.sin(a + Math.PI / 5) * 61); facet.close();
            solid(p, i % 2 == 0 ? 0x46ffffff : 0x16003377); c.drawPath(facet, p);
        }
        stroke(p, 0xffe4fff6, 3); c.drawPath(star, p);
        solid(p, 0xc8ffffff); c.drawPath(star(23, 10, 4), p);
        for (int i = 0; i < 4; i++) {
            c.save(); c.rotate(i * 90 + 18); c.translate(0, -133);
            solid(p, 0xd9dfffff); c.drawPath(star(7, 2, 4), p); c.restore();
        }
    }

    private static void bomb(Canvas c, Paint p) {
        radial(p, -30, -37, 150, 0xff8995a8, 0xff343c4b, 0xff101723);
        c.drawCircle(0, 7, 90, p);
        stroke(p, 0xff657081, 2); c.drawArc(-84, -77, 84, 91, 138, 120, false, p);
        solid(p, 0xff30313a); c.drawRoundRect(-17, -92, 21, -69, 5, 5, p);
        stroke(p, 0xffa18a60, 7);
        Path fuse = new Path(); fuse.moveTo(3, -89); fuse.cubicTo(6, -115, 38, -102, 39, -125);
        c.drawPath(fuse, p);
        stroke(p, 0xfff0cb89, 2); c.drawPath(fuse, p);
        radial(p, 39, -125, 27, 0xcafff1a2, 0x78ff9a27, 0x00ff5c00);
        c.drawCircle(39, -125, 27, p);
        solid(p, 0xffffeb9a); c.save(); c.translate(39, -125);
        c.drawPath(star(13, 4, 6), p); c.restore();
        stroke(p, 0xffffb04c, 2.5f);
        c.drawLine(57, -136, 65, -141, p); c.drawLine(29, -143, 27, -151, p);
        c.drawLine(59, -116, 68, -112, p);
        shine(c, p, -38, -35, 16, 25);
        // A simple embossed warning mark remains legible even at small game sizes.
        c.save(); c.translate(1, 24);
        Path warning = new Path(); warning.moveTo(0, -28); warning.lineTo(28, 22);
        warning.lineTo(-28, 22); warning.close();
        stroke(p, 0xffd9b460, 4); c.drawPath(warning, p);
        stroke(p, 0xffe9c876, 5); c.drawLine(0, -11, 0, 5, p);
        solid(p, 0xffe9c876); c.drawCircle(0, 14, 3, p); c.restore();
    }

    private static Bitmap makeBoard(int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float scale = w / 1000f;
        c.scale(scale, scale);
        float height = h / scale;
        solid(p, 0xff251b18); c.drawRect(0, 0, 1000, height, p);
        Random random = new Random(9247);
        float plank = Math.max(64, height / 5.1f);
        int row = 0;
        for (float y = -23; y < height; y += plank, row++) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, y, 0, y + plank,
                    new int[]{0xff382722, row % 2 == 0 ? 0xff432e25 : 0xff3c2923, 0xff291d19},
                    new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, y, 1000, y + plank, p);
            for (int strand = 0; strand < 41; strand++) {
                float grainY = y + random.nextFloat() * plank;
                float grainX = random.nextFloat() * 1100 - 100;
                float length = 140 + random.nextFloat() * 700;
                Path grain = new Path(); grain.moveTo(grainX, grainY);
                grain.cubicTo(grainX + length * .28f, grainY - 5 - random.nextFloat() * 5,
                        grainX + length * .62f, grainY + 5, grainX + length, grainY - 1);
                stroke(p, strand % 3 == 0 ? 0x16ce9b66 : 0x38301b15,
                        strand % 4 == 0 ? 1.3f : .7f);
                c.drawPath(grain, p);
            }
            if (row % 2 == 0) {
                float knotX = 110 + random.nextFloat() * 780;
                for (int ring = 0; ring < 5; ring++) {
                    stroke(p, 0x30201210, 1.1f);
                    c.drawOval(knotX - 13 - ring * 9, y + 29 - ring * 3,
                            knotX + 16 + ring * 10, y + 40 + ring * 3, p);
                }
            }
            stroke(p, 0x9b100d0d, 2.5f); c.drawLine(0, y + plank, 1000, y + plank, p);
            stroke(p, 0x2fbd9667, 1); c.drawLine(0, y + plank + 2, 1000, y + plank + 2, p);
            for (float x : new float[]{24, 976}) {
                int previousAlpha = p.getAlpha();
                radial(p, x - 1.5f, y + 14, 8, 0xff716657, 0xff40392f, 0xff201b19);
                p.setAlpha(previousAlpha);
                c.drawCircle(x, y + 16, 4.3f, p);
                stroke(p, 0xff241f1b, 1.1f); c.drawLine(x - 2, y + 15, x + 2, y + 17, p);
            }
        }
        // Fine old knife marks add depth without fighting fresh slice trails.
        for (int i = 0; i < 32; i++) {
            float x = 60 + random.nextFloat() * 880, y = 50 + random.nextFloat() * (height - 80);
            float length = 20 + random.nextFloat() * 75;
            stroke(p, 0x12f2c38a, .7f); c.drawLine(x, y, x + length, y - length * .45f, p);
            stroke(p, 0x220e0b09, 1); c.drawLine(x, y + 1, x + length, y - length * .45f + 1, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(485, height * .42f, 640,
                new int[]{0x15ffd29b, 0x00160f0d, 0xa408090d},
                new float[]{0, .38f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1000, height, p);
        p.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{0x73060a11, 0x00000000, 0x66080a11},
                new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1000, height, p);
        return bitmap;
    }
}
