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
import android.content.res.AssetManager;
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
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.microedition.util.ContextHolder;

/**
 * Bitmap font. Glyphs are cut from an atlas of white cells and tinted with the
 * current graphics color.
 * <p>
 * Every style is a folder in assets/bitmapfont/ (add a folder to add a style):
 * style.properties - name, order, cellWidth, cellHeight, ascent, descent
 * atlas.png        - glyph i is at column i % columns, row i / columns
 *                    (columns = atlas width / cellWidth)
 * charset.txt      - UTF-8, the char of every glyph in atlas order
 * widths.bin       - one byte per glyph, the advance width in pixels
 */
public final class BitmapFont {
	public static final String DEFAULT_STYLE = "han16";

	private static final String TAG = BitmapFont.class.getSimpleName();
	private static final String DIR = "bitmapfont/";

	/** A font style found in assets. */
	public static final class Style {
		public final String id;
		public final String name;
		final int order;

		Style(String id, String name, int order) {
			this.id = id;
			this.name = name;
			this.order = order;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private static int cellW = 16;
	private static int cellH = 16;
	private static int columns = 128;
	private static int ascent = 14;
	private static int descent = 2;

	private static int[] index = new int[0];
	private static int[] width = new int[0];

	private static final Paint paint = new Paint();
	private static final Rect src = new Rect();
	private static final Rect dst = new Rect();
	private static Bitmap atlas;
	private static String loadedStyle;
	private static int filterColor;
	private static PorterDuffColorFilter filter;

	private BitmapFont() {
	}

	static int getCellHeight() {
		return cellH;
	}

	static int getAscent() {
		return ascent;
	}

	static int getDescent() {
		return descent;
	}

	/** Lists the styles available in assets, sorted by their "order" property. */
	public static List<Style> listStyles(Context context) {
		ArrayList<Style> styles = new ArrayList<>();
		AssetManager assets = context.getAssets();
		try {
			String[] dirs = assets.list(DIR.substring(0, DIR.length() - 1));
			if (dirs != null) {
				for (String dir : dirs) {
					Properties p = readProperties(context, dir);
					if (p != null) {
						styles.add(new Style(dir, p.getProperty("name", dir), parseInt(p, "order", 100)));
					}
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "Can't list bitmap font styles", e);
		}
		Collections.sort(styles, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order)
				: a.name.compareTo(b.name));
		return styles;
	}

	private static Properties readProperties(Context context, String dir) {
		try (InputStream is = context.getAssets().open(DIR + dir + "/style.properties")) {
			Properties p = new Properties();
			p.load(new InputStreamReader(is, Charset.forName("UTF-8")));
			return p;
		} catch (IOException e) {
			return null;
		}
	}

	private static int parseInt(Properties p, String key, int def) {
		try {
			return Integer.parseInt(p.getProperty(key, "").trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/**
	 * Loads a style (once). Unknown or empty ids fall back to {@link #DEFAULT_STYLE}.
	 * Returns false if the font is unavailable.
	 */
	static synchronized boolean load(String styleId) {
		Context context = ContextHolder.getAppContext();
		String id = styleId == null || styleId.isEmpty() ? DEFAULT_STYLE : styleId;
		Properties props = readProperties(context, id);
		if (props == null) {
			id = DEFAULT_STYLE;
			props = readProperties(context, id);
			if (props == null) {
				return false;
			}
		}
		if (atlas != null && id.equals(loadedStyle)) {
			return true;
		}
		try {
			String dir = DIR + id + "/";
			String charset = new String(readAsset(context, dir + "charset.txt"), Charset.forName("UTF-8"));
			byte[] widths = readAsset(context, dir + "widths.bin");
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
			try (InputStream is = context.getAssets().open(dir + "atlas.png")) {
				bitmap = BitmapFactory.decodeStream(is, null, options);
			}
			if (bitmap == null) {
				return false;
			}
			int cw = Math.max(1, parseInt(props, "cellWidth", 16));
			Bitmap old = atlas;
			cellW = cw;
			cellH = Math.max(1, parseInt(props, "cellHeight", 16));
			ascent = parseInt(props, "ascent", 14);
			descent = parseInt(props, "descent", 2);
			columns = Math.max(1, bitmap.getWidth() / cw);
			index = idx;
			width = w;
			atlas = bitmap;
			loadedStyle = id;
			if (old != null) {
				old.recycle();
			}
		} catch (IOException | OutOfMemoryError e) {
			Log.e(TAG, "Can't load bitmap font " + id, e);
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
	 * @param top   top edge of the glyph row (baseline - ascent)
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
				int sx = (idx % columns) * cellW;
				int sy = (idx / columns) * cellH;
				src.set(sx, sy, sx + w, sy + cellH);
				dst.set(cx, top, cx + w, top + cellH);
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
			int y = top + ascent + 1;
			canvas.drawRect(x, y, cx + (bold ? 1 : 0), y + 1, paint);
		}
	}
}
