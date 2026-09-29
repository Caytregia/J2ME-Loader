/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

import javax.microedition.util.ContextHolder;

/**
 * Bitmap font. Glyphs are cut from an atlas of white 16x16 cells (128 per row) and
 * tinted with the current graphics color.
 * <p>
 * Assets in bitmapfont/:
 * atlas.png   - glyph i is at column i % 128, row i / 128
 * charset.txt - UTF-8, the char of every glyph in atlas order
 * widths.bin  - one byte per glyph, the advance width in pixels
 */
final class BitmapFont {
	static final int CELL_W = 16;
	static final int CELL_H = 16;
	static final int COLUMNS = 128;
	static final int ASCENT = 14;
	static final int DESCENT = 2;

	private static final String TAG = BitmapFont.class.getSimpleName();
	private static final String DIR = "bitmapfont/";

	private static int[] index = new int[0];
	private static int[] width = new int[0];

	private static final Paint paint = new Paint();
	private static final Rect src = new Rect();
	private static final Rect dst = new Rect();
	private static Bitmap atlas;
	private static int filterColor;
	private static PorterDuffColorFilter filter;

	private BitmapFont() {
	}

	/** Loads the atlas and tables once. Returns false if they are unavailable. */
	static synchronized boolean load() {
		if (atlas != null) {
			return true;
		}
		Context context = ContextHolder.getAppContext();
		try {
			String charset = new String(readAsset(context, DIR + "charset.txt"), Charset.forName("UTF-8"));
			byte[] widths = readAsset(context, DIR + "widths.bin");
			int count = Math.min(charset.length(), widths.length);
			int[] idx = new int[65536];
			Arrays.fill(idx, -1);
			int[] w = new int[count];
			for (int i = 0; i < count; i++) {
				idx[charset.charAt(i)] = i;
				w[i] = widths[i] & 0xFF;
			}
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inScaled = false;
			options.inPreferredConfig = Bitmap.Config.ARGB_8888;
			Bitmap bitmap;
			try (InputStream is = context.getAssets().open(DIR + "atlas.png")) {
				bitmap = BitmapFactory.decodeStream(is, null, options);
			}
			if (bitmap == null) {
				return false;
			}
			index = idx;
			width = w;
			atlas = bitmap;
		} catch (IOException | OutOfMemoryError e) {
			Log.e(TAG, "Can't load bitmap font", e);
			return false;
		}
		paint.setAntiAlias(false);
		paint.setFilterBitmap(false);
		paint.setDither(false);
		return true;
	}

	private static byte[] readAsset(Context context, String path) throws IOException {
		try (InputStream is = context.getAssets().open(path)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	static boolean supports(char c) {
		return c < index.length && index[c] >= 0;
	}

	/** True if every char has a glyph. */
	static boolean supports(String s) {
		for (int i = 0, n = s.length(); i < n; i++) {
			if (!supports(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	static int charWidth(char c, boolean bold) {
		return width[index[c]] + (bold ? 1 : 0);
	}

	static int stringWidth(String s, boolean bold) {
		int w = 0;
		for (int i = 0, n = s.length(); i < n; i++) {
			w += width[index[s.charAt(i)]];
		}
		return bold && w > 0 ? w + 1 : w;
	}

	/**
	 * @param x     left edge of the text
	 * @param top   top edge of the glyph row (baseline - ASCENT)
	 * @param color 0xAARRGGBB
	 */
	static synchronized void draw(Canvas canvas, String s, int x, int top, int color,
								  boolean bold, boolean underline) {
		if (atlas == null) {
			return;
		}
		if (filter == null || filterColor != color) {
			filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
			filterColor = color;
		}
		paint.setAlpha(255);
		paint.setColorFilter(filter);
		int cx = x;
		for (int i = 0, n = s.length(); i < n; i++) {
			int idx = index[s.charAt(i)];
			int w = width[idx];
			if (w > 0) {
				int sx = (idx % COLUMNS) * CELL_W;
				int sy = (idx / COLUMNS) * CELL_H;
				src.set(sx, sy, sx + w, sy + CELL_H);
				dst.set(cx, top, cx + w, top + CELL_H);
				canvas.drawBitmap(atlas, src, dst, paint);
				if (bold) {
					dst.offset(1, 0);
					canvas.drawBitmap(atlas, src, dst, paint);
				}
			}
			cx += w;
		}
		paint.setColorFilter(null);
		if (underline) {
			paint.setColor(color);
			int y = top + ASCENT + 1;
			canvas.drawRect(x, y, cx + (bold ? 1 : 0), y + 1, paint);
		}
	}
}
