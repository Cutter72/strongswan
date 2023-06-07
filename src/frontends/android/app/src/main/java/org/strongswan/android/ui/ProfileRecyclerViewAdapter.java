/*
 * Copyright © 2023 Techstep Poland S.A.
 * All rights reserved.
 */
package org.strongswan.android.ui;

import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.strongswan.android.R;

import java.util.List;

/**
 * @author Paweł Drelich <pawel.drelich@techstep.io>
 */
public class ProfileRecyclerViewAdapter extends RecyclerView.Adapter<ProfileRecyclerViewAdapter.ViewHolder> {
	private final List<String> profileNames;
	private final DialogInterface.OnClickListener onClickListener;

	public ProfileRecyclerViewAdapter(@NonNull List<String> profileNames, @NonNull DialogInterface.OnClickListener onClickListener) {
		this.profileNames = profileNames;
		this.onClickListener = onClickListener;
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.dialog_profile_list_item, parent, false);
		return new ViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		String item = profileNames.get(position);
		holder.profileName.setText(item);
		holder.root.setOnClickListener(v -> onClickListener.onClick(null, position));
	}

	@Override
	public int getItemCount() {
		return profileNames.size();
	}

	public static class ViewHolder extends RecyclerView.ViewHolder {
		TextView profileName;
		View root;

		public ViewHolder(@NonNull View itemView) {
			super(itemView);
			root = itemView;
			profileName = itemView.findViewById(R.id.profile_name);
		}
	}
}
