package com.twofours.surespot.services;

import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import ch.boye.httpclientandroidlib.cookie.Cookie;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.twofours.surespot.IdentityController;
import com.twofours.surespot.PublicKeys;
import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.encryption.EncryptionController;

public class CredentialCachingService extends Service {
	private static final String TAG = "CredentialCachingService";

	private final IBinder mBinder = new CredentialCachingBinder();

	private Map<String, String> mPasswords = new HashMap<String, String>();
	private Map<String, Cookie> mCookies = new HashMap<String, Cookie>();
	private static String mLoggedInUser;
	private LoadingCache<PublicKeyPairKey, PublicKeys> mPublicIdentities;
	private LoadingCache<SharedSecretKey, byte[]> mSharedSecrets;
	private LoadingCache<String, String> mLatestVersions;

	@Override
	public void onCreate() {
		SurespotLog.v(TAG, "onCreate");
		// TODO make display optional?
		Notification notification = new Notification(0, null, System.currentTimeMillis());
		notification.flags |= Notification.FLAG_NO_CLEAR;

		// Intent notificationIntent = new Intent(this, StartupActivity.class);
		// PendingIntent pendingIntent = PendingIntent.getActivity(this, SurespotConstants.IntentRequestCodes.FOREGROUND_NOTIFICATION,
		// notificationIntent, 0);
		// notification.setLatestEventInfo(this, "surespot", "caching credentials", pendingIntent);
		startForeground(SurespotConstants.IntentRequestCodes.FOREGROUND_NOTIFICATION, notification);

		CacheLoader<PublicKeyPairKey, PublicKeys> keyPairCacheLoader = new CacheLoader<PublicKeyPairKey, PublicKeys>() {

			@Override
			public PublicKeys load(PublicKeyPairKey key) throws Exception {
				PublicKeys keys = IdentityController.getPublicKeyPair(key.getUsername(), key.getVersion());
				String version = keys.getVersion();

				SurespotLog.v(TAG, "keyPairCacheLoader getting latest version");
				String latestVersion = getLatestVersionIfPresent(key.getUsername());

				if (latestVersion == null || version.compareTo(latestVersion) > 0) {
					SurespotLog
							.v(TAG, "keyPairCacheLoader setting latestVersion, username: " + key.getUsername() + ", version: " + version);
					synchronized (this) {
						mLatestVersions.put(key.getUsername(), version);
					}
				}

				return keys;
			}
		};

		CacheLoader<SharedSecretKey, byte[]> secretCacheLoader = new CacheLoader<SharedSecretKey, byte[]>() {
			@Override
			public byte[] load(SharedSecretKey key) throws Exception {
				SurespotLog.v(TAG, "loadSharedSecret, ourVersion: " + key.getOurVersion() + ", theirUsername: " + key.getTheirUsername()
						+ ", theirVersion: " + key.getTheirVersion());

				PublicKey publicKey = mPublicIdentities.get(
						new PublicKeyPairKey(new VersionMap(key.getTheirUsername(), key.getTheirVersion()))).getDHKey();

				return EncryptionController.generateSharedSecretSync(
						IdentityController.getIdentity(key.getOurUsername()).getKeyPairDH(key.getOurVersion()).getPrivate(), publicKey);
			}
		};

		CacheLoader<String, String> versionCacheLoader = new CacheLoader<String, String>() {
			@Override
			public String load(String key) throws Exception {

				String version = MainActivity.getNetworkController().getKeyVersionSync(key);
				SurespotLog.v(TAG, "versionCacheLoader: retrieved keyversion from server for username: " + key + ", version: " + version);
				return version;
			}
		};

		mPublicIdentities = CacheBuilder.newBuilder().build(keyPairCacheLoader);
		mSharedSecrets = CacheBuilder.newBuilder().build(secretCacheLoader);
		mLatestVersions = CacheBuilder.newBuilder().build(versionCacheLoader);
	}

	public synchronized void login(String username, String password, Cookie cookie) {
		SurespotLog.v(TAG, "Logging in: " + username);
		mLoggedInUser = username;
		this.mPasswords.put(username, password);
		this.mCookies.put(username, cookie);
	}

	public String getLoggedInUser() {
		return mLoggedInUser;
	}

	public String getPassword(String username) {
		return mPasswords.get(username);
	}

	public Cookie getCookie(String username) {
		return mCookies.get(username);
	}

	public byte[] getSharedSecret(String ourVersion, String theirUsername, String theirVersion) {
		// get the cache for this user
		try {
			return mSharedSecrets.get(new SharedSecretKey(new VersionMap(getLoggedInUser(), ourVersion), new VersionMap(theirUsername,
					theirVersion)));
		}
		catch (ExecutionException e) {
			SurespotLog.w(TAG, "getSharedSecret: " + e.getMessage());
			return null;
		}

	}

	public synchronized void logout() {
		if (mLoggedInUser != null) {
			SurespotLog.v(TAG, "Logging out: " + mLoggedInUser);
			mPasswords.remove(mLoggedInUser);
			mCookies.remove(mLoggedInUser);
			mLoggedInUser = null;
		}
	}

	public class CredentialCachingBinder extends Binder {
		public CredentialCachingService getService() {
			return CredentialCachingService.this;
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;

	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;

	}

	@Override
	public void onDestroy() {
		SurespotLog.v(TAG, "onDestroy");
	}

	/**
	 * NEeds to be called on a thread
	 * 
	 * @param username
	 * @return
	 */

	private synchronized String getLatestVersionIfPresent(String username) {
		return mLatestVersions.getIfPresent(username);
	}

	public synchronized String getLatestVersion(String username) {
		try {
			String version = mLatestVersions.get(username);
			SurespotLog.v(TAG, "getLatestVersion, username: " + username + ", version: " + version);
			return version;
		}
		catch (ExecutionException e) {
			SurespotLog.w(TAG, "getLatestVersion", e);
		}
		return null;
	}

	public synchronized void updateLatestVersion(String username, String version) {
		if (username != null && version != null) {
			String latestVersion = getLatestVersionIfPresent(username);
			if (latestVersion == null || version.compareTo(latestVersion) > 0) {
				mLatestVersions.put(username, version);
			}
		}
	}

	private class VersionMap {
		private String mUsername;
		private String mVersion;

		public VersionMap(String username, String version) {
			mUsername = username;
			mVersion = version;
		}

		public String getUsername() {
			return mUsername;
		}

		public String getVersion() {
			return mVersion;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((mUsername == null) ? 0 : mUsername.hashCode());
			result = prime * result + ((mVersion == null) ? 0 : mVersion.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (!(obj instanceof VersionMap))
				return false;
			VersionMap other = (VersionMap) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (mUsername == null) {
				if (other.mUsername != null)
					return false;
			}
			else if (!mUsername.equals(other.mUsername))
				return false;
			if (mVersion == null) {
				if (other.mVersion != null)
					return false;
			}
			else if (!mVersion.equals(other.mVersion))
				return false;
			return true;
		}

		private CredentialCachingService getOuterType() {
			return CredentialCachingService.this;
		}

	}

	private class PublicKeyPairKey {
		private VersionMap mVersionMap;

		public PublicKeyPairKey(VersionMap versionMap) {
			mVersionMap = versionMap;
		}

		public String getUsername() {
			return mVersionMap.getUsername();
		}

		public String getVersion() {
			return mVersionMap.getVersion();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((mVersionMap == null) ? 0 : mVersionMap.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (!(obj instanceof PublicKeyPairKey))
				return false;
			PublicKeyPairKey other = (PublicKeyPairKey) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (mVersionMap == null) {
				if (other.mVersionMap != null)
					return false;
			}
			else if (!mVersionMap.equals(other.mVersionMap))
				return false;
			return true;
		}

		private CredentialCachingService getOuterType() {
			return CredentialCachingService.this;
		}

	}

	private class SharedSecretKey {
		private VersionMap mOurVersionMap;
		private VersionMap mTheirVersionMap;

		public SharedSecretKey(VersionMap ourVersionMap, VersionMap theirVersionMap) {
			mOurVersionMap = ourVersionMap;
			mTheirVersionMap = theirVersionMap;
		}

		public String getOurUsername() {
			return mOurVersionMap.getUsername();
		}

		public String getOurVersion() {
			return mOurVersionMap.getVersion();
		}

		public String getTheirUsername() {
			return mTheirVersionMap.getUsername();
		}

		public String getTheirVersion() {
			return mTheirVersionMap.getVersion();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((mOurVersionMap == null) ? 0 : mOurVersionMap.hashCode());
			result = prime * result + ((mTheirVersionMap == null) ? 0 : mTheirVersionMap.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (!(obj instanceof SharedSecretKey))
				return false;
			SharedSecretKey other = (SharedSecretKey) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (mOurVersionMap == null) {
				if (other.mOurVersionMap != null)
					return false;
			}
			else if (!mOurVersionMap.equals(other.mOurVersionMap))
				return false;
			if (mTheirVersionMap == null) {
				if (other.mTheirVersionMap != null)
					return false;
			}
			else if (!mTheirVersionMap.equals(other.mTheirVersionMap))
				return false;
			return true;
		}

		private CredentialCachingService getOuterType() {
			return CredentialCachingService.this;
		}

	}

}
