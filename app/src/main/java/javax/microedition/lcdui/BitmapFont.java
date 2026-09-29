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

import java.io.IOException;
import java.io.InputStream;

import javax.microedition.util.ContextHolder;

/**
 * Fixed-size bitmap font. Glyphs are cut from a 16-column atlas of white
 * 16x15 cells and tinted with the current graphics color.
 */
final class BitmapFont {
	static final int CELL_W = 16;
	static final int CELL_H = 15;
	static final int COLUMNS = 16;
	static final int ASCENT = 13;
	static final int DESCENT = 2;

	private static final String TAG = BitmapFont.class.getSimpleName();
	private static final String ATLAS_PATH = "bitmapfont/atlas.png";

	// Glyph order inside the atlas: index i -> column i % 16, row i / 16
	private static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"
			+ "ghijklmnopqrstuvwxyz0123456789.,"
			+ ";:!@/\\*()[]{}|#$%^&<>?'\"+- \u0111\u0110\u00e1\u00e0\u1ea3"
			+ "\u00e3\u1ea1\u0103\u1eaf\u1eb1\u1eb3\u1eb5\u1eb7\u00e2\u1ea5\u1ea7\u1ea9\u1eab\u1ead\u00e9\u00e8\u1ebb\u1ebd\u1eb9\u00ea\u1ebf\u1ec1\u1ec3\u1ec5\u1ec7\u00ed\u00ec\u1ec9\u0129\u1ecb\u00f3\u00f2"
			+ "\u1ecf\u00f5\u1ecd\u00f4\u1ed1\u1ed3\u1ed5\u1ed7\u1ed9\u01a1\u1edb\u1edd\u1edf\u1ee1\u1ee3\u00fa\u00f9\u1ee7\u0169\u1ee5\u01b0\u1ee9\u1eeb\u1eed\u1eef\u1ef1\u00fd\u1ef3\u1ef7\u1ef9\u1ef5\u00c1"
			+ "\u00c0\u1ea2\u00c3\u1ea0\u0102\u1eae\u1eb0\u1eb2\u1eb4\u1eb6\u00c2\u1ea4\u1ea6\u1ea8\u1eaa\u1eac\u00c9\u00c8\u1eba\u1ebc\u1eb8\u00ca\u1ebe\u1ec0\u1ec2\u1ec4\u1ec6\u00cd\u00cc\u1ec8\u0128\u1eca"
			+ "\u00d3\u00d2\u1ece\u00d5\u1ecc\u00d4\u1ed0\u1ed2\u1ed4\u1ed6\u1ed8\u01a0\u1eda\u1edc\u1ede\u1ee0\u1ee2\u00da\u00d9\u1ee6\u0168\u1ee4\u01af\u1ee8\u1eea\u1eec\u1eee\u1ef0\u00dd\u1ef2\u1ef6\u1ef8"
			+ "\u1ef4\u2026";

	// Two decimal digits per glyph: advance width in pixels
	private static final String WIDTHS = "0706070706060707040506050807080608070606070610060606060605060604"
			+ "0606020305020806060606040504060608060605060606060606060606060404"
			+ "0404041004040604040404050504080611080708080502040804030608060606"
			+ "0606060606060606060606060606060606060606060606060603030303020606"
			+ "0606060606060606060606060606060606060606070707070707060606060607"
			+ "0707070707070707070707070707070706060606060606060606060404040404"
			+ "0808080808080808080808080808080808070707070708080808080806060606"
			+ "0609";

	private static final short[] INDEX;
	private static final int[] WIDTH;

	static {
		int max = 0;
		for (int i = 0; i < CHARSET.length(); i++) {
			max = Math.max(max, CHARSET.charAt(i));
		}
		INDEX = new short[max + 1];
		java.util.Arrays.fill(INDEX, (short) -1);
		WIDTH = new int[CHARSET.length()];
		for (int i = 0; i < CHARSET.length(); i++) {
			INDEX[CHARSET.charAt(i)] = (short) i;
			WIDTH[i] = (WIDTHS.charAt(i * 2) - '0') * 10 + (WIDTHS.charAt(i * 2 + 1) - '0');
		}
	}

	private static final Paint paint = new Paint();
	private static final Rect src = new Rect();
	private static final Rect dst = new Rect();
	private static Bitmap atlas;
	private static int filterColor;
	private static PorterDuffColorFilter filter;

	private BitmapFont() {
	}

	/** Loads the atlas once. Returns false if it is unavailable. */
	static synchronized boolean load() {
		if (atlas != null) {
			return true;
		}
		Context context = ContextHolder.getAppContext();
		try (InputStream is = context.getAssets().open(ATLAS_PATH)) {
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inScaled = false;
			options.inPreferredConfig = Bitmap.Config.ARGB_8888;
			atlas = BitmapFactory.decodeStream(is, null, options);
		} catch (IOException e) {
			Log.e(TAG, "Can't load bitmap font atlas", e);
		}
		paint.setAntiAlias(false);
		paint.setFilterBitmap(false);
		paint.setDither(false);
		return atlas != null;
	}

	static boolean supports(char c) {
		return c < INDEX.length && INDEX[c] >= 0;
	}

	/** True if every char has a glyph; otherwise the caller falls back to the system font. */
	static boolean supports(String s) {
		for (int i = 0, n = s.length(); i < n; i++) {
			if (!supports(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	static int charWidth(char c, boolean bold) {
		return WIDTH[INDEX[c]] + (bold ? 1 : 0);
	}

	static int stringWidth(String s, boolean bold) {
		int w = 0;
		for (int i = 0, n = s.length(); i < n; i++) {
			w += WIDTH[INDEX[s.charAt(i)]];
		}
		return bold && w > 0 ? w + 1 : w;
	}

	/**
	 * @param x     left edge of the text
	 * @param top   top edge of the 15px glyph row
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
			int idx = INDEX[s.charAt(i)];
			int w = WIDTH[idx];
			int sx = (idx % COLUMNS) * CELL_W;
			int sy = (idx / COLUMNS) * CELL_H;
			src.set(sx, sy, sx + w, sy + CELL_H);
			dst.set(cx, top, cx + w, top + CELL_H);
			canvas.drawBitmap(atlas, src, dst, paint);
			if (bold) {
				dst.offset(1, 0);
				canvas.drawBitmap(atlas, src, dst, paint);
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
