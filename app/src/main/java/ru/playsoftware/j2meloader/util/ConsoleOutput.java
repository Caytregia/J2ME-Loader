/*
 *  Copyright 2020 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.playsoftware.j2meloader.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Keeps a copy of everything the MIDlet prints to System.out / System.err,
 * so it can be shown in the log console.
 */
public class ConsoleOutput {
	private static final int MAX_CHARS = 200000;
	private static final StringBuilder buffer = new StringBuilder();
	private static boolean installed;
	private static LogListener listener;

	public interface LogListener {
		void onLogUpdate(String log);

		void onLogClear();
	}

	public static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		final PrintStream original = System.out;
		PrintStream stream = new PrintStream(new OutputStream() {
			@Override
			public void write(int b) {
				original.write(b);
				append(String.valueOf((char) b));
			}

			@Override
			public void write(byte[] b, int off, int len) {
				original.write(b, off, len);
				append(new String(b, off, len));
			}
		}, true);
		System.setOut(stream);
		System.setErr(stream);
	}

	public static synchronized void setListener(LogListener l) {
		listener = l;
	}

	public static synchronized String getLog() {
		return buffer.toString();
	}

	public static synchronized void clear() {
		buffer.setLength(0);
		if (listener != null) {
			listener.onLogClear();
		}
	}

	private static synchronized void append(String s) {
		buffer.append(s);
		if (buffer.length() > MAX_CHARS) {
			buffer.delete(0, buffer.length() - MAX_CHARS);
		}
		if (listener != null) {
			listener.onLogUpdate(buffer.toString());
		}
	}
}
