/*
 * Copyright © 2023 Techstep Poland S.A.
 * All rights reserved.
 */
package org.strongswan.android.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.strongswan.android.R;
import org.strongswan.android.databinding.RecyclerDialogBinding;

/**
 * @author Paweł Drelich <pawel.drelich@techstep.io>
 */
public class RecyclerDialogBuilder extends MaterialAlertDialogBuilder {
	private RecyclerDialogBinding binding;

	public RecyclerDialogBuilder(Context context) {
		super(context, R.style.BrandedDialog);

		binding = RecyclerDialogBinding.inflate(LayoutInflater.from(context), null, false);

		setView(binding.getRoot());
		setBackground(AppCompatResources.getDrawable(context, R.drawable.dialog_background));
		setCancelable(true);
		setBackgroundInsetTop(context.getResources().getDimensionPixelSize(R.dimen.dialog_content_margin));
		setBackgroundInsetBottom(context.getResources().getDimensionPixelSize(R.dimen.dialog_content_margin));
		setBackgroundInsetStart(0);
		setBackgroundInsetEnd(0);
	}

	@NonNull
	@Override
	public RecyclerDialogBuilder setIcon(Drawable icon) {
		binding.titleBar.icon.setImageDrawable(icon);
		return this;
	}

	@NonNull
	@Override
	public RecyclerDialogBuilder setIcon(@DrawableRes int icon) {
		binding.titleBar.icon.setImageResource(icon);
		return this;
	}

	@NonNull
	@Override
	public RecyclerDialogBuilder setTitle(CharSequence text) {
		binding.titleBar.title.setText(text);
		return this;
	}

	@NonNull
	@Override
	public RecyclerDialogBuilder setMessage(@StringRes int messageId) {
		TextView tv = new TextView(getContext());
		tv.setText(messageId);
		tv.setTextColor(ResourcesCompat.getColor(getContext().getResources(), R.color.brandedBlack, null));
		setContentView(tv);
		return this;
	}

	@NonNull
	@Override
	public RecyclerDialogBuilder setMessage(@Nullable CharSequence message) {
		TextView tv = new TextView(getContext());
		tv.setText(message);
		tv.setTextColor(ResourcesCompat.getColor(getContext().getResources(), R.color.brandedBlack, null));
		setContentView(tv);
		return this;
	}

	public RecyclerDialogBuilder setContentView(View view) {
		binding.scrollableContent.removeAllViews();
		binding.scrollableContent.addView(view);
		return this;
	}

	public RecyclerDialogBuilder setContentView(int layoutResId) {
		binding.scrollableContent.removeAllViews();
		binding.scrollableContent.addView(
			LayoutInflater.from(getContext()).inflate(layoutResId, null, false)
		);
		return this;
	}

	public View getContentView() {
		return binding.scrollableContent.getChildAt(0);
	}

	public RecyclerDialogBuilder setAdapter(RecyclerView.Adapter<?> recyclerViewAdapter) {
		LinearLayoutManager layoutManager = new LinearLayoutManager(binding.scrollableContent.getContext());
		binding.scrollableContent.setLayoutManager(layoutManager);

		DividerItemDecoration itemDecoration = new DividerItemDecoration(binding.scrollableContent.getContext(), DividerItemDecoration.VERTICAL);
		binding.scrollableContent.addItemDecoration(itemDecoration);

		binding.scrollableContent.setAdapter(recyclerViewAdapter);
		return this;
	}

	@NonNull
	@Override
	public AlertDialog create() {
		AlertDialog alertDialog = super.create();
		binding.titleBar.closeBtn.setOnClickListener(v -> {
			alertDialog.cancel();
		});
		return alertDialog;
	}

	@NonNull
	@Override
	public MaterialAlertDialogBuilder setPositiveButton(int textId, @Nullable DialogInterface.OnClickListener listener) {
		hideFooter();
		return super.setPositiveButton(textId, listener);
	}

	@NonNull
	@Override
	public MaterialAlertDialogBuilder setNeutralButton(int textId, @Nullable DialogInterface.OnClickListener listener) {
		hideFooter();
		return super.setNeutralButton(textId, listener);
	}

	@NonNull
	@Override
	public MaterialAlertDialogBuilder setNeutralButton(@Nullable CharSequence text, @Nullable DialogInterface.OnClickListener listener) {
		hideFooter();
		return super.setNeutralButton(text, listener);
	}

	@NonNull
	@Override
	public MaterialAlertDialogBuilder setNegativeButton(int textId, @Nullable DialogInterface.OnClickListener listener) {
		hideFooter();
		return super.setNegativeButton(textId, listener);
	}

	@NonNull
	@Override
	public MaterialAlertDialogBuilder setNegativeButton(@Nullable CharSequence text, @Nullable DialogInterface.OnClickListener listener) {
		hideFooter();
		return super.setNegativeButton(text, listener);
	}

	private void hideFooter() {
		binding.footer.footer.setVisibility(View.GONE);
	}
}
