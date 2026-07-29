/*
 * Multi-tab support patch.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package javax.microedition.shell;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Per-tab network proxy settings. Persisted as a small JSON file inside the tab's
 * data directory so a tab remembers its proxy across restarts.
 */
public class ProxyConfig {
	private static final String TAG = ProxyConfig.class.getName();
	private static final String FILE_NAME = "proxy.json";

	public boolean enabled;
	public String type = "HTTP";
	public String host = "";
	public int port;
	public String user = "";
	public String pass = "";

	public ProxyConfig() {
	}

	public ProxyConfig(boolean enabled, String type, String host, int port, String user, String pass) {
		this.enabled = enabled;
		this.type = type;
		this.host = host;
		this.port = port;
		this.user = user;
		this.pass = pass;
	}

	public static ProxyConfig fromJSON(JSONObject o) throws JSONException {
		return new ProxyConfig(
				o.optBoolean("enabled", false),
				o.optString("type", "HTTP"),
				o.optString("host", ""),
				o.optInt("port", 0),
				o.optString("user", ""),
				o.optString("pass", "")
		);
	}

	public JSONObject toJSON() throws JSONException {
		JSONObject o = new JSONObject();
		o.put("enabled", enabled);
		o.put("type", type);
		o.put("host", host);
		o.put("port", port);
		o.put("user", user);
		o.put("pass", pass);
		return o;
	}

	/** Load the proxy config saved for a tab's data directory, or {@code null} if none exists / on error. */
	public static ProxyConfig load(File dataDir) {
		File file = new File(dataDir, FILE_NAME);
		if (!file.exists()) {
			return null;
		}
		try (FileInputStream fis = new FileInputStream(file)) {
			byte[] buf = new byte[(int) file.length()];
			int read = fis.read(buf);
			if (read <= 0) {
				return null;
			}
			JSONObject o = new JSONObject(new String(buf, 0, read, "UTF-8"));
			return fromJSON(o);
		} catch (IOException | JSONException e) {
			Log.w(TAG, "Failed to load proxy config from " + file, e);
			return null;
		}
	}

	/** Save this proxy config into a tab's data directory. */
	public void save(File dataDir) {
		if (!dataDir.exists()) {
			dataDir.mkdirs();
		}
		File file = new File(dataDir, FILE_NAME);
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(toJSON().toString().getBytes("UTF-8"));
		} catch (IOException | JSONException e) {
			Log.w(TAG, "Failed to save proxy config to " + file, e);
		}
	}

	/** Convert to a java.net.Proxy usable by socket/HTTP connection code, or Proxy.NO_PROXY if disabled/invalid. */
	public Proxy toJavaNetProxy() {
		if (!enabled || host == null || host.isEmpty() || port <= 0) {
			return Proxy.NO_PROXY;
		}
		Proxy.Type proxyType = "SOCKS".equalsIgnoreCase(type) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
		return new Proxy(proxyType, InetSocketAddress.createUnresolved(host, port));
	}
}
