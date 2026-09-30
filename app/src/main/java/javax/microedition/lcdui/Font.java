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

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.util.Arrays;

import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.config.ProfileModel;

public class Font {
	public static final int FACE_MONOSPACE = 32;
	public static final int FACE_PROPORTIONAL = 64;
	public static final int FACE_SYSTEM = 0;

	public static final int SIZE_LARGE = 16;
	public static final int SIZE_MEDIUM = 0;
	public static final int SIZE_SMALL = 8;

	public static final int STYLE_BOLD = 1;
	public static final int STYLE_ITALIC = 2;
	public static final int STYLE_PLAIN = 0;
	public static final int STYLE_UNDERLINED = 4;

	public static final int FONT_STATIC_TEXT = 0;
	public static final int FONT_INPUT_TEXT = 1;

	private static final int[] SCREEN_SIZES = {128, 176, 220, 320};
	private static final int[] FONT_SIZES = {
			9, 13, 15, // 128
			13, 15, 20, // 176
			15, 18, 22, // 220
			18, 22, 26, // 320
	};

	private static final int FONT_COUNT = 3 * 3 * 2 * 2 * 2;
	private static final Font[] fonts = new Font[FONT_COUNT];
	private static final float[] sizes = {22, 18, 26};

	private static boolean antiAlias;
	private static boolean bitmapEnabled;

	final Paint paint = new Paint();
	final float ascent;
	final float descent;
	private final int height;
	private final int face;
	private final int style;
	private final int size;
	final boolean bitmap;

	@SuppressLint("WrongConstant")
	public Font(int face, int style, int size, float height) {
		this.face = face;
		this.style = style;
		this.size = size;
		this.bitmap = bitmapEnabled;

		Typeface family;
		switch (face) {
			case FACE_MONOSPACE:
				family = Typeface.MONOSPACE;
				break;
			case FACE_PROPORTIONAL:
				family = Typeface.SANS_SERIF;
				break;
			default:
				family = Typeface.DEFAULT;
		}

		paint.setColor(Color.BLACK);
		paint.setTypeface(Typeface.create(family, style & Typeface.BOLD_ITALIC));
		paint.setAntiAlias(antiAlias);
		paint.setStyle(Paint.Style.FILL);
		paint.setUnderlineText((style & STYLE_UNDERLINED) != 0);

		// at first, just set the size (no matter what is put here)
		paint.setTextSize(height);
		// and now we set the size equal to the given one (in pixels)
		paint.setTextSize(height * height / paint.getFontSpacing());

		Paint.FontMetrics fm = new Paint.FontMetrics();
		if (bitmap) {
			// metrics of the bitmap glyph row; the system paint is only a fallback
			this.height = BitmapFont.getCellHeight();
			this.ascent = -BitmapFont.getAscent();
			this.descent = BitmapFont.getDescent();
			return;
		}
		this.height = (int) Math.ceil(paint.getFontMetrics(fm));
		this.ascent = fm.ascent;
		this.descent = fm.descent;
	}

	public static Font getFont(int fontSpecifier) {
		return getDefaultFont();
	}

	public static Font getFont(int face, int style, int size) {
		int index = ((face >> 5) * 3 + (size >> 3) << 3) + style;
		Font font = fonts[index];

		if (font == null) {
			float height = bitmapEnabled ? BitmapFont.getCellHeight() : sizes[size / 8];
			font = new Font(face, style, size, height);
			fonts[index] = font;
		}

		return font;
	}

	public static Font getDefaultFont() {
		return getFont(FACE_SYSTEM, STYLE_PLAIN, SIZE_MEDIUM);
	}

	public int getFace() {
		return face;
	}

	public int getStyle() {
		return style;
	}

	public int getSize() {
		return size;
	}

	public boolean isUnderlined() {
		return paint.isUnderlineText();
	}

	public int getHeight() {
		return height;
	}

	public int getBaselinePosition() {
		if (bitmap) {
			return BitmapFont.getAscent();
		}
		return (int) Math.ceil(-paint.ascent());
	}

	public int charWidth(char c) {
		if (bitmap && BitmapFont.supports(c)) {
			return BitmapFont.charWidth(c, isBoldStyle());
		}
		return (int) Math.ceil(paint.measureText(new char[]{c}, 0, 1));
	}

	public int charsWidth(char[] ch, int offset, int length) {
		if (bitmap) {
			return bitmapStringWidth(new String(ch, offset, length));
		}
		return (int) Math.ceil(paint.measureText(ch, offset, length));
	}

	public int stringWidth(String text) {
		if (bitmap) {
			return bitmapStringWidth(text);
		}
		return (int) Math.ceil(paint.measureText(text));
	}

	public int substringWidth(String str, int offset, int len) {
		if (bitmap) {
			return bitmapStringWidth(str.substring(offset, offset + len));
		}
		return (int) paint.measureText(str, offset, offset + len);
	}

	/**
	 * Width of text drawn with the bitmap font. Chars that have no glyph in the atlas
	 * are measured (and drawn, see Graphics) with the system font, run by run.
	 */
	int bitmapStringWidth(String s) {
		boolean bold = isBoldStyle();
		int total = 0;
		int n = s.length();
		int i = 0;
		while (i < n) {
			boolean supported = BitmapFont.supports(s.charAt(i));
			int j = i + 1;
			while (j < n && BitmapFont.supports(s.charAt(j)) == supported) {
				j++;
			}
			String run = s.substring(i, j);
			total += supported ? BitmapFont.stringWidth(run, bold)
					: (int) Math.ceil(paint.measureText(run));
			i = j;
		}
		return total;
	}

	boolean isBoldStyle() {
		return (style & STYLE_BOLD) != 0;
	}

	public boolean isBold() {
		return style == STYLE_BOLD;
	}

	public boolean isPlain() {
		return style == STYLE_PLAIN;
	}

	public boolean isItalic() {
		return style == STYLE_ITALIC;
	}

	public static void applySettings(ProfileModel params) {
		antiAlias = params.fontAA;
		bitmapEnabled = params.fontBitmap && BitmapFont.load(params.fontBitmapStyle);

		float small = params.fontSizeSmall;
		float medium = params.fontSizeMedium;
		float large = params.fontSizeLarge;

		int screen = Math.max(params.screenWidth, params.screenHeight);
		if (medium <= 0) medium = Font.getFontSizeForResolution(1, screen);
		if (small <= 0) small = Font.getFontSizeForResolution(0, screen);
		if (large <= 0) large = Font.getFontSizeForResolution(2, screen);

		if (params.fontApplyDimensions) {
			DisplayMetrics metrics = ContextHolder.getAppContext().getResources().getDisplayMetrics();
			medium = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, medium, metrics);
			small = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, small, metrics);
			large = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, large, metrics);
		}
		sizes[0] = medium;
		sizes[1] = small;
		sizes[2] = large;

		Arrays.fill(fonts, null);
	}

	private static int getFontSizeForResolution(int type, int size) {
		if (size > 0) {
			for (int i = 0; i < SCREEN_SIZES.length; i++) {
				if (SCREEN_SIZES[i] >= size) {
					return FONT_SIZES[i * 3 + type];
				}
			}
		}
		return FONT_SIZES[FONT_SIZES.length - 3 + type];
	}
}
