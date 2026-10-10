package com.ethan.fruitslice;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

import java.util.Random;

/** Original, offline fruit artwork. Expensive paths and gradients are rasterized once. */
public final class GameArt {
    private static final int SIZE = 320;
    private static final int[] JUICE = {
            0xffff4260, 0xffffa523, 0xffb6e948, 0xffff4a42,
            0xffffd145, 0xffce78ed, 0xff70fff1, 0xffff9d35
    };
    private final Bitmap[] fruits = new Bitmap[8];
    private final Bitmap[][] halves = new Bitmap[8][2];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private Bitmap board;
    private int boardW, boardH;

    public GameArt() {
        for (int type = 0; type < 8; type++) {
            fruits[type] = sprite(type, -1);
            halves[type][0] = sprite(type, 0);
            halves[type][1] = sprite(type, 1);
        }
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

    private void draw(Canvas c, Bitmap image, float x, float y, float r,
                      float angle, float alpha) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255))));
        c.save();
        c.translate(x, y);
        c.rotate(angle);
        c.scale(r / 100f, r / 100f);
        c.drawBitmap(image, -SIZE / 2f, -SIZE / 2f, paint);
        c.restore();
    }

    private Bitmap sprite(int type, int side) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.translate(SIZE / 2f, SIZE / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (side < 0) {
            p.setColor(type == 7 ? 0x35000000 : 0x18000000);
            c.drawOval(-87, -72, 94, 102, p);
            switch (type) {
                case 0: watermelon(c, p); break;
                case 1: orange(c, p); break;
                case 2: kiwi(c, p); break;
                case 3: apple(c, p); break;
                case 4: pineapple(c, p); break;
                case 5: plum(c, p); break;
                case 6: prism(c, p); break;
                default: bomb(c, p);
            }
        } else {
            c.save();
            c.clipRect(side == 0 ? -150 : 0, -150, side == 0 ? 0 : 150, 150);
            cutFruit(c, p, type);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(type == 6 ? 0xb0ffffff : 0xaafff6c4);
            c.drawLine(side == 0 ? -1.5f : 1.5f, -91, side == 0 ? -1.5f : 1.5f, 91, p);
        }
        return bitmap;
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

    private static void leaf(Canvas c, Paint p, float x, float y, float angle, float scale) {
        c.save(); c.translate(x, y); c.rotate(angle); c.scale(scale, scale);
        Path leaf = new Path();
        leaf.moveTo(0, 0); leaf.cubicTo(8, -33, 45, -41, 58, -32);
        leaf.cubicTo(47, -5, 18, 8, 0, 0); leaf.close();
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, 0, 40, -35,
                0xff3a9c37, 0xffb6f466, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL); c.drawPath(leaf, p);
        stroke(p, 0x9970b63e, 2); c.drawLine(2, -1, 49, -28, p);
        c.restore();
    }

    private static void stem(Canvas c, Paint p, float x, float y, float height) {
        stroke(p, 0xff3e2310, 12); c.drawLine(x, y, x + 4, y - height, p);
        stroke(p, 0xffb89248, 4); c.drawLine(x - 2, y - 2, x + 2, y - height + 3, p);
    }

    private static void watermelon(Canvas c, Paint p) {
        radial(p, -36, -42, 174, 0xffb7f365, 0xff64cd4a, 0xff2c9143);
        c.drawOval(-103, -92, 103, 92, p);
        c.save(); c.clipPath(oval(-102, -91, 102, 91));
        for (int i = -3; i <= 3; i++) {
            float x = i * 32;
            Path stripe = new Path(); stripe.moveTo(x - 5, -103);
            stripe.cubicTo(x - 42, -45, x - 30, 45, x - 5, 103);
            stripe.lineTo(x + 9, 103);
            stripe.cubicTo(x - 8, 40, x - 16, -38, x + 10, -103); stripe.close();
            solid(p, 0x991d7835); c.drawPath(stripe, p);
            stroke(p, 0x68c7ec80, 2); c.drawPath(stripe, p);
        }
        c.restore();
        stroke(p, 0x5d173a22, 2.5f); c.drawOval(-103, -92, 103, 92, p);
        shine(c, p, -43, -43, 23, 33);
        stem(c, p, 11, -88, 10);
    }

    private static void orange(Canvas c, Paint p) {
        radial(p, -34, -40, 178, 0xffffed95, 0xffffb62e, 0xffef7d0c);
        c.drawCircle(0, 5, 93, p);
        Random random = new Random(21);
        for (int i = 0; i < 500; i++) {
            float x = random.nextFloat() * 186 - 93, y = random.nextFloat() * 186 - 88;
            if (x * x + (y - 5) * (y - 5) > 84 * 84) continue;
            solid(p, i % 3 == 0 ? 0x30fff2a4 : 0x24a93a00);
            c.drawCircle(x, y, .6f + random.nextFloat() * 1.6f, p);
        }
        solid(p, 0xff5b7022); c.drawOval(-9, -87, 9, -77, p);
        stem(c, p, 1, -82, 8); leaf(c, p, 4, -84, -5, .75f);
        shine(c, p, -37, -37, 17, 28);
    }

    private static void kiwi(Canvas c, Paint p) {
        c.save(); c.rotate(-12);
        radial(p, -37, -41, 178, 0xfff4d38b, 0xffc79b50, 0xff987535);
        c.drawOval(-87, -100, 87, 100, p);
        Random random = new Random(41);
        for (int i = 0; i < 750; i++) {
            float x = random.nextFloat() * 174 - 87, y = random.nextFloat() * 200 - 100;
            if (x * x / (85 * 85) + y * y / (98 * 98) > 1) continue;
            stroke(p, i % 2 == 0 ? 0x5cffe3a8 : 0x3c795a29, .8f);
            c.drawLine(x, y, x + 2, y - 3, p);
        }
        solid(p, 0xff5d4227); c.drawOval(-10, -98, 10, -88, p);
        shine(c, p, -38, -35, 15, 24);
        c.restore();
    }

    private static void apple(Canvas c, Paint p) {
        Path apple = new Path();
        apple.moveTo(0, -76); apple.cubicTo(-26, -106, -91, -85, -99, -28);
        apple.cubicTo(-108, 25, -66, 105, -26, 94);
        apple.cubicTo(-8, 87, 7, 86, 25, 94);
        apple.cubicTo(66, 107, 108, 25, 98, -29);
        apple.cubicTo(88, -86, 26, -106, 0, -76); apple.close();
        radial(p, -42, -42, 188, 0xffffa18c, 0xffff4149, 0xffcc2442);
        c.drawPath(apple, p);
        c.save(); c.clipPath(apple);
        Random random = new Random(7);
        for (int i = 0; i < 170; i++) {
            solid(p, 0x4affc88b);
            c.drawCircle(random.nextFloat() * 180 - 90, random.nextFloat() * 180 - 80,
                    random.nextFloat() * .8f + .5f, p);
        }
        stroke(p, 0x15fff4a0, 6);
        Path ridge = new Path(); ridge.moveTo(29, -72); ridge.cubicTo(65, -8, 64, 51, 30, 86);
        c.drawPath(ridge, p); c.restore();
        stem(c, p, 0, -79, 29); leaf(c, p, 5, -97, -3, .8f);
        shine(c, p, -44, -33, 17, 29);
    }

    private static void pineapple(Canvas c, Paint p) {
        Path shape = oval(-75, -75, 75, 111);
        radial(p, -29, -20, 170, 0xfffff2a8, 0xffffd650, 0xffdfac23);
        c.drawPath(shape, p);
        c.save(); c.clipPath(shape);
        for (int y = -101; y < 130; y += 30) {
            int row = (y + 101) / 30;
            for (int x = -112 + (row % 2) * 18; x < 112; x += 36) {
                Path diamond = new Path(); diamond.moveTo(x, y - 19); diamond.lineTo(x + 19, y);
                diamond.lineTo(x, y + 19); diamond.lineTo(x - 19, y); diamond.close();
                stroke(p, 0x9aaf8625, 2.4f); c.drawPath(diamond, p);
                solid(p, 0xffca9828); c.drawOval(x - 4, y - 3, x + 4, y + 5, p);
                stroke(p, 0xffffef9a, 1.8f); c.drawLine(x - 3, y - 5, x + 2, y - 5, p);
            }
        }
        c.restore();
        for (int i = -3; i <= 3; i++) {
            Path spike = new Path(); float tipX = i * 22;
            float tipY = -140 + Math.abs(i) * 15;
            spike.moveTo(-14 + i * 4, -64);
            spike.quadTo(tipX - 21, -90, tipX, tipY);
            spike.quadTo(tipX + 10, -96, 15 + i * 4, -64); spike.close();
            p.setColor(Color.WHITE);
            p.setShader(new LinearGradient(0, -140, 0, -58,
                    i % 2 == 0 ? 0xffbef168 : 0xff7bd94e, 0xff36963e, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL); c.drawPath(spike, p);
            stroke(p, 0x817dc451, 1.5f); c.drawLine(i * 4, -69, tipX, tipY + 10, p);
        }
        shine(c, p, -32, -12, 11, 30);
    }

    private static void plum(Canvas c, Paint p) {
        radial(p, -36, -43, 180, 0xffe1c5ff, 0xffac7ce8, 0xff7644ba);
        c.drawOval(-89, -98, 89, 100, p);
        stroke(p, 0x77452782, 3.5f);
        Path seam = new Path(); seam.moveTo(8, -94); seam.cubicTo(-11, -40, -9, 53, 12, 95);
        c.drawPath(seam, p);
        stroke(p, 0x36e8c6ff, 2); c.drawArc(-80, -87, 80, 91, 95, 100, false, p);
        Random random = new Random(91);
        for (int i = 0; i < 230; i++) {
            float x = random.nextFloat() * 160 - 80, y = random.nextFloat() * 178 - 89;
            if (x * x / 6400 + y * y / 7921 > 1) continue;
            solid(p, 0x15f5c8ff); c.drawCircle(x, y, random.nextFloat() * 2 + 1, p);
        }
        stem(c, p, 3, -94, 18); leaf(c, p, 7, -108, -15, .55f);
        shine(c, p, -40, -43, 18, 30);
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

    private static void cutFruit(Canvas c, Paint p, int type) {
        if (type == 6) {
            prism(c, p); return;
        }
        if (type == 7) {
            bomb(c, p); return;
        }
        int outer = type == 0 ? 0xff4daa4c : type == 1 ? 0xffffa62f :
                type == 2 ? 0xffab8748 : type == 3 ? 0xffff4150 :
                type == 4 ? 0xffd5a126 : 0xff965ccc;
        int rimShadow = Color.rgb((int) (Color.red(outer) * .8f),
                (int) (Color.green(outer) * .8f), (int) (Color.blue(outer) * .8f));
        radial(p, -30, -30, 170, outer, outer, rimShadow);
        c.drawCircle(0, 0, 100, p);
        solid(p, type == 0 ? 0xffe8efbe : type == 1 ? 0xfffff0b9 :
                type == 2 ? 0xffa7d54e : type == 3 ? 0xffffe7ab :
                type == 4 ? 0xfff0c342 : 0xffffb359);
        c.drawCircle(0, 0, type == 0 ? 90 : 93, p);
        if (type == 0) {
            radial(p, -26, -31, 165, 0xffff9789, 0xffff5266, 0xffdc3448);
            c.drawCircle(0, 0, 82, p);
            Random random = new Random(63);
            for (int i = 0; i < 31; i++) {
                float a = i * 2.399963f;
                float r = 18 + random.nextFloat() * 58;
                float x = (float) Math.cos(a) * r, y = (float) Math.sin(a) * r;
                c.save(); c.translate(x, y); c.rotate((float) Math.toDegrees(a) - 90);
                solid(p, 0xff682733); c.drawOval(-2.6f, -5.5f, 2.6f, 5.5f, p);
                solid(p, 0x75fff1bc); c.drawOval(-1.6f, -4, -.4f, -.6f, p); c.restore();
            }
            stroke(p, 0x1dfff9d3, 1); c.drawCircle(0, 0, 70, p);
        } else if (type == 1) {
            solid(p, 0xffffab24); c.drawCircle(0, 0, 84, p);
            for (int i = 0; i < 10; i++) {
                c.save(); c.rotate(i * 36);
                radial(p, 8, -53, 115, 0xffffe082, 0xffffbb3d, 0xffff9010);
                c.drawArc(-81, -81, 81, 81, -89, 32, true, p);
                stroke(p, 0xffffe8a8, 2.8f); c.drawLine(0, 0, 0, -84, p);
                stroke(p, 0x77fff0ae, 1.1f);
                for (int j = 0; j < 7; j++) {
                    float x = 8 + j * 3;
                    c.drawLine(x, -62 + j * 2, x + 9, -42 + j * 2, p);
                }
                c.restore();
            }
            solid(p, 0xffffeac0); c.drawCircle(0, 0, 10, p);
        } else if (type == 2) {
            radial(p, -27, -34, 164, 0xffe8ff92, 0xffb2df48, 0xff84b537);
            c.drawCircle(0, 0, 88, p);
            for (int i = 0; i < 42; i++) {
                c.save(); c.rotate(i * 360f / 42);
                stroke(p, 0x60f0f5ad, i % 2 == 0 ? 2 : 1);
                c.drawLine(0, 24, 0, 82, p); c.restore();
            }
            solid(p, 0xfff1eac0); c.drawOval(-22, -28, 22, 28, p);
            for (int i = 0; i < 28; i++) {
                c.save(); c.rotate(i * 360f / 28);
                solid(p, 0xff363a21); c.drawOval(-1.8f, 38, 1.8f, 44, p);
                solid(p, 0x85fff5b8); c.drawOval(-.7f, 38.5f, .2f, 40, p); c.restore();
            }
        } else if (type == 3) {
            radial(p, -30, -35, 175, 0xfffffae7, 0xffffedbf, 0xffedd198);
            c.drawCircle(0, 0, 92, p);
            solid(p, 0x35c59a56); c.drawOval(-19, -45, 19, 48, p);
            solid(p, 0xfff8edc4); c.drawOval(-14, -40, 14, 43, p);
            for (int i = 0; i < 5; i++) {
                c.save(); c.rotate(i * 72); c.translate(0, -21);
                solid(p, 0xff805334); c.drawOval(-3.5f, -9, 3.5f, 4, p); c.restore();
            }
        } else if (type == 4) {
            radial(p, -31, -29, 176, 0xfffff3aa, 0xffffdf63, 0xffe6bd38);
            c.drawCircle(0, 0, 91, p);
            for (int i = 0; i < 72; i++) {
                c.save(); c.rotate(i * 5);
                stroke(p, i % 3 == 0 ? 0x45fff3b8 : 0x35bd8f22, 1.1f);
                c.drawLine(0, 28, 0, 85, p); c.restore();
            }
            solid(p, 0xffffedaa); c.drawCircle(0, 0, 24, p);
            stroke(p, 0x68cfa333, 2); c.drawCircle(0, 0, 25, p);
        } else {
            radial(p, -31, -34, 174, 0xffffedaa, 0xffffca7d, 0xffe39c65);
            c.drawCircle(0, 0, 91, p);
            for (int i = 0; i < 28; i++) {
                c.save(); c.rotate(i * 360f / 28);
                stroke(p, 0x45ffd9a0, 1.2f); c.drawLine(0, 27, 0, 85, p); c.restore();
            }
            radial(p, -7, -11, 42, 0xffc99451, 0xffa36932, 0xff61371f);
            c.drawOval(-20, -32, 20, 33, p);
            for (int i = -2; i <= 2; i++) {
                stroke(p, 0x8082532c, 1.5f);
                c.drawLine(i * 5, -22 + Math.abs(i) * 4, i * 5 + 3, 24 - Math.abs(i) * 4, p);
            }
        }
        shine(c, p, -39, -38, 9, 18);
    }

    private static Path oval(float left, float top, float right, float bottom) {
        Path path = new Path(); path.addOval(left, top, right, bottom, Path.Direction.CW);
        return path;
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
