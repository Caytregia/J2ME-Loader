/*
 * Multi-tab support patch.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package javax.microedition.shell;

import javax.microedition.lcdui.Displayable;

/**
 * Represents a single running MIDlet instance ("tab") when multi-tab mode is enabled.
 * A tab owns its own data directory (RMS storage), its own {@link ProxyConfig} and,
 * once launched, its own {@link MicroLoader} and {@link MidletThread}.
 */
public class MidletTab {
	/** Stable identifier for this tab, assigned by {@link TabManager}. Never reused while alive. */
	public final int id;
	/** Display name of the MIDlet running in this tab (shown on the tab bar). */
	public String appName;
	/** Path to the app's JAR/converted directory (same meaning as MicroActivity#appPath). */
	public String appPath;
	/** Main class (MIDlet-1 entry point) that was launched for this tab, if known. */
	public String mainClass;
	/** Per-tab data directory: RMS records, cache and proxy.json live here, isolated from other tabs. */
	public String dataDir;
	/** Per-tab network proxy settings, loaded from/saved to {@code dataDir}. */
	public ProxyConfig proxyConfig;
	/** The Displayable currently shown for this tab; swapped in/out of the shared container on tab switch. */
	public Displayable currentDisplayable;
	/** MicroLoader bound to this tab's appPath; created lazily when the tab is (re)launched. */
	public transient MicroLoader loader;

	public MidletTab(int id, String appName, String appPath) {
		this.id = id;
		this.appName = appName;
		this.appPath = appPath;
	}

	@Override
	public String toString() {
		return appName != null ? appName : ("Tab #" + id);
	}
}
