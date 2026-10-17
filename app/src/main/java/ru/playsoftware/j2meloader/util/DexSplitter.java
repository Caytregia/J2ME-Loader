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

import com.android.dx.command.dexer.Main;
import com.android.dx.rop.code.RegisterSpec;
import com.android.dx.rop.cst.CstType;
import com.android.dx.rop.type.Prototype;
import com.android.dx.rop.type.Type;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Converts a jar into one or several dex files.
 * <p>
 * The dexer keeps every class of the jar in memory until the dex is written, so big jars
 * (or jars that contain several copies of a game) can run out of memory. Here the classes
 * are converted in batches of a given size and every batch is written to its own dex file:
 * converted.dex, converted2.dex, converted3.dex, ...
 */
public final class DexSplitter {
	/** Name of the first dex file. The same as Config.MIDLET_DEX_FILE without the slash. */
	private static final String FIRST_DEX = "converted.dex";

	private DexSplitter() {
	}

	/** Name of the dex file with the given index (0 is the first file). */
	public static String getDexName(int index) {
		return index == 0 ? FIRST_DEX : "converted" + (index + 1) + ".dex";
	}

	/** All dex files of an installed app, in loading order. */
	public static List<File> listDexFiles(File appDir) {
		ArrayList<File> files = new ArrayList<>();
		for (int i = 0; ; i++) {
			File f = new File(appDir, getDexName(i));
			if (!f.isFile()) {
				break;
			}
			files.add(f);
		}
		return files;
	}

	/**
	 * @param jar          source jar
	 * @param outDir       directory for the dex files
	 * @param classesPerDex max number of classes in one dex file; 0 or less - one dex for the whole jar
	 * @param workDir      directory for temporary files
	 * @return number of dex files
	 */
	public static int convert(File jar, File outDir, int classesPerDex, File workDir) throws IOException {
		if (classesPerDex <= 0) {
			dex(jar, new File(outDir, FIRST_DEX));
			return 1;
		}
		ZipFile zip = new ZipFile(jar);
		List<FileHeader> classes = new ArrayList<>();
		for (FileHeader header : zip.getFileHeaders()) {
			if (!header.isDirectory() && header.getFileName().toLowerCase().endsWith(".class")) {
				classes.add(header);
			}
		}
		if (classes.size() <= classesPerDex) {
			dex(jar, new File(outDir, FIRST_DEX));
			return 1;
		}
		Collections.sort(classes, (a, b) -> a.getFileName().compareTo(b.getFileName()));
		int parts = (classes.size() + classesPerDex - 1) / classesPerDex;
		File partJar = new File(workDir, "dex_part.jar");
		try {
			for (int part = 0; part < parts; part++) {
				int from = part * classesPerDex;
				int to = Math.min(classes.size(), from + classesPerDex);
				writeJar(zip, classes.subList(from, to), partJar);
				dex(partJar, new File(outDir, getDexName(part)));
				// free everything the dexer kept for this batch
				partJar.delete();
				clearDexerTables();
			}
		} finally {
			//noinspection ResultOfMethodCallIgnored
			partJar.delete();
			clearDexerTables();
		}
		return parts;
	}

	private static void dex(File jar, File out) throws IOException {
		Main.main(new String[]{"--no-optimize", "--core-library",
				"--output=" + out.getAbsolutePath(), jar.getAbsolutePath()});
	}

	private static void writeJar(ZipFile zip, List<FileHeader> entries, File target) throws IOException {
		try (OutputStream os = new FileOutputStream(target);
			 ZipOutputStream zo = new ZipOutputStream(os)) {
			zo.setLevel(Deflater.NO_COMPRESSION);
			byte[] buf = new byte[8192];
			for (FileHeader header : entries) {
				zo.putNextEntry(new ZipEntry(header.getFileName()));
				try (ZipInputStream is = zip.getInputStream(header)) {
					int n;
					while ((n = is.read(buf)) != -1) {
						zo.write(buf, 0, n);
					}
				}
				zo.closeEntry();
			}
		}
	}

	/** The dexer caches types, prototypes and registers in static tables; drop them between batches. */
	private static void clearDexerTables() {
		RegisterSpec.clearInternTable();
		CstType.clearInternTable();
		Prototype.clearInternTable();
		Type.clearInternTable();
		System.gc();
	}
}
