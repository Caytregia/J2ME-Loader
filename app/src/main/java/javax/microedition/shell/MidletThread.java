/*
 *  Copyright 2020 Yury Kharchenko
 *  Copyright 2022-2023 Arman Jussupgaliyev
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
 *
 *  --- Multi-tab support patch ---
 *  Instead of a single static `instance`, running MIDlets are now tracked in a
 *  Map<Integer, MidletThread> keyed by tab id, each running inside its own
 *  ThreadGroup for basic isolation. Destroying one tab's MIDlet no longer kills
 *  the whole process unless it was the last remaining tab.
 */

package javax.microedition.shell;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.config.Config;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final UncaughtExceptionHandler uncaughtExceptionHandler = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after destroy app called", e);

	private static final int INIT = 0;
	private static final int START = 1;
	private static final int PAUSE = 2;
	private static final int DESTROY = 3;
	private static final int UNINITIALIZED = 0;
	private static final int STARTED = 1;
	private static final int PAUSED = 2;
	private static final int DESTROYED = 3;
	public static String[] startAfterDestroy;

	/** All currently running MIDlet instances, keyed by tab id. */
	private static final Map<Integer, MidletThread> instances = new ConcurrentHashMap<>();
	/** Thread groups per tab, kept alive for the tab's lifetime so per-tab threads can be enumerated/interrupted. */
	private static final Map<Integer, ThreadGroup> tabThreadGroups = new ConcurrentHashMap<>();

	private final MicroLoader microLoader;
	private final String mainClass;
	private final int tabId;
	private final String dataDir;
	private final ProxyConfig proxyConfig;
	private MIDlet midlet;
	private final Handler handler;
	private int state;

	private MidletThread(MicroLoader microLoader, String mainClass, int tabId, String dataDir, ProxyConfig proxyConfig) {
		// Note: HandlerThread only exposes (String) / (String, int priority) constructors,
		// there is no (ThreadGroup, String) overload, so we can't parent this thread to the
		// tab's ThreadGroup directly. createTabThreadGroup(tabId) still tracks a ThreadGroup
		// per tab for bookkeeping/lookup purposes even though it isn't the OS-level parent group.
		super("MidletMain-" + tabId);
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		this.tabId = tabId;
		this.dataDir = dataDir;
		this.proxyConfig = proxyConfig;
		start();
		handler = new Handler(getLooper(), this);
		handler.obtainMessage(INIT).sendToTarget();
	}

	private static synchronized ThreadGroup createTabThreadGroup(int tabId) {
		ThreadGroup group = new ThreadGroup("MidletTab-" + tabId);
		tabThreadGroups.put(tabId, group);
		return group;
	}

	/** Full form: explicitly launch (or relaunch) a MIDlet inside a given tab, with its own data dir and proxy. */
	static void create(MicroLoader microLoader, String mainClass, int tabId, String dataDir, ProxyConfig proxyConfig) {
		MidletThread existing = instances.get(tabId);
		if (existing != null) {
			Log.w(TAG, "create() called for tab " + tabId + " while an instance is already running; ignoring");
			return;
		}
		createTabThreadGroup(tabId);
		MidletThread thread = new MidletThread(microLoader, mainClass, tabId, dataDir, proxyConfig);
		instances.put(tabId, thread);
	}

	/**
	 * Legacy convenience form kept for source compatibility with single-instance callers:
	 * runs against the currently active tab (or tab 0 if multi-tab was never engaged),
	 * using the app's default data dir and no proxy.
	 */
	static void create(MicroLoader microLoader, String mainClass) {
		int tabId = ContextHolder.getActiveTabId();
		if (tabId < 0) {
			tabId = 0;
			ContextHolder.setActiveTabId(0);
		}
		create(microLoader, mainClass, tabId, AppClassLoader.getDataDir(), null);
	}

	@Nullable
	static MidletThread getInstance(int tabId) {
		return instances.get(tabId);
	}

	/** Returns the MidletThread whose own HandlerThread the caller is currently running on, if any. */
	@Nullable
	private static MidletThread resolveCurrent() {
		Thread current = Thread.currentThread();
		for (MidletThread mt : instances.values()) {
			if (mt == current) {
				return mt;
			}
		}
		// Fall back to the active tab (covers calls made from the UI thread).
		return instances.get(ContextHolder.getActiveTabId());
	}

	public static void notifyDestroyed() {
		MidletThread mt = resolveCurrent();
		if (mt != null) {
			notifyDestroyed(mt.tabId);
		}
	}

	public static void notifyDestroyed(int tabId) {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		MidletThread mt = instances.remove(tabId);
		if (mt != null) {
			mt.state = DESTROYED;
		}
		tabThreadGroups.remove(tabId);
		MicroActivity activity = ContextHolder.getActivity();
		boolean lastTab = instances.isEmpty();
		if (activity != null) {
			if (lastTab) {
				activity.finish();
			} else {
				activity.onTabDestroyed(tabId);
			}
		}
		if (startAfterDestroy != null) {
			Config.startApp(ContextHolder.getActivity(), startAfterDestroy[0], startAfterDestroy[1], false, startAfterDestroy[2]);
			startAfterDestroy = null;
		}
		if (lastTab) {
			Process.killProcess(Process.myPid());
		}
	}

	public static void notifyPaused() {
		MidletThread mt = resolveCurrent();
		if (mt != null) {
			mt.state = PAUSED;
		}
	}

	static void pauseApp() {
		MidletThread mt = instances.get(ContextHolder.getActiveTabId());
		if (mt != null) {
			mt.handler.obtainMessage(PAUSE).sendToTarget();
		}
	}

	public static void resumeApp() {
		resumeApp(ContextHolder.getActiveTabId());
	}

	public static void resumeApp(int tabId) {
		MicroActivity activity = ContextHolder.getActivity();
		MidletThread mt = instances.get(tabId);
		if (mt != null && activity != null && activity.isVisible()) {
			mt.handler.obtainMessage(START).sendToTarget();
		}
	}

	static void destroyApp() {
		destroyApp(ContextHolder.getActiveTabId());
	}

	static void destroyApp(int tabId) {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		boolean lastTab = instances.size() <= 1;
		if (lastTab) {
			new Thread(() -> {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				Process.killProcess(Process.myPid());
			}, "ForceDestroyTimer").start();
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null && tabId == ContextHolder.getActiveTabId()) {
			Displayable current = activity.getCurrent();
			if (current instanceof Canvas) {
				Canvas canvas = (Canvas) current;
				canvas.postKeyPressed(Canvas.KEY_END);
				canvas.postKeyReleased(Canvas.KEY_END);
			}
		}
		MidletThread mt = instances.get(tabId);
		if (mt != null) {
			mt.handler.obtainMessage(DESTROY).sendToTarget();
		}
	}

	public String getDataDir() {
		return dataDir;
	}

	public ProxyConfig getProxyConfig() {
		return proxyConfig;
	}

	public int getTabId() {
		return tabId;
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		switch (msg.what) {
			case INIT:
				if (state != UNINITIALIZED) {
					break;
				}
				try {
					midlet = microLoader.loadMIDlet(this.mainClass);
					state = PAUSED;
				} catch (Throwable t) {
					throw new RuntimeException("Init midlet failed", t);
				}
				break;
			case START:
				if (state != PAUSED) {
					break;
				}
				try {
					state = STARTED;
					midlet.startApp();
				} catch (MIDletStateChangeException e) {
					state = PAUSED;
					Log.w(TAG, "Midlet doesn't want to start!", e);
				} catch (Throwable t) {
					state = DESTROYED;
					throw new RuntimeException("Failed startApp", t);
				}
				break;
			case PAUSE:
				if (state != STARTED) {
					break;
				}
				try {
					midlet.pauseApp();
					state = PAUSED;
				} catch (Throwable t) {
					state = DESTROYED;
					try {
						midlet.destroyApp(true);
					} catch (MIDletStateChangeException ignored) {}
					throw new RuntimeException("Filed pauseApp", t);
				}
				break;
			case DESTROY:
				if (state == DESTROYED) {
					notifyDestroyed(tabId);
					break;
				}
				state = DESTROYED;
				try {
					midlet.destroyApp(true);
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "Midlet didn't want to die!", e);
				} catch (Throwable t) {
					Log.e(TAG, "Filed destroyApp:", t);
				}
				notifyDestroyed(tabId);
				break;
		}
		return true;
	}
}
