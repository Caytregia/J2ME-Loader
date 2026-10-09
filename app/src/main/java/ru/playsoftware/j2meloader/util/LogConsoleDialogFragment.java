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

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import ru.playsoftware.j2meloader.R;

/**
 * Shows the output captured by {@link ConsoleOutput}.
 * Double tap on a line copies it to the clipboard.
 */
public class LogConsoleDialogFragment extends DialogFragment implements ConsoleOutput.LogListener {
	private static final long UPDATE_DELAY_MS = 200;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable updateRunnable = this::updateLog;
	private TextView tvLog;
	private ScrollView scrollView;
	private GestureDetector gestureDetector;
	private boolean updatePending;

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_log_console, null);
		tvLog = view.findViewById(R.id.tvLog);
		scrollView = view.findViewById(R.id.scrollViewLog);
		tvLog.setText(ConsoleOutput.getLog());
		setupDoubleTapToCopy();

		AlertDialog dialog = new AlertDialog.Builder(requireActivity())
				.setTitle(R.string.log_console)
				.setView(view)
				.setPositiveButton(android.R.string.ok, null)
				.setNeutralButton(R.string.clear, null)
				.create();
		// the "Clear" button must not close the dialog
		dialog.setOnShowListener(d -> ((AlertDialog) d).getButton(AlertDialog.BUTTON_NEUTRAL)
				.setOnClickListener(v -> ConsoleOutput.clear()));
		return dialog;
	}

	@Override
	public void onResume() {
		super.onResume();
		ConsoleOutput.setListener(this);
		scrollToBottom();
	}

	@Override
	public void onPause() {
		ConsoleOutput.setListener(null);
		handler.removeCallbacks(updateRunnable);
		super.onPause();
	}

	@Override
	public void onLogUpdate(String log) {
		// the log can be updated very often, so refresh the view at most every 200 ms
		if (!updatePending) {
			updatePending = true;
			handler.postDelayed(updateRunnable, UPDATE_DELAY_MS);
		}
	}

	@Override
	public void onLogClear() {
		handler.post(() -> {
			if (tvLog != null) {
				tvLog.setText("");
			}
		});
	}

	private void updateLog() {
		if (tvLog != null) {
			tvLog.setText(ConsoleOutput.getLog());
			scrollToBottom();
		}
		updatePending = false;
	}

	private void scrollToBottom() {
		if (scrollView != null) {
			scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
		}
	}

	private void setupDoubleTapToCopy() {
		gestureDetector = new GestureDetector(requireContext(),
				new GestureDetector.SimpleOnGestureListener() {
					@Override
					public boolean onDoubleTap(MotionEvent e) {
						copyLineAt(e.getX(), e.getY());
						return true;
					}
				});
		tvLog.setOnTouchListener((v, event) -> {
			gestureDetector.onTouchEvent(event);
			return false;
		});
	}

	private void copyLineAt(float x, float y) {
		if (tvLog == null) {
			return;
		}
		CharSequence text = tvLog.getText();
		if (text.length() == 0) {
			return;
		}
		try {
			int offset = tvLog.getOffsetForPosition(x, y);
			if (offset < 0 || offset > text.length()) {
				return;
			}
			int start = offset;
			while (start > 0 && text.charAt(start - 1) != '\n') {
				start--;
			}
			int end = offset;
			while (end < text.length() && text.charAt(end) != '\n') {
				end++;
			}
			if (start >= end) {
				return;
			}
			String line = text.subSequence(start, end).toString().trim();
			if (line.isEmpty()) {
				return;
			}
			ClipboardManager clipboard = (ClipboardManager)
					requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
			clipboard.setPrimaryClip(ClipData.newPlainText("log_line", line));
			Toast.makeText(getContext(), R.string.copied, Toast.LENGTH_SHORT).show();
		} catch (Exception ignored) {
		}
	}
}
