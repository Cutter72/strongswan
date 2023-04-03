/*
 * Copyright © 2015 FancyFon Software Ltd.
 * All rights reserved.
 */
package org.strongswan.android.apiclient;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.fancyfon.strongswan.apiclient.R;
import com.google.inject.Inject;

import java.util.Arrays;
import java.util.Locale;

/**
 * @author Marcin Waligórski <marcin.waligorski@fancyfon.com>
 */
public class Logger {

	@Inject
	Context context;

	@Inject
	Resources resources;

	public void logAndToast(String tag, String message) {
		logAndToast(tag, message, null);
	}

	public void logAndToast(String tag, String message, Throwable t) {
		if (t == null) {
			Log.i(tag, message);
			Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
		} else {
			Log.e(tag, message, t);
			Toast.makeText(context, message + ", cause: " + t, Toast.LENGTH_SHORT).show();
		}
	}

	public void logAndToastVpnProfileBundle(String tag, Bundle bundle) {
		long mId = bundle.getLong(resources.getString(R.string.vpn_profile_bundle_id_key));
		String mGateway = bundle.getString(resources.getString(R.string.vpn_profile_bundle_gateway_key));
		String mName = bundle.getString(resources.getString(R.string.vpn_profile_bundle_name_key));
		String mPassword = bundle.getString(resources.getString(R.string.vpn_profile_bundle_password_key));
		String mVpnType = bundle.getString(resources.getString(R.string.vpn_profile_bundle_type_key));
		String mUsername = bundle.getString(resources.getString(R.string.vpn_profile_bundle_username_key));
		boolean mBlockIpv4 = bundle.getBoolean(resources.getString(R.string.vpn_profile_bundle_split_tunneling_block_ipv4));
		boolean mBlockIpv6 = bundle.getBoolean(resources.getString(R.string.vpn_profile_bundle_split_tunneling_block_ipv6));
		String[] mSubnets = bundle.getStringArray(resources.getString(R.string.vpn_profile_bundle_split_tunneling_subnets));
		String[] mExcluded = bundle.getStringArray(resources.getString(R.string.vpn_profile_bundle_split_tunneling_excluded));
		logAndToast(tag, String.format(Locale.getDefault(),
			"VpnProfile: id: %d, name: %s, gateway: %s, type: %s, pass: %s, user: %s, blockIpv4: %s, blockIpv6: %s, subnets: %s, excluded: %s",
			mId, mName, mGateway, mVpnType,
			mPassword == null ? "null" : mPassword,
			mUsername == null ? "null" : mUsername,
			mBlockIpv4, mBlockIpv6,
			Arrays.toString(mSubnets),
			Arrays.toString(mExcluded)));
	}
}
