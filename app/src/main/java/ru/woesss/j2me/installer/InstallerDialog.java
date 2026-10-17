/*
 *  Copyright 2020-2022 Yury Kharchenko
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

package ru.woesss.j2me.installer;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.databinding.DialogInstallerBinding;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

public class InstallerDialog extends DialogFragment {
	private static final String ARG_URI = "InstallerDialog.uri";
	private static final String ARG_ID = "InstallerDialog.id";
	private static final String PREF_SPLIT_DEX = "installer_split_dex";
	private static final String PREF_SPLIT_DEX_COUNT = "installer_split_dex_count";
	private static final int DEFAULT_SPLIT_DEX_COUNT = 500;
	private final CompositeDisposable compositeDisposable = new CompositeDisposable();

	private AppRepository appRepository;
	private Button btnOk;
	private Button btnClose;
	private Button btnRun;
	private AppInstaller installer;
	private AlertDialog mDialog;

	private DialogInstallerBinding binding;

	private final ActivityResultLauncher<String> openFileLauncher = registerForActivityResult(
			FileUtils.getFilePicker(),
			this::onPickFileResult);

	/**
	 * @param uri original uri from intent.
	 * @return A new instance of fragment InstallerDialog.
	 */
	public static InstallerDialog newInstance(Uri uri) {
		InstallerDialog fragment = new InstallerDialog();
		Bundle args = new Bundle();
		args.putParcelable(ARG_URI, uri);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	public static InstallerDialog newInstance(int id) {
		InstallerDialog fragment = new InstallerDialog();
		Bundle args = new Bundle();
		args.putInt(ARG_ID, id);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		AppListModel appListModel = new ViewModelProvider(requireActivity()).get(AppListModel.class);
		appRepository = appListModel.getAppRepository();
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			dismissAllowingStateLoss();
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		binding = DialogInstallerBinding.inflate(LayoutInflater.from(getContext()));
		mDialog = new AlertDialog.Builder(requireActivity(), getTheme())
				.setIcon(R.mipmap.ic_launcher)
				.setView(binding.getRoot())
				.setTitle("MIDlet installer")
				.setMessage("")
				.setCancelable(false)
				.setPositiveButton(R.string.install, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.START_CMD, null)
				.create();
		return mDialog;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		binding = null;
	}

	@Override
	public void onDestroy() {
		compositeDisposable.dispose();
		super.onDestroy();
	}

	@Override
	public void onStart() {
		super.onStart();
		if (installer != null) {
			return;
		}
		btnOk = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
		btnClose = mDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
		btnRun = mDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
		hideButtons();
		Bundle args = requireArguments();
		Uri uri = args.getParcelable(ARG_URI);
		if (uri != null) {
			installApp(null, uri);
			return;
		}
		int id = args.getInt(ARG_ID);
		reinstallApp(id);
	}

	private void installApp(String path, Uri uri) {
		installer = new AppInstaller(path, uri, requireActivity().getApplication(), appRepository);
		btnClose.setOnClickListener(v -> {
			installer.deleteTemp();
			installer.clearCache();
			dismiss();
		});
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void reinstallApp(int id) {
		installer = new AppInstaller(id, requireActivity().getApplication(), appRepository);
		btnClose.setOnClickListener(v -> {
			installer.deleteTemp();
			installer.clearCache();
			dismiss();
		});
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	@SuppressLint("CheckResult")
	private void onPickFileResult(Uri uri) {
		if (uri == null) {
			return;
		}
		Disposable disposable = installer.updateInfo(uri)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void hideProgress() {
		binding.installationProgress.setVisibility(View.GONE);
		binding.installationStatus.setVisibility(View.GONE);
	}

	private void showProgress() {
		binding.installationProgress.setVisibility(View.VISIBLE);
		binding.installationStatus.setVisibility(View.VISIBLE);
	}

	private void hideButtons() {
		btnOk.setVisibility(View.GONE);
		btnClose.setVisibility(View.GONE);
		btnRun.setVisibility(View.GONE);
	}

	private void showButtons() {
		btnOk.setVisibility(View.VISIBLE);
		btnClose.setVisibility(View.VISIBLE);
	}

	private void showSplitOptions() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
		boolean split = prefs.getBoolean(PREF_SPLIT_DEX, false);
		binding.splitDexCheck.setOnCheckedChangeListener(null);
		binding.splitDexCheck.setChecked(split);
		binding.splitDexCount.setText(String.valueOf(prefs.getInt(PREF_SPLIT_DEX_COUNT, DEFAULT_SPLIT_DEX_COUNT)));
		setSplitCountVisible(split);
		binding.splitDexCheck.setOnCheckedChangeListener((button, checked) -> setSplitCountVisible(checked));
		binding.splitOptions.setVisibility(View.VISIBLE);
	}

	private void setSplitCountVisible(boolean visible) {
		int v = visible ? View.VISIBLE : View.GONE;
		binding.splitDexHint.setVisibility(v);
		binding.splitDexCount.setVisibility(v);
	}

	/**
	 * Reads the dex options, saves them for the next installation and passes them to the installer.
	 *
	 * @return false if the entered value is not valid
	 */
	private boolean applySplitOptions() {
		int classesPerDex = 0;
		if (binding.splitOptions.getVisibility() == View.VISIBLE) {
			boolean split = binding.splitDexCheck.isChecked();
			int count = DEFAULT_SPLIT_DEX_COUNT;
			try {
				count = Integer.parseInt(binding.splitDexCount.getText().toString().trim());
			} catch (NumberFormatException e) {
				if (split) {
					count = 0;
				}
			}
			if (split && count <= 0) {
				Toast.makeText(requireActivity(), R.string.split_dex_invalid, Toast.LENGTH_SHORT).show();
				return false;
			}
			SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit();
			editor.putBoolean(PREF_SPLIT_DEX, split);
			if (count > 0) {
				editor.putInt(PREF_SPLIT_DEX_COUNT, count);
			}
			editor.apply();
			classesPerDex = split ? count : 0;
		}
		installer.setSplitClasses(classesPerDex);
		binding.splitOptions.setVisibility(View.GONE);
		return true;
	}

	private void convert() {
		if (!applySplitOptions()) {
			return;
		}
		Descriptor nd = installer.getNewDescriptor();
		SpannableStringBuilder info = nd.getInfo(requireActivity());
		mDialog.setMessage(info);
		binding.installationStatus.setText(R.string.converting_wait);
		showProgress();
		hideButtons();
		Disposable disposable = Single.create(installer::install)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void alertConfirm(SpannableStringBuilder message,
							  View.OnClickListener positive) {
		hideProgress();
		mDialog.setCancelable(false);
		mDialog.setCanceledOnTouchOutside(false);
		mDialog.setMessage(message);
		btnOk.setOnClickListener(positive);
		showButtons();
	}

	private void alertSelectJar(View.OnClickListener positive) {
		hideProgress();
		mDialog.setCancelable(false);
		mDialog.setCanceledOnTouchOutside(false);
		mDialog.setMessage(getString(R.string.install_jar_needed));
		btnOk.setOnClickListener(positive);
		showButtons();
	}

	private void onProgress(@NonNull Integer status) {
		if (!isAdded()) {
			return;
		}
		if (status == AppInstaller.STATUS_SUCCESS) {
			binding.installationProgress.setVisibility(View.GONE);
			binding.installationStatus.setText(getString(R.string.install_done));
			AppItem app = installer.getExistsApp();
			Drawable drawable = Drawable.createFromPath(app.getImagePathExt());
			if (drawable != null) mDialog.setIcon(drawable);
			btnOk.setText(R.string.START_CMD);
			btnOk.setOnClickListener(v -> {
				Config.startApp(v.getContext(), app.getTitle(), app.getPathExt(), false);
				dismiss();
			});
			btnClose.setText(R.string.close);
			showButtons();
			return;
		}
		Descriptor nd = installer.getNewDescriptor();
		SpannableStringBuilder message;
		switch (status) {
			case AppInstaller.STATUS_NEW:
				message = nd.getInfo(requireActivity());
				break;
			case AppInstaller.STATUS_OLDEST:
				message = new SpannableStringBuilder(getString(
						R.string.reinstall_older,
						nd.getVersion(),
						installer.getCurrentVersion()));
				break;
			case AppInstaller.STATUS_EQUAL:
				message = new SpannableStringBuilder(getString(R.string.reinstall));
				AppItem app = installer.getExistsApp();
				btnRun.setVisibility(View.VISIBLE);
				btnRun.setOnClickListener(v -> {
					installer.clearCache();
					installer.deleteTemp();
					Config.startApp(v.getContext(), app.getTitle(), app.getPathExt(), false);
					dismiss();
				});
				break;
			case AppInstaller.STATUS_NEWEST:
				message = new SpannableStringBuilder(getString(
						R.string.reinstall_newest,
						nd.getVersion(),
						installer.getCurrentVersion()));
				break;
			case AppInstaller.STATUS_UNMATCHED:
				SpannableStringBuilder info = installer.getManifest().getInfo(requireActivity());
				info.append(getString(R.string.install_jar_non_matched_jad));
				alertConfirm(info, v -> installApp(installer.getJar(), null));
				return;
			case AppInstaller.STATUS_NEED_JAD:
				alertSelectJar(v -> openFileLauncher.launch(null));
				return;
			default:
				throw new IllegalStateException("Unexpected value: " + status);
		}
		if (installer.getJar() == null) {
			message.append('\n').append(getString(R.string.warn_install_from_net));
		}
		Drawable drawable = Drawable.createFromPath(installer.getIconPath());
		if (drawable != null) mDialog.setIcon(drawable);
		mDialog.setTitle(nd.getName());
		mDialog.setCancelable(false);
		mDialog.setCanceledOnTouchOutside(false);
		mDialog.setMessage(message);
		btnOk.setOnClickListener(v -> convert());
		hideProgress();
		showSplitOptions();
		showButtons();
	}

	private void onError(Throwable e) {
		e.printStackTrace();
		installer.clearCache();
		installer.deleteTemp();
		if (!isAdded()) return;
		hideProgress();
		Toast.makeText(requireActivity(), getString(R.string.error) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
		dismissAllowingStateLoss();
	}
}
