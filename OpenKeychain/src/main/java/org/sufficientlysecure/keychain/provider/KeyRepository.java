/*
 * Copyright (C) 2017 Schürmann & Breitmoser GbR
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.provider;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.squareup.sqldelight.SqlDelightQuery;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.sufficientlysecure.keychain.model.Certification;
import org.sufficientlysecure.keychain.model.KeyRingPublic;
import org.sufficientlysecure.keychain.model.SubKey;
import org.sufficientlysecure.keychain.model.SubKey.UnifiedKeyInfo;
import org.sufficientlysecure.keychain.model.UserPacket;
import org.sufficientlysecure.keychain.model.UserPacket.UserId;
import org.sufficientlysecure.keychain.operations.results.OperationResult.LogType;
import org.sufficientlysecure.keychain.operations.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.pgp.CanonicalizedKeyRing.VerificationStatus;
import org.sufficientlysecure.keychain.pgp.CanonicalizedPublicKeyRing;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey.SecretKeyType;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.pgp.exception.PgpKeyNotFoundException;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import timber.log.Timber;


public class KeyRepository extends AbstractDao {
    // If we ever switch to api level 11, we can ditch this whole mess!
    public static final int FIELD_TYPE_NULL = 1;
    // this is called integer to stay coherent with the constants in Cursor (api level 11)
    public static final int FIELD_TYPE_INTEGER = 2;
    public static final int FIELD_TYPE_FLOAT = 3;
    public static final int FIELD_TYPE_STRING = 4;
    public static final int FIELD_TYPE_BLOB = 5;

    final ContentResolver contentResolver;
    final LocalPublicKeyStorage mLocalPublicKeyStorage;
    final LocalSecretKeyStorage localSecretKeyStorage;

    OperationLog mLog;
    int mIndent;

    public static KeyRepository create(Context context) {
        ContentResolver contentResolver = context.getContentResolver();
        LocalPublicKeyStorage localPublicKeyStorage = LocalPublicKeyStorage.getInstance(context);
        LocalSecretKeyStorage localSecretKeyStorage = LocalSecretKeyStorage.getInstance(context);
        KeychainDatabase database = KeychainDatabase.getInstance(context);
        DatabaseNotifyManager databaseNotifyManager = DatabaseNotifyManager.create(context);

        return new KeyRepository(contentResolver, database, databaseNotifyManager, localPublicKeyStorage, localSecretKeyStorage);
    }

    private KeyRepository(ContentResolver contentResolver, KeychainDatabase database,
            DatabaseNotifyManager databaseNotifyManager,
            LocalPublicKeyStorage localPublicKeyStorage,
            LocalSecretKeyStorage localSecretKeyStorage) {
        this(contentResolver, database, databaseNotifyManager, localPublicKeyStorage, localSecretKeyStorage, new OperationLog(), 0);
    }

    KeyRepository(ContentResolver contentResolver, KeychainDatabase database,
            DatabaseNotifyManager databaseNotifyManager,
            LocalPublicKeyStorage localPublicKeyStorage,
            LocalSecretKeyStorage localSecretKeyStorage,
            OperationLog log, int indent) {
        super(database, databaseNotifyManager);
        this.contentResolver = contentResolver;
        mLocalPublicKeyStorage = localPublicKeyStorage;
        this.localSecretKeyStorage = localSecretKeyStorage;
        mIndent = indent;
        mLog = log;
    }

    public OperationLog getLog() {
        return mLog;
    }

    public void log(LogType type) {
        if (mLog != null) {
            mLog.add(type, mIndent);
        }
    }

    public void log(LogType type, Object... parameters) {
        if (mLog != null) {
            mLog.add(type, mIndent, parameters);
        }
    }

    public void clearLog() {
        mLog = new OperationLog();
    }

    Object getGenericData(Uri uri, String column, int type) throws NotFoundException {
        Object result = getGenericData(uri, new String[]{column}, new int[]{type}, null).get(column);
        if (result == null) {
            throw new NotFoundException();
        }
        return result;
    }

    private HashMap<String, Object> getGenericData(Uri uri, String[] proj, int[] types)
            throws NotFoundException {
        return getGenericData(uri, proj, types, null);
    }

    private HashMap<String, Object> getGenericData(Uri uri, String[] proj, int[] types, String selection)
            throws NotFoundException {
        Cursor cursor = contentResolver.query(uri, proj, selection, null, null);

        try {
            HashMap<String, Object> result = new HashMap<>(proj.length);
            if (cursor != null && cursor.moveToFirst()) {
                int pos = 0;
                for (String p : proj) {
                    switch (types[pos]) {
                        case FIELD_TYPE_NULL:
                            result.put(p, cursor.isNull(pos));
                            break;
                        case FIELD_TYPE_INTEGER:
                            result.put(p, cursor.getLong(pos));
                            break;
                        case FIELD_TYPE_FLOAT:
                            result.put(p, cursor.getFloat(pos));
                            break;
                        case FIELD_TYPE_STRING:
                            result.put(p, cursor.getString(pos));
                            break;
                        case FIELD_TYPE_BLOB:
                            result.put(p, cursor.getBlob(pos));
                            break;
                    }
                    pos += 1;
                }
            } else {
                // If no data was found, throw an appropriate exception
                throw new NotFoundException();
            }

            return result;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public HashMap<String, Object> getUnifiedData(long masterKeyId, String[] proj, int[] types)
            throws NotFoundException {
        return getGenericData(KeyRings.buildUnifiedKeyRingUri(masterKeyId), proj, types);
    }

    public CachedPublicKeyRing getCachedPublicKeyRing(Uri queryUri) throws PgpKeyNotFoundException {
        long masterKeyId = new CachedPublicKeyRing(this, queryUri).extractOrGetMasterKeyId();
        return getCachedPublicKeyRing(masterKeyId);
    }

    public CachedPublicKeyRing getCachedPublicKeyRing(long id) {
        return new CachedPublicKeyRing(this, KeyRings.buildUnifiedKeyRingUri(id));
    }

    public CanonicalizedPublicKeyRing getCanonicalizedPublicKeyRing(long masterKeyId) throws NotFoundException {
        UnifiedKeyInfo unifiedKeyInfo = getUnifiedKeyInfo(masterKeyId);
        if (unifiedKeyInfo == null) {
            throw new NotFoundException();
        }

        byte[] publicKeyData = loadPublicKeyRingData(masterKeyId);
        return new CanonicalizedPublicKeyRing(publicKeyData, unifiedKeyInfo.verified());
    }

    public CanonicalizedSecretKeyRing getCanonicalizedSecretKeyRing(long masterKeyId) throws NotFoundException {
        UnifiedKeyInfo unifiedKeyInfo = getUnifiedKeyInfo(masterKeyId);
        if (unifiedKeyInfo == null || !unifiedKeyInfo.has_any_secret()) {
            throw new NotFoundException();
        }
        byte[] secretKeyData = loadSecretKeyRingData(masterKeyId);
        if (secretKeyData == null) {
            throw new IllegalStateException("Missing expected secret key data!");
        }
        return new CanonicalizedSecretKeyRing(secretKeyData, unifiedKeyInfo.verified());
    }

    public List<Long> getAllMasterKeyIds() {
        SqlDelightQuery query = KeyRingPublic.FACTORY.selectAllMasterKeyIds();
        return mapAllRows(query, KeyRingPublic.FACTORY.selectAllMasterKeyIdsMapper()::map);
    }

    public List<Long> getMasterKeyIdsBySigner(List<Long> signerMasterKeyIds) {
        long[] signerKeyIds = new long[signerMasterKeyIds.size()];
        int i = 0;
        for (Long signerKeyId : signerMasterKeyIds) {
            signerKeyIds[i++] = signerKeyId;
        }
        SqlDelightQuery query = SubKey.FACTORY.selectMasterKeyIdsBySigner(signerKeyIds);
        return mapAllRows(query, KeyRingPublic.FACTORY.selectAllMasterKeyIdsMapper()::map);
    }

    public Long getMasterKeyIdBySubkeyId(long subKeyId) {
        SqlDelightQuery query = SubKey.FACTORY.selectMasterKeyIdBySubkey(subKeyId);
        try (Cursor cursor = getReadableDb().query(query)) {
            if (cursor.moveToFirst()) {
                return SubKey.FACTORY.selectMasterKeyIdBySubkeyMapper().map(cursor);
            }
            return null;
        }
    }

    public UnifiedKeyInfo getUnifiedKeyInfo(long masterKeyId) {
        SqlDelightQuery query = SubKey.FACTORY.selectUnifiedKeyInfoByMasterKeyId(masterKeyId);
        try (Cursor cursor = getReadableDb().query(query)) {
            if (cursor.moveToNext()) {
                return SubKey.UNIFIED_KEY_INFO_MAPPER.map(cursor);
            }
            return null;
        }
    }

    public List<UnifiedKeyInfo> getUnifiedKeyInfosByMailAddress(String mailAddress) {
        SqlDelightQuery query = SubKey.FACTORY.selectUnifiedKeyInfoSearchMailAddress('%' + mailAddress + '%');
        return mapAllRows(query, SubKey.UNIFIED_KEY_INFO_MAPPER::map);
    }

    public List<UnifiedKeyInfo> getAllUnifiedKeyInfo() {
        SqlDelightQuery query = SubKey.FACTORY.selectAllUnifiedKeyInfo();
        return mapAllRows(query, SubKey.UNIFIED_KEY_INFO_MAPPER::map);
    }

    public List<UnifiedKeyInfo> getAllUnifiedKeyInfoWithSecret() {
        SqlDelightQuery query = SubKey.FACTORY.selectAllUnifiedKeyInfoWithSecret();
        return mapAllRows(query, SubKey.UNIFIED_KEY_INFO_MAPPER::map);
    }

    public List<UserId> getUserIds(long... masterKeyIds) {
        SqlDelightQuery query = UserPacket.FACTORY.selectUserIdsByMasterKeyId(masterKeyIds);
        return mapAllRows(query, UserPacket.USER_ID_MAPPER::map);
    }

    public List<String> getConfirmedUserIds(long masterKeyId) {
        ArrayList<String> userIds = new ArrayList<>();
        SqlDelightQuery query = UserPacket.FACTORY.selectUserIdsByMasterKeyIdAndVerification(
                Certification.FACTORY, masterKeyId, VerificationStatus.VERIFIED_SECRET);
        for (UserId userId : mapAllRows(query, UserPacket.USER_ID_MAPPER::map)) {
            userIds.add(userId.user_id());
        }
        return userIds;
    }

    public List<SubKey> getSubKeysByMasterKeyId(long masterKeyId) {
        SqlDelightQuery query = SubKey.FACTORY.selectSubkeysByMasterKeyId(masterKeyId);
        return mapAllRows(query, SubKey.SUBKEY_MAPPER::map);
    }

    public SecretKeyType getSecretKeyType(long keyId) {
        SqlDelightQuery query = SubKey.FACTORY.selectSecretKeyType(keyId);
        try (Cursor cursor = getReadableDb().query(query)) {
            if (cursor.moveToFirst()) {
                return SubKey.SKT_MAPPER.map(cursor);
            }
            return null;
        }
    }

    private byte[] getKeyRingAsArmoredData(byte[] data) throws IOException, PgpGeneralException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ArmoredOutputStream aos = new ArmoredOutputStream(bos);

        aos.write(data);
        aos.close();

        return bos.toByteArray();
    }

    public String getPublicKeyRingAsArmoredString(long masterKeyId)
            throws NotFoundException, IOException, PgpGeneralException {
        byte[] data = loadPublicKeyRingData(masterKeyId);
        byte[] armoredData = getKeyRingAsArmoredData(data);
        return new String(armoredData);
    }

    public byte[] getSecretKeyRingAsArmoredData(long masterKeyId)
            throws NotFoundException, IOException, PgpGeneralException {
        byte[] data = loadSecretKeyRingData(masterKeyId);
        return getKeyRingAsArmoredData(data);
    }

    public ContentResolver getContentResolver() {
        return contentResolver;
    }

    public final byte[] loadPublicKeyRingData(long masterKeyId) throws NotFoundException {
        SqlDelightQuery query = KeyRingPublic.FACTORY.selectByMasterKeyId(masterKeyId);
        try (Cursor cursor = getReadableDb().query(query)) {
            if (cursor.moveToFirst()) {
                KeyRingPublic keyRingPublic = KeyRingPublic.MAPPER.map(cursor);
                byte[] keyRingData = keyRingPublic.key_ring_data();
                if (keyRingData == null) {
                    keyRingData = mLocalPublicKeyStorage.readPublicKey(masterKeyId);
                }
                return keyRingData;
            }
        } catch (IOException e) {
            Timber.e(e, "Error reading public key from storage!");
        }
        throw new NotFoundException();
    }

    public final byte[] loadSecretKeyRingData(long masterKeyId) throws NotFoundException {
        try {
            return localSecretKeyStorage.readSecretKey(masterKeyId);
        } catch (IOException e) {
            Timber.e(e, "Error reading secret key from storage!");
            throw new NotFoundException();
        }
    }

    public static class NotFoundException extends Exception {
        public NotFoundException() {
        }

        public NotFoundException(String name) {
            super(name);
        }
    }
}
