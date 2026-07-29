/*
 * Multi-tab support patch.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package javax.microedition.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Holds the list of currently open {@link MidletTab}s and hands out new tab IDs.
 * Not thread-safe by design: all mutating calls are expected to happen on the UI thread
 * (from {@link MicroActivity}), same as the vanilla single-tab code did.
 */
public class TabManager {
	/** Hard cap on concurrently open tabs, mirrors the limit observed in the reference build. */
	public static final int MAX_TABS = 100;

	private final List<MidletTab> tabs = new ArrayList<>();
	private int nextTabId = 0;
	private int activeTabId = -1;

	/** Create a tab with an explicit id (used when restoring a previously saved session). */
	public synchronized MidletTab createTab(int id, String appName, String appPath) {
		if (tabs.size() >= MAX_TABS) {
			return null;
		}
		MidletTab tab = new MidletTab(id, appName, appPath);
		tabs.add(tab);
		if (id >= nextTabId) {
			nextTabId = id + 1;
		}
		return tab;
	}

	/** Create a tab with an auto-assigned id. */
	public synchronized MidletTab createTab(String appName, String appPath) {
		if (tabs.size() >= MAX_TABS) {
			return null;
		}
		int id = nextTabId++;
		MidletTab tab = new MidletTab(id, appName, appPath);
		tabs.add(tab);
		return tab;
	}

	public synchronized MidletTab getTab(int id) {
		for (MidletTab tab : tabs) {
			if (tab.id == id) {
				return tab;
			}
		}
		return null;
	}

	public synchronized List<MidletTab> getTabs() {
		return new ArrayList<>(tabs);
	}

	public synchronized int size() {
		return tabs.size();
	}

	public synchronized void removeTab(int id) {
		Iterator<MidletTab> it = tabs.iterator();
		while (it.hasNext()) {
			if (it.next().id == id) {
				it.remove();
				return;
			}
		}
	}

	public synchronized void setActiveTab(int id) {
		this.activeTabId = id;
		javax.microedition.util.ContextHolder.setActiveTabId(id);
	}

	public synchronized MidletTab getActiveTab() {
		return getTab(activeTabId);
	}

	public synchronized int getActiveTabId() {
		return activeTabId;
	}

	/**
	 * Returns the tab that follows the tab with the given id, wrapping around to the
	 * first tab if {@code currentId} was the last one. Used to pick which tab to show
	 * after the current one is closed. Returns {@code null} if there are no tabs left.
	 */
	public synchronized MidletTab getNextTab(int currentId) {
		if (tabs.isEmpty()) {
			return null;
		}
		Iterator<MidletTab> it = tabs.iterator();
		while (it.hasNext()) {
			MidletTab tab = it.next();
			if (tab.id == currentId) {
				if (it.hasNext()) {
					return it.next();
				}
				break;
			}
		}
		return tabs.get(0);
	}

	public synchronized void sortTabs() {
		Collections.sort(tabs, Comparator.comparingInt(t -> t.id));
	}
}
