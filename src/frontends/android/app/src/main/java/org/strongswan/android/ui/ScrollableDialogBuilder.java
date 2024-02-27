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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.strongswan.android.R;
import org.strongswan.android.databinding.ScrollableDialogBinding;

/**
 * @author Paweł Drelich <pawel.drelich@techstep.io>
 */
public class ScrollableDialogBuilder extends MaterialAlertDialogBuilder {
	private ScrollableDialogBinding scrollableDialogView;

	public ScrollableDialogBuilder(Context context) {
		super(context, R.style.BrandedDialog);

		scrollableDialogView = ScrollableDialogBinding.inflate(LayoutInflater.from(context), null, false);

		setView(scrollableDialogView.getRoot());
		setBackground(AppCompatResources.getDrawable(context, R.drawable.dialog_background));
		setCancelable(true);
		setBackgroundInsetTop(context.getResources().getDimensionPixelSize(R.dimen.dialog_content_margin));
		setBackgroundInsetBottom(context.getResources().getDimensionPixelSize(R.dimen.dialog_content_margin));
		setBackgroundInsetStart(0);
		setBackgroundInsetEnd(0);
	}

	@NonNull
	@Override
	public ScrollableDialogBuilder setIcon(Drawable icon) {
		scrollableDialogView.titleBar.icon.setImageDrawable(icon);
		return this;
	}

	@NonNull
	@Override
	public ScrollableDialogBuilder setIcon(@DrawableRes int icon) {
		scrollableDialogView.titleBar.icon.setImageResource(icon);
		return this;
	}

	@NonNull
	@Override
	public ScrollableDialogBuilder setTitle(CharSequence text) {
		scrollableDialogView.titleBar.title.setText(text);
		return this;
	}

	@NonNull
	@Override
	public ScrollableDialogBuilder setMessage(@StringRes int messageId) {
		TextView tv = new TextView(getContext());
		tv.setText(messageId);
		tv.setTextColor(ResourcesCompat.getColor(getContext().getResources(), R.color.brandedText, null));
		setContentView(tv);
		return this;
	}

	@NonNull
	@Override
	public ScrollableDialogBuilder setMessage(@Nullable CharSequence message) {
		TextView tv = new TextView(getContext());
		tv.setText(message);
		tv.setTextColor(ResourcesCompat.getColor(getContext().getResources(), R.color.brandedText, null));
		setContentView(tv);
		return this;
	}

	public ScrollableDialogBuilder setContentView(View view) {
		scrollableDialogView.scrollableContent.removeAllViews();
		scrollableDialogView.scrollableContent.addView(view);
		return this;
	}

	public ScrollableDialogBuilder setContentView(int layoutResId) {
		scrollableDialogView.scrollableContent.removeAllViews();
		scrollableDialogView.scrollableContent.addView(
			LayoutInflater.from(getContext()).inflate(layoutResId, null, false)
		);
		return this;
	}

	public View getContentView() {
		return scrollableDialogView.scrollableContent.getChildAt(0);
	}

	@NonNull
	@Override
	public AlertDialog create() {
		AlertDialog alertDialog = super.create();
		scrollableDialogView.titleBar.closeBtn.setOnClickListener(v -> {
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
		scrollableDialogView.footer.footer.setVisibility(View.GONE);
	}
}
