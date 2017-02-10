/*
 * Copyright (C) 2012-2016 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
 * HSR Hochschule fuer Technik Rapperswil
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.strongswan.android.logic;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.security.KeyChain;
import android.security.KeyChainException;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.system.OsConstants;
import android.util.Log;

import org.strongswan.android.R;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnProfileDataSource;
import org.strongswan.android.data.VpnType.VpnTypeFeature;
import org.strongswan.android.logic.VpnStateService.ErrorState;
import org.strongswan.android.logic.VpnStateService.State;
import org.strongswan.android.logic.imc.ImcState;
import org.strongswan.android.logic.imc.RemediationInstruction;
import org.strongswan.android.security.LocalKeystore;
import org.strongswan.android.ui.MainActivity;
import org.strongswan.android.utils.SettingsWriter;

import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CharonVpnService extends VpnService implements Runnable, VpnStateService.VpnStateListener
{
	private static final String TAG = CharonVpnService.class.getSimpleName();
	public static final String LOG_FILE = "charon.log";
	public static final int VPN_STATE_NOTIFICATION_ID = 1;

	private String mLogFile;
	private VpnProfileDataSource mDataSource;
	private Thread mConnectionHandler;
	private VpnProfile mCurrentProfile;
	private volatile String mCurrentCertificateId;
	private volatile String mCurrentCertificateAlias;
	private volatile String mCurrentUserCertificateAlias;
	private VpnProfile mNextProfile;
	private volatile boolean mProfileUpdated;
	private volatile boolean mTerminate;
	private volatile boolean mIsDisconnecting;
	private volatile boolean mShowNotification;
	private VpnStateService mService;
	private final Object mServiceLock = new Object();
	private final ServiceConnection mServiceConnection = new ServiceConnection() {
		@Override
		public void onServiceDisconnected(ComponentName name)
		{	/* since the service is local this is theoretically only called when the process is terminated */
			synchronized (mServiceLock)
			{
				mService = null;
			}
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service)
		{
			synchronized (mServiceLock)
			{
				mService = ((VpnStateService.LocalBinder)service).getService();
			}
			/* we are now ready to start the handler thread */
			mService.registerListener(CharonVpnService.this);
			mConnectionHandler.start();
		}
	};

	/**
	 * as defined in charonservice.h
	 */
	static final int STATE_CHILD_SA_UP = 1;
	static final int STATE_CHILD_SA_DOWN = 2;
	static final int STATE_AUTH_ERROR = 3;
	static final int STATE_PEER_AUTH_ERROR = 4;
	static final int STATE_LOOKUP_ERROR = 5;
	static final int STATE_UNREACHABLE_ERROR = 6;
	static final int STATE_GENERIC_ERROR = 7;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		if (intent != null)
		{
			Bundle bundle = intent.getExtras();
			VpnProfile profile = null;
			if (bundle != null)
			{
				profile = mDataSource.getVpnProfile(bundle.getLong(VpnProfileDataSource.KEY_ID));
				if (profile != null)
				{
					String password = bundle.getString(VpnProfileDataSource.KEY_PASSWORD);
					profile.setPassword(password);
				}
			}
			setNextProfile(profile);
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onCreate()
	{
		mLogFile = getFilesDir().getAbsolutePath() + File.separator + LOG_FILE;

		mDataSource = new VpnProfileDataSource(this);
		mDataSource.open();
		/* use a separate thread as main thread for charon */
		mConnectionHandler = new Thread(this);
		/* the thread is started when the service is bound */
		bindService(new Intent(this, VpnStateService.class),
					mServiceConnection, Service.BIND_AUTO_CREATE);
	}

	@Override
	public void onRevoke()
	{	/* the system revoked the rights grated with the initial prepare() call.
		 * called when the user clicks disconnect in the system's VPN dialog */
		setNextProfile(null);
	}

	@Override
	public void onDestroy()
	{
		mTerminate = true;
		setNextProfile(null);
		try
		{
			mConnectionHandler.join();
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
		if (mService != null)
		{
			mService.unregisterListener(this);
			unbindService(mServiceConnection);
		}
		mDataSource.close();
	}

	/**
	 * Set the profile that is to be initiated next. Notify the handler thread.
	 *
	 * @param profile the profile to initiate
	 */
	private void setNextProfile(VpnProfile profile)
	{
		synchronized (this)
		{
			this.mNextProfile = profile;
			mProfileUpdated = true;
			notifyAll();
		}
	}

	@Override
	public void run()
	{
		while (true)
		{
			synchronized (this)
			{
				try
				{
					while (!mProfileUpdated)
					{
						wait();
					}

					mProfileUpdated = false;
					stopCurrentConnection();
					if (mNextProfile == null)
					{
						setState(State.DISABLED);
						if (mTerminate)
						{
							break;
						}
					}
					else
					{
						mCurrentProfile = mNextProfile;
						mNextProfile = null;

						/* store this in a separate (volatile) variable to avoid
						 * a possible deadlock during deinitialization */
						mCurrentCertificateId = mCurrentProfile.getCertificateId();
						mCurrentCertificateAlias = mCurrentProfile.getCertificateAlias();
						mCurrentUserCertificateAlias = mCurrentProfile.getUserCertificateAlias();

						startConnection(mCurrentProfile);
						mIsDisconnecting = false;

						addNotification();
					//	BuilderAdapter builder = new BuilderAdapter(mCurrentProfile.getName(), mCurrentProfile.getSplitTunneling());
						BuilderAdapter builder = new BuilderAdapter(mCurrentProfile);
						if (initializeCharon(builder, mLogFile, mCurrentProfile.getVpnType().has(VpnTypeFeature.BYOD)))
						{
							Log.i(TAG, "charon started");
							SettingsWriter writer = new SettingsWriter();
						//	writer.setValue("global.language", Locale.getDefault().getLanguage());
						//	writer.setValue("global.mtu", mCurrentProfile.getMTU());
							writer.setValue("connection.type", mCurrentProfile.getVpnType().getIdentifier());
							writer.setValue("connection.server", mCurrentProfile.getGateway());
					//		writer.setValue("connection.port", mCurrentProfile.getPort());
							writer.setValue("connection.username", mCurrentProfile.getUsername());
							writer.setValue("connection.password", mCurrentProfile.getPassword());
					//		writer.setValue("connection.local_id", mCurrentProfile.getLocalId());
					//		writer.setValue("connection.remote_id", mCurrentProfile.getRemoteId());
							initiate(writer.serialize());
						}
						else
						{
							Log.e(TAG, "failed to start charon");
							setError(ErrorState.GENERIC_ERROR);
							setState(State.DISABLED);
							mCurrentProfile = null;
						}
					}
				}
				catch (InterruptedException ex)
				{
					stopCurrentConnection();
					setState(State.DISABLED);
				}
			}
		}
	}

	/**
	 * Stop any existing connection by deinitializing charon.
	 */
	private void stopCurrentConnection()
	{
		synchronized (this)
		{
			if (mCurrentProfile != null)
			{
				setState(State.DISCONNECTING);
				mIsDisconnecting = true;
				deinitializeCharon();
				Log.i(TAG, "charon stopped");
				mCurrentProfile = null;
				removeNotification();
			}
		}
	}

	/**
	 * Add a permanent notification while we are connected to avoid the service getting killed by
	 * the system when low on memory.
	 */
	private void addNotification()
	{
		mShowNotification = true;
		startForeground(VPN_STATE_NOTIFICATION_ID, buildNotification(false));
	}

	/**
	 * Remove the permanent notification.
	 */
	private void removeNotification()
	{
		mShowNotification = false;
		stopForeground(true);
	}

	/**
	 * Build a notification matching the current state
	 */
	private Notification buildNotification(boolean publicVersion)
	{
		VpnProfile profile = mService.getProfile();
		State state = mService.getState();
		ErrorState error = mService.getErrorState();
		String name = "";

		if (profile != null)
		{
			name = profile.getName();
		}
		android.support.v4.app.NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
				.setSmallIcon(R.drawable.ic_notification)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setVisibility(publicVersion ? NotificationCompat.VISIBILITY_PUBLIC
											 : NotificationCompat.VISIBILITY_PRIVATE);
		int s = R.string.state_disabled;
		if (error != ErrorState.NO_ERROR)
		{
			s = R.string.state_error;
			builder.setSmallIcon(R.drawable.ic_notification_warning);
			builder.setColor(ContextCompat.getColor(this, R.color.error_text));
		}
		else
		{
			switch (state)
			{
				case CONNECTING:
					s = R.string.state_connecting;
					builder.setSmallIcon(R.drawable.ic_notification_warning);
					builder.setColor(ContextCompat.getColor(this, R.color.warning_text));
					break;
				case CONNECTED:
					s = R.string.state_connected;
					builder.setColor(ContextCompat.getColor(this, R.color.success_text));
					builder.setUsesChronometer(true);
					break;
				case DISCONNECTING:
					s = R.string.state_disconnecting;
					break;
			}
		}
		builder.setContentTitle(getString(s));
		if (!publicVersion)
		{
			builder.setContentText(name);
			builder.setPublicVersion(buildNotification(true));
		}

		Intent intent = new Intent(getApplicationContext(), MainActivity.class);
		PendingIntent pending = PendingIntent.getActivity(getApplicationContext(), 0, intent,
														  PendingIntent.FLAG_UPDATE_CURRENT);
		builder.setContentIntent(pending);
		return builder.build();
	}

	@Override
	public void stateChanged() {
		if (mShowNotification)
		{
			NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			manager.notify(VPN_STATE_NOTIFICATION_ID, buildNotification(false));
		}
	}

	/**
	 * Notify the state service about a new connection attempt.
	 * Called by the handler thread.
	 *
	 * @param profile currently active VPN profile
	 */
	private void startConnection(VpnProfile profile)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.startConnection(profile);
			}
		}
	}

	/**
	 * Update the current VPN state on the state service. Called by the handler
	 * thread and any of charon's threads.
	 *
	 * @param state current state
	 */
	private void setState(State state)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.setState(state);
			}
		}
	}

	/**
	 * Set an error on the state service. Called by the handler thread and any
	 * of charon's threads.
	 *
	 * @param error error state
	 */
	private void setError(ErrorState error)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.setError(error);
			}
		}
	}

	/**
	 * Set the IMC state on the state service. Called by the handler thread and
	 * any of charon's threads.
	 *
	 * @param state IMC state
	 */
	private void setImcState(ImcState state)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				mService.setImcState(state);
			}
		}
	}

	/**
	 * Set an error on the state service. Called by the handler thread and any
	 * of charon's threads.
	 *
	 * @param error error state
	 */
	private void setErrorDisconnect(ErrorState error)
	{
		synchronized (mServiceLock)
		{
			if (mService != null)
			{
				if (!mIsDisconnecting)
				{
					mService.setError(error);
				}
			}
		}
	}

	/**
	 * Updates the state of the current connection.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param status new state
	 */
	public void updateStatus(int status)
	{
		switch (status)
		{
			case STATE_CHILD_SA_DOWN:
				if (!mIsDisconnecting)
				{
					setState(State.CONNECTING);
				}
				break;
			case STATE_CHILD_SA_UP:
				setState(State.CONNECTED);
				break;
			case STATE_AUTH_ERROR:
				setErrorDisconnect(ErrorState.AUTH_FAILED);
				break;
			case STATE_PEER_AUTH_ERROR:
				setErrorDisconnect(ErrorState.PEER_AUTH_FAILED);
				break;
			case STATE_LOOKUP_ERROR:
				setErrorDisconnect(ErrorState.LOOKUP_FAILED);
				break;
			case STATE_UNREACHABLE_ERROR:
				setErrorDisconnect(ErrorState.UNREACHABLE);
				break;
			case STATE_GENERIC_ERROR:
				setErrorDisconnect(ErrorState.GENERIC_ERROR);
				break;
			default:
				Log.e(TAG, "Unknown status code received");
				break;
		}
	}

	/**
	 * Updates the IMC state of the current connection.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param value new state
	 */
	public void updateImcState(int value)
	{
		ImcState state = ImcState.fromValue(value);
		if (state != null)
		{
			setImcState(state);
		}
	}

	/**
	 * Add a remediation instruction to the VPN state service.
	 * Called via JNI by different threads (but not concurrently).
	 *
	 * @param xml XML text
	 */
	public void addRemediationInstruction(String xml)
	{
		for (RemediationInstruction instruction : RemediationInstruction.fromXml(xml))
		{
			synchronized (mServiceLock)
			{
				if (mService != null)
				{
					mService.addRemediationInstruction(instruction);
				}
			}
		}
	}

	/**
	 * Function called via JNI to generate a list of DER encoded CA certificates
	 * as byte array.
	 *
	 * @return a list of DER encoded CA certificates
	 */
	private byte[][] getTrustedCertificates()
	{
		ArrayList<byte[]> certs = new ArrayList<byte[]>();
		try
		{
			certs = getFancyFonTrustedCerts(certs);
		}
		catch (CertificateEncodingException e)
		{
			e.printStackTrace();
			return null;
		} catch (KeyStoreException e) {
			e.printStackTrace();
		}
		return certs.toArray(new byte[certs.size()][]);
	}

	private ArrayList<byte[]> getFancyFonTrustedCerts(ArrayList<byte[]> certs) throws KeyStoreException, CertificateEncodingException {
		LocalKeystore keystore = new LocalKeystore();
		X509Certificate cert = keystore.getCertificate(mCurrentCertificateId,
				mCurrentCertificateAlias);
		certs.add(cert.getEncoded());
		return certs;
	}

	/**
	 * Function called via JNI to get a list containing the DER encoded certificates
	 * of the user selected certificate chain (beginning with the user certificate).
	 *
	 * Since this method is called from a thread of charon's thread pool we are safe
	 * to call methods on KeyChain directly.
	 *
	 * @return list containing the certificates (first element is the user certificate)
	 * @throws InterruptedException
	 * @throws KeyChainException
	 * @throws CertificateEncodingException
	 */
	private byte[][] getUserCertificate() throws KeyChainException, InterruptedException, CertificateEncodingException
	{
		ArrayList<byte[]> encodings = new ArrayList<byte[]>();
		X509Certificate[] chain = null;
		try {
			chain = getFancyFonCertificateChain();
		} catch (KeyStoreException e) {
			throw new KeyChainException();
		}
		if (chain == null || chain.length == 0)
		{
			return null;
		}
		for (X509Certificate cert : chain)
		{
			encodings.add(cert.getEncoded());
		}
		return encodings.toArray(new byte[encodings.size()][]);
	}

	private X509Certificate[] getFancyFonCertificateChain() throws KeyStoreException {
		LocalKeystore localKeystore = new LocalKeystore();
		return localKeystore.getCertificateChain(mCurrentCertificateId,mCurrentUserCertificateAlias);
	}

	/**
	 * Function called via JNI to get the private key the user selected.
	 *
	 * Since this method is called from a thread of charon's thread pool we are safe
	 * to call methods on KeyChain directly.
	 *
	 * @return the private key
	 * @throws InterruptedException
	 * @throws KeyChainException
	 * @throws CertificateEncodingException
	 */
	private PrivateKey getUserKey() throws KeyChainException, InterruptedException
	{
		return getFancyFonPrivateKey();
	}


	private PrivateKey getFancyFonPrivateKey() throws KeyChainException {
		try {
			LocalKeystore localKeystore = new LocalKeystore();
			return localKeystore.getPrivateKey(mCurrentCertificateId, mCurrentUserCertificateAlias);
		} catch (KeyStoreException e) {
			throw new KeyChainException();
		}
	}
	/**
	 * Initialization of charon, provided by libandroidbridge.so
	 *
	 * @param builder BuilderAdapter for this connection
	 * @param logfile absolute path to the logfile
	 * @param boyd enable BYOD features
	 * @return TRUE if initialization was successful
	 */
	public native boolean initializeCharon(BuilderAdapter builder, String logfile, boolean byod);

	/**
	 * Deinitialize charon, provided by libandroidbridge.so
	 */
	public native void deinitializeCharon();

	/**
	 * Initiate VPN, provided by libandroidbridge.so
	 */
	public native void initiate(String config);

	/**
	 * Adapter for VpnService.Builder which is used to access it safely via JNI.
	 * There is a corresponding C object to access it from native code.
	 */
	public class BuilderAdapter
	{
		private final String mName;
		private final Integer mSplitTunneling;
		private VpnService.Builder mBuilder;
		private BuilderCache mCache;
		private BuilderCache mEstablishedCache;
		private final VpnProfile profile;

		public BuilderAdapter(VpnProfile profile)
		{
			this.profile = profile;
			mName = profile.getName();
			mSplitTunneling = profile.getSplitTunneling();
			mBuilder = createBuilder();
			mCache = new BuilderCache(mSplitTunneling);
		}

		private VpnService.Builder createBuilder()
		{
			VpnService.Builder builder = new CharonVpnService.Builder();
			builder.setSession(mName);
			addFancyFonAllowedApplications(builder);
			/* even though the option displayed in the system dialog says "Configure"
			 * we just use our main Activity */
			Context context = getApplicationContext();
			Intent intent = new Intent(context, MainActivity.class);
			PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
					PendingIntent.FLAG_UPDATE_CURRENT);
			builder.setConfigureIntent(pending);
			return builder;
		}

        private void addFancyFonAllowedApplications(VpnService.Builder builder){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ArrayList<String> allowedApplications = profile.getAllowedApplications();
                for (String s : allowedApplications) {
                    try {
                        builder.addAllowedApplication(s);
                    } catch (PackageManager.NameNotFoundException ex) {
                        Log.w(TAG, "Failed to add packageName: " + s + " to allowed applications list for vpn profile: " + mName, ex);
                    }
                }
            }
        }


		public synchronized boolean addAddress(String address, int prefixLength)
		{
			try
			{
				mCache.addAddress(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}


		public synchronized boolean addDnsServer(String address)
		{
			try
			{
				mBuilder.addDnsServer(address);
				mCache.recordAddressFamily(address);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addRoute(String address, int prefixLength)
		{
			try
			{
				mCache.addRoute(address, prefixLength);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean addSearchDomain(String domain)
		{
			try
			{
				mBuilder.addSearchDomain(domain);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized boolean setMtu(int mtu)
		{
			try
			{
				mCache.setMtu(mtu);
			}
			catch (IllegalArgumentException ex)
			{
				return false;
			}
			return true;
		}

		public synchronized int establish()
		{
			ParcelFileDescriptor fd;
			try
			{
				mCache.applyData(mBuilder);
				fd = mBuilder.establish();
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				return -1;
			}
			if (fd == null)
			{
				return -1;
			}
			/* now that the TUN device is created we don't need the current
			 * builder anymore, but we might need another when reestablishing */
			mBuilder = createBuilder();
			mEstablishedCache = mCache;
			mCache = new BuilderCache(mSplitTunneling);
			return fd.detachFd();
		}

		public synchronized int establishNoDns()
		{
			ParcelFileDescriptor fd;

			if (mEstablishedCache == null)
			{
				return -1;
			}
			try
			{
				Builder builder = createBuilder();
				mEstablishedCache.applyData(builder);
				fd = builder.establish();
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				return -1;
			}
			if (fd == null)
			{
				return -1;
			}
			return fd.detachFd();
		}
	}

	/**
	 * Cache non DNS related information so we can recreate the builder without
	 * that information when reestablishing IKE_SAs
	 */
	public class BuilderCache
	{
		private final List<PrefixedAddress> mAddresses = new ArrayList<PrefixedAddress>();
		private final List<PrefixedAddress> mRoutesIPv4 = new ArrayList<PrefixedAddress>();
		private final List<PrefixedAddress> mRoutesIPv6 = new ArrayList<PrefixedAddress>();
		private final int mSplitTunneling;
		private int mMtu;
		private boolean mIPv4Seen, mIPv6Seen;

		public BuilderCache(Integer splitTunneling)
		{
			mSplitTunneling = splitTunneling != null ? splitTunneling : 0;
		}

		public void addAddress(String address, int prefixLength)
		{
			mAddresses.add(new PrefixedAddress(address, prefixLength));
			recordAddressFamily(address);
		}

		public void addRoute(String address, int prefixLength)
		{
			try
			{
				if (isIPv6(address))
				{
					mRoutesIPv6.add(new PrefixedAddress(address, prefixLength));
				}
				else
				{
					mRoutesIPv4.add(new PrefixedAddress(address, prefixLength));
				}
			}
			catch (UnknownHostException ex)
			{
				ex.printStackTrace();
			}
		}

		public void setMtu(int mtu)
		{
			mMtu = mtu;
		}

		public void recordAddressFamily(String address)
		{
			try
			{
				if (isIPv6(address))
				{
					mIPv6Seen = true;
				}
				else
				{
					mIPv4Seen = true;
				}
			}
			catch (UnknownHostException ex)
			{
				ex.printStackTrace();
			}
		}

		@TargetApi(Build.VERSION_CODES.LOLLIPOP)
		public void applyData(VpnService.Builder builder)
		{
			for (PrefixedAddress address : mAddresses)
			{
				builder.addAddress(address.mAddress, address.mPrefix);
			}
			/* add routes depending on whether split tunneling is allowed or not,
			 * that is, whether we have to handle and block non-VPN traffic */
			if ((mSplitTunneling & VpnProfile.SPLIT_TUNNELING_BLOCK_IPV4) == 0)
			{
				if (mIPv4Seen)
				{	/* split tunneling is used depending on the routes */
					for (PrefixedAddress route : mRoutesIPv4)
					{
						builder.addRoute(route.mAddress, route.mPrefix);
					}
				}
				else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
				{	/* allow traffic that would otherwise be blocked to bypass the VPN */
					builder.allowFamily(OsConstants.AF_INET);
				}
			}
			else if (mIPv4Seen)
			{	/* only needed if we've seen any addresses.  otherwise, traffic
				 * is blocked by default (we also install no routes in that case) */
				builder.addRoute("0.0.0.0", 0);
			}
			/* same thing for IPv6 */
			if ((mSplitTunneling & VpnProfile.SPLIT_TUNNELING_BLOCK_IPV6) == 0)
			{
				if (mIPv6Seen)
				{
					for (PrefixedAddress route : mRoutesIPv6)
					{
						builder.addRoute(route.mAddress, route.mPrefix);
					}
				}
				else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
				{
					builder.allowFamily(OsConstants.AF_INET6);
				}
			}
			else if (mIPv6Seen)
			{
				builder.addRoute("::", 0);
			}
			builder.setMtu(mMtu);
		}

		private boolean isIPv6(String address) throws UnknownHostException
		{
			InetAddress addr = InetAddress.getByName(address);
			if (addr instanceof Inet4Address)
			{
				return false;
			}
			else if (addr instanceof Inet6Address)
			{
				return true;
			}
			return false;
		}

		private class PrefixedAddress
		{
			public String mAddress;
			public int mPrefix;

			public PrefixedAddress(String address, int prefix)
			{
				this.mAddress = address;
				this.mPrefix = prefix;
			}
		}
	}

	/*
	 * The libraries are extracted to /data/data/org.strongswan.android/...
	 * during installation.  On newer releases most are loaded in JNI_OnLoad.
	 */
	static
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2)
		{
			System.loadLibrary("strongswan");

			if (MainActivity.USE_BYOD)
			{
				System.loadLibrary("tpmtss");
				System.loadLibrary("tncif");
				System.loadLibrary("tnccs");
				System.loadLibrary("imcv");
			}

			System.loadLibrary("charon");
			System.loadLibrary("ipsec");
		}
		System.loadLibrary("androidbridge");
	}
}
