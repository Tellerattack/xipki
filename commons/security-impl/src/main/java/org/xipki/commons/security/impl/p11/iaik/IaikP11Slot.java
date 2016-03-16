/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2013 - 2016 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License (version 3
 * or later at your option) as published by the Free Software Foundation
 * with the addition of the following permission added to Section 15 as
 * permitted in Section 7(a):
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.commons.security.impl.p11.iaik;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTNamedCurves;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X962NamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.DSAParametersGenerator;
import org.bouncycastle.crypto.params.DSAParameterGenerationParameters;
import org.bouncycastle.crypto.params.DSAParameters;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.commons.common.ConfPairs;
import org.xipki.commons.common.util.CollectionUtil;
import org.xipki.commons.common.util.LogUtil;
import org.xipki.commons.common.util.ParamUtil;
import org.xipki.commons.security.api.HashCalculator;
import org.xipki.commons.security.api.SecurityException;
import org.xipki.commons.security.api.SecurityFactory;
import org.xipki.commons.security.api.p11.P11Constants;
import org.xipki.commons.security.api.p11.P11EntityIdentifier;
import org.xipki.commons.security.api.p11.P11Identity;
import org.xipki.commons.security.api.p11.P11KeyIdentifier;
import org.xipki.commons.security.api.p11.P11MechanismFilter;
import org.xipki.commons.security.api.p11.P11SlotIdentifier;
import org.xipki.commons.security.api.p11.P11TokenException;
import org.xipki.commons.security.api.p11.P11UnknownEntityException;
import org.xipki.commons.security.api.p11.P11UnsupportedMechanismException;
import org.xipki.commons.security.api.p11.P11WritableSlot;
import org.xipki.commons.security.api.p11.parameters.P11Params;
import org.xipki.commons.security.api.p11.parameters.P11RSAPkcsPssParams;
import org.xipki.commons.security.api.util.KeyUtil;
import org.xipki.commons.security.api.util.X509Util;

import iaik.pkcs.pkcs11.Mechanism;
import iaik.pkcs.pkcs11.Session;
import iaik.pkcs.pkcs11.SessionInfo;
import iaik.pkcs.pkcs11.Slot;
import iaik.pkcs.pkcs11.State;
import iaik.pkcs.pkcs11.Token;
import iaik.pkcs.pkcs11.TokenException;
import iaik.pkcs.pkcs11.objects.ByteArrayAttribute;
import iaik.pkcs.pkcs11.objects.Certificate.CertificateType;
import iaik.pkcs.pkcs11.objects.CharArrayAttribute;
import iaik.pkcs.pkcs11.objects.DSAPrivateKey;
import iaik.pkcs.pkcs11.objects.DSAPublicKey;
import iaik.pkcs.pkcs11.objects.ECDSAPrivateKey;
import iaik.pkcs.pkcs11.objects.ECDSAPublicKey;
import iaik.pkcs.pkcs11.objects.PrivateKey;
import iaik.pkcs.pkcs11.objects.PublicKey;
import iaik.pkcs.pkcs11.objects.RSAPrivateKey;
import iaik.pkcs.pkcs11.objects.RSAPublicKey;
import iaik.pkcs.pkcs11.objects.X509PublicKeyCertificate;
import iaik.pkcs.pkcs11.parameters.RSAPkcsPssParameters;
import iaik.pkcs.pkcs11.wrapper.PKCS11Exception;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

class IaikP11Slot implements P11WritableSlot {

    public static final long YEAR = 365L * 24 * 60 * 60 * 1000; // milliseconds of one year

    private static final long DEFAULT_MAX_COUNT_SESSION = 20;

    private static final Logger LOG = LoggerFactory.getLogger(IaikP11Slot.class);

    private final String moduleName;

    private Slot slot;

    private int maxSessionCount;

    private List<char[]> password;

    private long timeOutWaitNewSession = 10000; // maximal wait for 10 second

    private AtomicLong countSessions = new AtomicLong(0);

    private BlockingQueue<Session> idleSessions = new LinkedBlockingDeque<>();

    private ConcurrentHashMap<String, PrivateKey> signingKeysById = new ConcurrentHashMap<>();

    private ConcurrentHashMap<String, PrivateKey> signingKeysByLabel = new ConcurrentHashMap<>();

    private final List<IaikP11Identity> identities = new LinkedList<>();

    private Set<Long> supportedMechanisms;

    private boolean writableSessionInUse;

    private Session writableSession;

    private final P11SlotIdentifier slotId;

    private final int maxMessageSize;

    private final long userType;

    IaikP11Slot(
            final String moduleName,
            final P11SlotIdentifier slotId,
            final Slot slot,
            final long userType,
            final List<char[]> password,
            final int maxMessageSize,
            final P11MechanismFilter mechanismFilter)
    throws P11TokenException {
        ParamUtil.requireNonNull("mechanismFilter", mechanismFilter);
        this.moduleName = ParamUtil.requireNonBlank("moduleName", moduleName);
        this.slotId = ParamUtil.requireNonNull("slotId", slotId);
        this.slot = ParamUtil.requireNonNull("slot", slot);
        this.maxMessageSize = ParamUtil.requireMin("maxMessageSize", maxMessageSize, 1);
        this.userType = userType;
        this.password = password;

        Session session;
        try {
            session = openSession();
        } catch (P11TokenException ex) {
            final String message = "openSession";
            if (LOG.isWarnEnabled()) {
                LOG.warn(LogUtil.buildExceptionLogFormat(message), ex.getClass().getName(),
                        ex.getMessage());
            }
            LOG.debug(message, ex);
            close();
            throw ex;
        }

        try {
            firstLogin(session, password);
        } catch (P11TokenException ex) {
            final String message = "firstLogin";
            if (LOG.isWarnEnabled()) {
                LOG.warn(LogUtil.buildExceptionLogFormat(message), ex.getClass().getName(),
                        ex.getMessage());
            }
            LOG.debug(message, ex);
            close();
            throw ex;
        }

        Token token;
        try {
            token = this.slot.getToken();
        } catch (TokenException ex) {
            throw new P11TokenException("could not get token: " + ex.getMessage(), ex);
        }

        Mechanism[] mechanisms;
        try {
            mechanisms = token.getMechanismList();
        } catch (TokenException ex) {
            throw new P11TokenException("could not get tokenInfo: " + ex.getMessage(), ex);
        }

        Set<Long> mechSet = new HashSet<>();
        if (mechanisms != null) {
            for (Mechanism mech : mechanisms) {
                long mechCode = mech.getMechanismCode();
                if (mechanismFilter.isMechanismPermitted(slotId, mechCode)) {
                    mechSet.add(mechCode);
                }
            }
        }
        this.supportedMechanisms = Collections.unmodifiableSet(mechSet);
        if (LOG.isInfoEnabled()) {
            LOG.info("module {}, slot {}: supported mechanisms: {}", moduleName, slotId,
                    this.supportedMechanisms);
        }

        long maxSessionCount2;
        try {
            maxSessionCount2 = token.getTokenInfo().getMaxSessionCount();
        } catch (TokenException ex) {
            throw new P11TokenException("could not get tokenInfo: " + ex.getMessage(), ex);
        }

        if (maxSessionCount2 == 0) {
            maxSessionCount2 = DEFAULT_MAX_COUNT_SESSION;
        } else {
            // 2 sessions as buffer, they may be used elsewhere.
            maxSessionCount2 = (maxSessionCount2 < 3)
                    ? 1
                    : maxSessionCount2 - 2;
        }
        this.maxSessionCount = (int) maxSessionCount2;
        LOG.info("maxSessionCount: {}", this.maxSessionCount);

        returnIdleSession(session);

        refresh();
    } // constructor

    void refresh()
    throws P11TokenException {
        Set<IaikP11Identity> currentIdentifies = new HashSet<>();

        List<PrivateKey> signatureKeys = getAllPrivateObjects(Boolean.TRUE, null);
        for (PrivateKey signatureKey : signatureKeys) {
            byte[] keyId = signatureKey.getId().getByteArrayValue();
            if (keyId == null || keyId.length == 0) {
                continue;
            }

            try {
                X509PublicKeyCertificate certificateObject = getCertificateObject(keyId, null);

                X509Certificate signatureCert = null;
                java.security.PublicKey signaturePublicKey = null;

                if (certificateObject != null) {
                    byte[] encoded = certificateObject.getValue().getByteArrayValue();
                    try {
                        signatureCert = (X509Certificate) X509Util.parseCert(
                                    new ByteArrayInputStream(encoded));
                    } catch (Exception ex) {
                        String keyIdStr = hex(keyId);
                        final String message = "could not parse certificate with id " + keyIdStr;
                        if (LOG.isWarnEnabled()) {
                            LOG.warn(LogUtil.buildExceptionLogFormat(message),
                                    ex.getClass().getName(), ex.getMessage());
                        }
                        LOG.debug(message, ex);
                        continue;
                    }
                    signaturePublicKey = signatureCert.getPublicKey();
                } else {
                    signatureCert = null;
                    PublicKey publicKeyObject = getPublicKeyObject(
                            Boolean.TRUE, null, keyId, null);
                    if (publicKeyObject == null) {
                        String msg =
                                "neither certificate nor public key for signing is available";
                        LOG.info(msg);
                        continue;
                    }

                    signaturePublicKey = generatePublicKey(publicKeyObject);
                }

                Map<String, Set<X509Certificate>> allCerts = new HashMap<>();
                List<X509Certificate> certChain = new LinkedList<>();

                if (signatureCert != null) {
                    certChain.add(signatureCert);
                    while (true) {
                        X509Certificate context = certChain.get(certChain.size() - 1);
                        if (X509Util.isSelfSigned(context)) {
                            break;
                        }

                        String issuerSubject = signatureCert.getIssuerX500Principal().getName();
                        Set<X509Certificate> issuerCerts = allCerts.get(issuerSubject);
                        if (issuerCerts == null) {
                            issuerCerts = new HashSet<>();
                            X509PublicKeyCertificate[] certObjects = getCertificateObjects(
                                    signatureCert.getIssuerX500Principal());
                            if (certObjects != null && certObjects.length > 0) {
                                for (X509PublicKeyCertificate certObject : certObjects) {
                                    issuerCerts.add(X509Util.parseCert(
                                            certObject.getValue().getByteArrayValue()));
                                }
                            }

                            if (CollectionUtil.isNonEmpty(issuerCerts)) {
                                allCerts.put(issuerSubject, issuerCerts);
                            }
                        }

                        if (CollectionUtil.isEmpty(issuerCerts)) {
                            break;
                        }

                        // find the certificate
                        for (X509Certificate issuerCert : issuerCerts) {
                            try {
                                context.verify(issuerCert.getPublicKey());
                                certChain.add(issuerCert);
                            } catch (Exception ex) { // CHECKSTYLE:SKIP
                            }
                        }
                    } // end while (true)
                } // end if (signatureCert != null)

                P11KeyIdentifier tmpKeyId = new P11KeyIdentifier(
                        signatureKey.getId().getByteArrayValue(),
                        new String(signatureKey.getLabel().getCharArrayValue()));

                IaikP11Identity identity = new IaikP11Identity(moduleName,
                        new P11EntityIdentifier(slotId, tmpKeyId),
                        certChain.toArray(new X509Certificate[0]), signaturePublicKey);
                currentIdentifies.add(identity);
            } catch (SecurityException ex) {
                String keyIdStr = hex(keyId);
                final String message = "SignerException while initializing key with key-id "
                        + keyIdStr;
                if (LOG.isWarnEnabled()) {
                    LOG.warn(LogUtil.buildExceptionLogFormat(message), ex.getClass().getName(),
                            ex.getMessage());
                }
                LOG.debug(message, ex);
                continue;
            } catch (Throwable th) {
                String keyIdStr = hex(keyId);
                final String message =
                        "unexpected exception while initializing key with key-id " + keyIdStr;
                if (LOG.isWarnEnabled()) {
                    LOG.warn(LogUtil.buildExceptionLogFormat(message), th.getClass().getName(),
                            th.getMessage());
                }
                LOG.debug(message, th);
                continue;
            }
        } // end for (PrivateKey signatureKey : signatureKeys)

        this.identities.clear();
        this.identities.addAll(currentIdentifies);
        currentIdentifies.clear();
    } // method refresh

    byte[] sign(
            final long mechanism,
            final P11Params parameters,
            final byte[] content,
            final P11KeyIdentifier keyId)
    throws P11TokenException {
        ParamUtil.requireNonNull("content", content);
        assertMechanismSupported(mechanism);

        int len = content.length;
        if (len <= maxMessageSize) {
            return singleSign(mechanism, parameters, content, keyId);
        }

        PrivateKey signingKey = getSigningKey(keyId);
        Mechanism mechanismObj = getMechanism(mechanism, parameters);
        if (LOG.isTraceEnabled()) {
            LOG.debug("sign (init, update, then finish) with private key:\n{}", signingKey);
        }

        Session session = borrowIdleSession();
        if (session == null) {
            throw new P11TokenException("no idle session available");
        }

        try {
            synchronized (session) {
                login(session);
                session.signInit(mechanismObj, signingKey);
                for (int i = 0; i < len; i += maxMessageSize) {
                    int blockLen = Math.min(maxMessageSize, len - i);
                    byte[] block = new byte[blockLen];
                    System.arraycopy(content, i, block, 0, blockLen);
                    session.signUpdate(block);
                }

                byte[] signature = session.signFinal();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("signature:\n{}", Hex.toHexString(signature));
                }
                return signature;
            }
        } catch (TokenException e) {
            throw new P11TokenException(e);
        } finally {
            returnIdleSession(session);
        }
    }

    private byte[] singleSign(
            final long mechanism,
            final P11Params parameters,
            final byte[] hash,
            final P11KeyIdentifier keyId)
    throws P11TokenException {
        PrivateKey signingKey = getSigningKey(keyId);
        Mechanism mechanismObj = getMechanism(mechanism, parameters);
        if (LOG.isTraceEnabled()) {
            LOG.debug("sign with private key:\n{}", signingKey);
        }

        Session session = borrowIdleSession();
        if (session == null) {
            throw new P11TokenException("no idle session available");
        }

        byte[] signature;
        try {
            synchronized (session) {
                login(session);
                session.signInit(mechanismObj, signingKey);
                signature = session.sign(hash);
            }
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        } finally {
            returnIdleSession(session);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("signature:\n{}", Hex.toHexString(signature));
        }
        return signature;
    } // method CKM_SIGN

    private PrivateKey getSigningKey(
            final P11KeyIdentifier keyId)
    throws P11TokenException {
        PrivateKey signingKey;
        synchronized (keyId) {
            if (keyId.getKeyId() != null) {
                signingKey = signingKeysById.get(keyId.getKeyIdHex());
            } else {
                signingKey = signingKeysByLabel.get(keyId.getKeyLabel());
            }

            if (signingKey == null) {
                LOG.info("try to retieve private key " + keyId);
                signingKey = getPrivateObject(Boolean.TRUE, null, keyId);

                if (signingKey != null) {
                    LOG.info("found private key " + keyId);
                    signingKey = cacheSigningKey(signingKey);
                } else {
                    LOG.warn("could not find private key " + keyId);
                }
            }
        }

        if (signingKey == null) {
            throw new P11TokenException("No key for signing is available");
        }
        return signingKey;
    }

    private static Mechanism getMechanism(
            final long mechanism,
            final P11Params parameters)
    throws P11TokenException {
        Mechanism ret = Mechanism.get(mechanism);
        if (parameters == null) {
            return ret;
        }

        if (parameters instanceof P11RSAPkcsPssParams) {
            P11RSAPkcsPssParams param = (P11RSAPkcsPssParams) parameters;
            RSAPkcsPssParameters paramObj = new RSAPkcsPssParameters(
                    Mechanism.get(param.getHashAlgorithm()), param.getMaskGenerationFunction(),
                    param.getSaltLength());
            ret.setParameters(paramObj);
        } else {
            throw new P11TokenException("unknown P11Parameters " + parameters.getClass().getName());
        }
        return ret;
    }

    private Session openSession()
    throws P11TokenException {
        return openSession(false);
    }

    private Session openSession(
            final boolean rwSession)
    throws P11TokenException {
        Session session;
        try {
            session = slot.getToken().openSession(
                    Token.SessionType.SERIAL_SESSION, rwSession, null, null);
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        }
        countSessions.incrementAndGet();
        return session;
    }

    private void closeSession(
            final Session session)
    throws P11TokenException {
        try {
            session.closeSession();
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        } finally {
            countSessions.decrementAndGet();
        }
    }

    private synchronized Session borrowWritableSession()
    throws P11TokenException {
        if (writableSession == null) {
            writableSession = openSession(true);
        }

        if (writableSessionInUse) {
            throw new P11TokenException("no idle writable session available");
        }

        writableSessionInUse = true;
        return writableSession;
    }

    private synchronized void returnWritableSession(
            final Session session)
    throws P11TokenException {
        if (session != writableSession) {
            throw new P11TokenException("the returned session does not belong to me");
        }
        this.writableSessionInUse = false;
    }

    private Session borrowIdleSession()
    throws P11TokenException {
        if (countSessions.get() < maxSessionCount) {
            Session session = idleSessions.poll();
            if (session == null) {
                // create new session
                session = openSession();
            }

            if (session != null) {
                return session;
            }
        }

        try {
            return idleSessions.poll(timeOutWaitNewSession, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) { // CHECKSTYLE:SKIP
        }

        throw new P11TokenException("no idle session");
    }

    private void returnIdleSession(
            final Session session) {
        if (session == null) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            try {
                idleSessions.put(session);
                return;
            } catch (InterruptedException ex) { // CHECKSTYLE:SKIP
            }
        }

        try {
            closeSession(session);
        } catch (P11TokenException ex) {
            LOG.error("could not closeSession {}: {}", ex.getClass().getName(), ex.getMessage());
            LOG.debug("closeSession", ex);
        }
    }

    private void firstLogin(
            final Session session,
            final List<char[]> password)
    throws P11TokenException {
        try {
            boolean isProtectedAuthenticationPath =
                    session.getToken().getTokenInfo().isProtectedAuthenticationPath();

            if (isProtectedAuthenticationPath || CollectionUtil.isEmpty(password)) {
                LOG.info("verify on PKCS11Module with PROTECTED_AUTHENTICATION_PATH");
                // some driver does not accept null PIN
                session.login(Session.UserType.USER, "".toCharArray());
                this.password = null;
            } else {
                LOG.info("verify on PKCS11Module with PIN");

                for (char[] singlePwd : password) {
                    session.login(Session.UserType.USER, singlePwd);
                }
                this.password = password;
            }
        } catch (PKCS11Exception ex) {
            // 0x100: user already logged in
            if (ex.getErrorCode() != 0x100) {
                throw new P11TokenException(ex.getMessage(), ex);
            }
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        }
    }

    void login()
    throws P11TokenException {
        Session session = borrowIdleSession();
        try {
            login(session);
        } finally {
            returnIdleSession(session);
        }
    }

    private void login(
            final Session session)
    throws P11TokenException {
        boolean isSessionLoggedIn = checkSessionLoggedIn(session);
        if (isSessionLoggedIn) {
            return;
        }

        boolean loginRequired;
        try {
            loginRequired = session.getToken().getTokenInfo().isLoginRequired();
        } catch (TokenException ex) {
            String msg = "could not check whether LoginRequired of token";
            LOG.error(LogUtil.buildExceptionLogFormat(msg),
                    ex.getClass().getName(), ex.getMessage());
            LOG.debug(msg, ex);
            loginRequired = true;
        }

        LOG.debug("loginRequired: {}", loginRequired);
        if (!loginRequired) {
            return;
        }

        if (CollectionUtil.isEmpty(password)) {
            login(session, null);
        } else {
            for (char[] singlePwd : password) {
                login(session, singlePwd);
            }
        }
    }

    private void login(Session session, char[] pin)
    throws P11TokenException {
        try {
            if (userType == P11Constants.CKU_USER) {
                session.login(Session.UserType.USER, pin);
                return;
            } else if (userType == P11Constants.CKU_SO) {
                session.login(Session.UserType.SO, pin);
                return;
            }

            final long handle = session.getSessionHandle();
            final boolean useUtf8Encoding = true;
            session.getModule().getPKCS11Module().C_Login(handle, userType, pin, useUtf8Encoding);
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        }
    }

    void close() {
        if (slot != null) {
            try {
                LOG.info("close all sessions on token: {}", slot.getSlotID());
                slot.getToken().closeAllSessions();
            } catch (Throwable th) {
                final String message = "could not slot.getToken().closeAllSessions()";
                if (LOG.isWarnEnabled()) {
                    LOG.warn(LogUtil.buildExceptionLogFormat(message), th.getClass().getName(),
                            th.getMessage());
                }
                LOG.debug(message, th);
            }

            slot = null;
        }

        // clear the session pool
        idleSessions.clear();
        countSessions.lazySet(0);
    }

    Set<Long> getSupportedMechanisms() {
        return supportedMechanisms;
    }

    boolean supportsMechanism(
            final long mechanism) {
        return supportedMechanisms.contains(mechanism);
    }

    private void assertMechanismSupported(
            final long mechanism)
    throws P11UnsupportedMechanismException {
        if (!supportedMechanisms.contains(mechanism)) {
            throw new P11UnsupportedMechanismException(mechanism, slotId);
        }
    }

    private List<PrivateKey> getAllPrivateObjects(
            final Boolean forSigning,
            final Boolean forDecrypting)
    throws P11TokenException {
        Session session = borrowIdleSession();

        try {
            if (LOG.isTraceEnabled()) {
                String info = listPrivateKeyObjects(session, forSigning, forDecrypting);
                LOG.debug(info);
            }

            PrivateKey template = new PrivateKey();
            if (forSigning != null) {
                template.getSign().setBooleanValue(forSigning);
            }
            if (forDecrypting != null) {
                template.getDecrypt().setBooleanValue(forDecrypting);
            }

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            if (CollectionUtil.isEmpty(tmpObjects)) {
                return Collections.emptyList();
            }

            final int n = tmpObjects.size();
            LOG.info("found {} private keys", n);

            List<PrivateKey> privateKeys = new ArrayList<>(n);
            for (iaik.pkcs.pkcs11.objects.Object tmpObject : tmpObjects) {
                PrivateKey privateKey = (PrivateKey) tmpObject;
                privateKeys.add(privateKey);
                cacheSigningKey(privateKey);
            }

            return privateKeys;
        } finally {
            returnIdleSession(session);
        }
    }

    private List<X509PublicKeyCertificate> getAllCertificateObjects()
    throws P11TokenException {
        Session session = borrowIdleSession();
        try {
            if (LOG.isTraceEnabled()) {
                String info = listCertificateObjects(session);
                LOG.debug(info);
            }
            X509PublicKeyCertificate template = new X509PublicKeyCertificate();
            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            final int n = tmpObjects.size();
            List<X509PublicKeyCertificate> certs = new ArrayList<>(n);
            for (iaik.pkcs.pkcs11.objects.Object tmpObject : tmpObjects) {
                X509PublicKeyCertificate cert = (X509PublicKeyCertificate) tmpObject;
                certs.add(cert);
            }
            return certs;
        } finally {
            returnIdleSession(session);
        }
    }

    private PrivateKey cacheSigningKey(
            final PrivateKey privateKey) {
        Boolean bo = privateKey.getSign().getBooleanValue();
        byte[] id = privateKey.getId().getByteArrayValue();
        char[] tmpLabel = privateKey.getLabel().getCharArrayValue();
        String label = (tmpLabel == null)
                ? null
                : new String(tmpLabel);

        if (bo == null || !bo.booleanValue()) {
            LOG.warn("key {} is not for signing", new P11KeyIdentifier(id, label));
            return null;
        }

        if (id != null) {
            signingKeysById.put(Hex.toHexString(id).toUpperCase(), privateKey);
        }
        if (label != null) {
            signingKeysByLabel.put(label, privateKey);
        }

        return privateKey;
    }

    private PrivateKey getPrivateObject(
            final Boolean forSigning,
            final Boolean forDecrypting,
            final P11KeyIdentifier keyIdentifier)
    throws P11TokenException {
        String tmpKeyLabel = keyIdentifier.getKeyLabel();
        char[] keyLabel = (tmpKeyLabel == null)
                ? null
                : tmpKeyLabel.toCharArray();
        byte[] keyId = keyIdentifier.getKeyId();

        Session session = borrowIdleSession();

        try {
            if (LOG.isTraceEnabled()) {
                String info = listPrivateKeyObjects(session, forSigning, forDecrypting);
                LOG.debug(info);
            }

            PrivateKey template = new PrivateKey();
            if (forSigning != null) {
                template.getSign().setBooleanValue(forSigning);
            }
            if (forDecrypting != null) {
                template.getDecrypt().setBooleanValue(forDecrypting);
            }
            if (keyId != null) {
                template.getId().setByteArrayValue(keyId);
            }
            if (keyLabel != null) {
                template.getLabel().setCharArrayValue(keyLabel);
            }

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            if (CollectionUtil.isEmpty(tmpObjects)) {
                return null;
            }

            int size = tmpObjects.size();
            if (size > 1) {
                LOG.warn("found {} private key identified by {}, use the first one",
                        size, getDescription(keyId, keyLabel));
            }
            return (PrivateKey) tmpObjects.get(0);
        } finally {
            returnIdleSession(session);
        }
    } // method getPrivateObject

    private String listPrivateKeyObjects(
            final Session session,
            final Boolean forSigning,
            final Boolean forDecrypting) {
        try {
            StringBuilder msg = new StringBuilder();
            msg.append("available private keys: ");
            msg.append("forSigning: ").append(forSigning);
            msg.append(", forDecrypting: ").append(forDecrypting).append("\n");

            PrivateKey template = new PrivateKey();
            if (forSigning != null) {
                template.getSign().setBooleanValue(forSigning);
            }
            if (forDecrypting != null) {
                template.getDecrypt().setBooleanValue(forDecrypting);
            }
            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            if (CollectionUtil.isEmpty(tmpObjects)) {
                msg.append(" empty");
            }
            for (int i = 0; i < tmpObjects.size(); i++) {
                PrivateKey privKey = (PrivateKey) tmpObjects.get(i);
                msg.append("------------------------PrivateKey ");
                msg.append(i + 1);
                msg.append("-------------------------\n");

                msg.append("\tid(hex): ");
                ByteArrayAttribute id = privKey.getId();

                byte[] bytes = null;
                if (id != null) {
                    bytes = id.getByteArrayValue();
                }
                if (bytes == null) {
                    msg.append("null");
                } else {
                    msg.append(Hex.toHexString(bytes));
                }
                msg.append("\n");

                msg.append("\tlabel: ");
                CharArrayAttribute label = privKey.getLabel();
                msg.append(toString(label)).append("\n");
            }
            return msg.toString();
        } catch (Throwable th) {
            return "Exception while calling listPrivateKeyObjects(): " + th.getMessage();
        }
    } // method listPrivateKeyObjects

    private PublicKey getPublicKeyObject(
            final Boolean forSignature,
            final Boolean forCipher,
            final byte[] keyId,
            final char[] keyLabel)
    throws P11TokenException {
        Session session = borrowIdleSession();

        try {
            if (LOG.isTraceEnabled()) {
                String info = listPublicKeyObjects(session, forSignature, forCipher);
                LOG.debug(info);
            }

            iaik.pkcs.pkcs11.objects.PublicKey template =
                    new iaik.pkcs.pkcs11.objects.PublicKey();
            if (keyId != null) {
                template.getId().setByteArrayValue(keyId);
            }
            if (keyLabel != null) {
                template.getLabel().setCharArrayValue(keyLabel);
            }

            if (forSignature != null) {
                template.getVerify().setBooleanValue(forSignature);
            }
            if (forCipher != null) {
                template.getEncrypt().setBooleanValue(forCipher);
            }

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            if (CollectionUtil.isEmpty(tmpObjects)) {
                return null;
            }

            int size = tmpObjects.size();
            if (size > 1) {
                LOG.warn("found {} public key identified by {}, use the first one",
                        size, getDescription(keyId, keyLabel));
            }

            iaik.pkcs.pkcs11.objects.PublicKey p11Key =
                    (iaik.pkcs.pkcs11.objects.PublicKey) tmpObjects.get(0);
            return p11Key;
        } finally {
            returnIdleSession(session);
        }
    } // method getPublicKeyObject

    private X509PublicKeyCertificate[] getCertificateObjects(
            final X500Principal subject)
    throws P11TokenException {
        Session session = borrowIdleSession();

        try {
            if (LOG.isTraceEnabled()) {
                String info = listCertificateObjects(session);
                LOG.debug(info);
            }

            X509PublicKeyCertificate template = new X509PublicKeyCertificate();
            template.getCertificateType().setLongValue(CertificateType.X_509_PUBLIC_KEY);
            template.getSubject().setByteArrayValue(subject.getEncoded());

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            final int n = (tmpObjects == null)
                    ? 0
                    : tmpObjects.size();

            if (n == 0) {
                LOG.warn("found no certificate with subject {}",
                        X509Util.getRfc4519Name(subject));
                return null;
            }

            X509PublicKeyCertificate[] certs = new X509PublicKeyCertificate[n];
            for (int i = 0; i < n; i++) {
                certs[i] = (X509PublicKeyCertificate) tmpObjects.get(i);
            }
            return certs;
        } finally {
            returnIdleSession(session);
        }
    } // method getCertificateObjects

    private X509PublicKeyCertificate[] getCertificateObjects(
            final byte[] keyId,
            final char[] keyLabel)
    throws P11TokenException {
        Session session = borrowIdleSession();

        try {
            if (LOG.isTraceEnabled()) {
                String info = listCertificateObjects(session);
                LOG.debug(info);
            }

            X509PublicKeyCertificate template = new X509PublicKeyCertificate();
            if (keyId != null) {
                template.getId().setByteArrayValue(keyId);
            }
            if (keyLabel != null) {
                template.getLabel().setCharArrayValue(keyLabel);
            }

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            if (CollectionUtil.isEmpty(tmpObjects)) {
                LOG.info("found no certificate identified by {}",
                        getDescription(keyId, keyLabel));
                return null;
            }

            int size = tmpObjects.size();
            X509PublicKeyCertificate[] certs = new X509PublicKeyCertificate[size];
            for (int i = 0; i < size; i++) {
                certs[i] = (X509PublicKeyCertificate) tmpObjects.get(i);
            }
            return certs;
        } finally {
            returnIdleSession(session);
        }
    } // method getCertificateObjects

    private X509PublicKeyCertificate getCertificateObject(
            final byte[] keyId,
            final char[] keyLabel)
    throws P11TokenException {
        X509PublicKeyCertificate[] certs = getCertificateObjects(keyId, keyLabel);
        if (certs == null) {
            return null;
        }
        if (certs.length > 1) {
            LOG.warn("found {} public key identified by {}, use the first one",
                    certs.length, getDescription(keyId, keyLabel));
        }
        return certs[0];
    }

    private boolean existsCertificateObjects(
            final byte[] keyId,
            final char[] keyLabel)
    throws P11TokenException {
        Session session = borrowIdleSession();

        try {
            if (LOG.isTraceEnabled()) {
                String info = listCertificateObjects(session);
                LOG.debug(info);
            }

            X509PublicKeyCertificate template = new X509PublicKeyCertificate();
            if (keyId != null) {
                template.getId().setByteArrayValue(keyId);
            }
            if (keyLabel != null) {
                template.getLabel().setCharArrayValue(keyLabel);
            }

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template, 1);
            return !CollectionUtil.isEmpty(tmpObjects);
        } finally {
            returnIdleSession(session);
        }
    }

    private String listCertificateObjects(
            final Session session) {
        try {
            StringBuilder msg = new StringBuilder();
            msg.append("available certificates: ");

            X509PublicKeyCertificate template = new X509PublicKeyCertificate();

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            if (CollectionUtil.isEmpty(tmpObjects)) {
                msg.append(" empty");
            }
            for (int i = 0; i < tmpObjects.size(); i++) {
                X509PublicKeyCertificate cert = (X509PublicKeyCertificate) tmpObjects.get(i);
                msg.append("------------------------Certificate ")
                    .append(i + 1)
                    .append("-------------------------\n");

                msg.append("\tid(hex): ");
                ByteArrayAttribute id = cert.getId();
                byte[] bytes = null;
                if (id != null) {
                    bytes = id.getByteArrayValue();
                }

                if (bytes == null) {
                    msg.append("null");
                } else {
                    msg.append(Hex.toHexString(bytes));
                }
                msg.append("\n");

                msg.append("\tlabel: ");
                CharArrayAttribute label = cert.getLabel();
                msg.append(toString(label)).append("\n");
            }
            return msg.toString();
        } catch (Throwable th) {
            return "Exception while calling listCertificateObjects(): " + th.getMessage();
        }
    } // method listCertificateObjects

    @Override
    public void updateCertificate(
            final P11KeyIdentifier keyIdentifier,
            final X509Certificate newCert,
            final Set<X509Certificate> caCerts,
            final SecurityFactory securityFactory)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonNull("keyIdentifier", keyIdentifier);
        ParamUtil.requireNonNull("newCert", newCert);

        PrivateKey privKey = getPrivateObject(null, null, keyIdentifier);

        if (privKey == null) {
            throw new P11UnknownEntityException("could not find private key " + keyIdentifier);
        }

        byte[] keyId = privKey.getId().getByteArrayValue();
        X509PublicKeyCertificate[] existingCerts = getCertificateObjects(keyId, null);

        assertMatch(newCert, keyIdentifier, securityFactory);

        X509Certificate[] certChain = X509Util.buildCertPath(newCert, caCerts);

        Session session = borrowWritableSession();
        try {
            X509PublicKeyCertificate newCertTemp = createPkcs11Template(newCert, null, keyId,
                    privKey.getLabel().getCharArrayValue());
            // delete existing signer certificate objects
            if (existingCerts != null && existingCerts.length > 0) {
                for (X509PublicKeyCertificate existingCert : existingCerts) {
                    session.destroyObject(existingCert);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    throw new P11TokenException("could not destroy object, interrupted");
                }
            }

            // create new signer certificate object
            session.createObject(newCertTemp);

            // create CA certificate objects
            if (certChain.length > 1) {
                for (int i = 1; i < certChain.length; i++) {
                    X509Certificate caCert = certChain[i];
                    byte[] encodedCaCert;
                    try {
                        encodedCaCert = caCert.getEncoded();
                    } catch (CertificateEncodingException ex) {
                        throw new SecurityException(
                                "could not encode certificate: " + ex.getMessage(), ex);
                    }

                    boolean alreadyExists = false;
                    X509PublicKeyCertificate[] certObjs = getCertificateObjects(
                            caCert.getSubjectX500Principal());
                    if (certObjs != null) {
                        for (X509PublicKeyCertificate certObj : certObjs) {
                            if (Arrays.equals(encodedCaCert,
                                    certObj.getValue().getByteArrayValue())) {
                                alreadyExists = true;
                                break;
                            }
                        }
                    }

                    if (alreadyExists) {
                        continue;
                    }

                    byte[] caCertKeyId = IaikP11Util.generateKeyId(session);

                    X500Name caX500Name = X500Name.getInstance(
                            caCert.getSubjectX500Principal().getEncoded());
                    String caCommonName = X509Util.getCommonName(caX500Name);

                    String label = null;
                    for (int j = 0;; j++) {
                        label = (j == 0)
                                ? caCommonName
                                : caCommonName + "-" + j;
                        if (!existsCertificateObjects(null, label.toCharArray())) {
                            break;
                        }
                    }

                    X509PublicKeyCertificate newCaCertTemp = createPkcs11Template(
                            caCert, encodedCaCert, caCertKeyId, label.toCharArray());
                    session.createObject(newCaCertTemp);
                }
            } // end if(certChain.length)
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        } finally {
            returnWritableSession(session);
        }
    } // method updateCertificate

    @Override
    public boolean removeKey(
            final P11KeyIdentifier keyIdentifier)
    throws SecurityException, P11TokenException {
        return doRemoveKeyAndCerts(keyIdentifier, false);
    }

    @Override
    public boolean removeKeyAndCerts(
            final P11KeyIdentifier keyIdentifier)
    throws SecurityException, P11TokenException {
        return doRemoveKeyAndCerts(keyIdentifier, true);
    }

    private boolean doRemoveKeyAndCerts(
            final P11KeyIdentifier keyIdentifier, boolean removeCerts)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonNull("keyIdentifier", keyIdentifier);

        PrivateKey privKey = getPrivateObject(null, null, keyIdentifier);
        if (privKey == null) {
            return false;
        }

        StringBuilder msgBuilder = new StringBuilder();
        Session session = borrowWritableSession();
        try {
            try {
                session.destroyObject(privKey);
            } catch (TokenException ex) {
                msgBuilder.append("could not delete private key, ");
            }

            PublicKey pubKey = getPublicKeyObject(null, null,
                    privKey.getId().getByteArrayValue(), null);
            if (pubKey != null) {
                try {
                    session.destroyObject(pubKey);
                } catch (TokenException ex) {
                    msgBuilder.append("could not delete public key, ");
                }
            }

            if (removeCerts) {
                X509PublicKeyCertificate[] certs = getCertificateObjects(
                        privKey.getId().getByteArrayValue(), null);
                if (certs != null && certs.length > 0) {
                    for (int i = 0; i < certs.length; i++) {
                        try {
                            session.destroyObject(certs[i]);
                        } catch (TokenException ex) {
                            msgBuilder.append("could not delete certificate at index ")
                                .append(i)
                                .append(", ");
                        }
                    } // end for
                } // end if (certs)
            } // end removeCerts
        } finally {
            returnWritableSession(session);
        }

        final int n = msgBuilder.length();
        if (n > 2) {
            throw new SecurityException(msgBuilder.substring(0, n - 2));
        }

        return true;
    } // method doRemoveKeyAndCerts

    @Override
    public void removeCerts(
            final P11KeyIdentifier keyIdentifier)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonNull("keyIdentifier", keyIdentifier);

        String keyLabel = keyIdentifier.getKeyLabel();
        char[] keyLabelChars = (keyLabel == null)
                ? null
                : keyLabel.toCharArray();

        X509PublicKeyCertificate[] existingCerts = getCertificateObjects(
                keyIdentifier.getKeyId(), keyLabelChars);

        if (existingCerts == null || existingCerts.length == 0) {
            throw new SecurityException("could not find certificates with id " + keyIdentifier);
        }

        Session session = borrowWritableSession();
        try {
            for (X509PublicKeyCertificate cert : existingCerts) {
                session.destroyObject(cert);
            }
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        } finally {
            returnWritableSession(session);
        }
    }

    private String listPublicKeyObjects(
            final Session session,
            final Boolean forSignature,
            final Boolean forCipher) {
        try {
            StringBuilder msg = new StringBuilder();
            msg.append("available public keys: ");
            msg.append("forSignature: ").append(forSignature);
            msg.append(", forCipher: ").append(forCipher).append("\n");

            iaik.pkcs.pkcs11.objects.PublicKey template =
                    new iaik.pkcs.pkcs11.objects.PublicKey();
            if (forSignature != null) {
                template.getVerify().setBooleanValue(forSignature);
            }
            if (forCipher != null) {
                template.getEncrypt().setBooleanValue(forCipher);
            }

            List<iaik.pkcs.pkcs11.objects.Object> tmpObjects = getObjects(session, template);
            if (CollectionUtil.isEmpty(tmpObjects)) {
                msg.append(" empty");
            }
            for (int i = 0; i < tmpObjects.size(); i++) {
                iaik.pkcs.pkcs11.objects.PublicKey pubKey =
                        (iaik.pkcs.pkcs11.objects.PublicKey) tmpObjects.get(i);
                msg.append("------------------------Public Key ")
                    .append(i + 1)
                    .append("-------------------------\n");
                msg.append("\tid(hex): ");
                ByteArrayAttribute id = pubKey.getId();
                byte[] bytes = null;
                if (id != null) {
                    bytes = id.getByteArrayValue();
                }

                if (bytes == null) {
                    msg.append("null");
                } else {
                    msg.append(Hex.toHexString(bytes));
                }
                msg.append("\n");

                msg.append("\tlabel: ");
                CharArrayAttribute label = pubKey.getLabel();
                msg.append(toString(label)).append("\n");
            } // end for
            return msg.toString();
        } catch (Throwable th) {
            return "Exception while calling listPublicKeyObjects(): " + th.getMessage();
        }
    } // method listPublicKeyObjects

    private void assertMatch(
            final X509Certificate cert,
            final P11KeyIdentifier keyId,
            final SecurityFactory securityFactory)
    throws SecurityException {
        ParamUtil.requireNonNull("securityFactory", securityFactory);
        ConfPairs pairs = new ConfPairs("slot-id", Long.toString(slot.getSlotID()));
        if (keyId.getKeyId() != null) {
            pairs.putPair("key-id", Hex.toHexString(keyId.getKeyId()));
        }
        if (keyId.getKeyLabel() != null) {
            pairs.putPair("key-label", keyId.getKeyLabel());
        }

        securityFactory.createSigner("PKCS11", pairs.getEncoded(), "SHA1", null, cert);
    }

    @Override
    public P11KeyIdentifier addCert(
            final X509Certificate cert)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonNull("cert", cert);
        Session session = borrowWritableSession();
        try {
            byte[] encodedCert = cert.getEncoded();

            X509PublicKeyCertificate[] certObjs = getCertificateObjects(
                    cert.getSubjectX500Principal());
            if (certObjs != null) {
                for (X509PublicKeyCertificate certObj : certObjs) {
                    if (Arrays.equals(encodedCert, certObj.getValue().getByteArrayValue())) {
                        P11KeyIdentifier p11KeyId = new P11KeyIdentifier(
                                certObj.getId().getByteArrayValue(),
                                new String(certObj.getLabel().getCharArrayValue()));
                        throw new SecurityException(
                                "given certificate already exists under " + p11KeyId);
                    }
                }
            }

            byte[] keyId = IaikP11Util.generateKeyId(session);

            X500Name x500Name = X500Name.getInstance(cert.getSubjectX500Principal().getEncoded());
            String cn = X509Util.getCommonName(x500Name);

            String label = null;
            for (int j = 0;; j++) {
                label = (j == 0)
                        ? cn
                        : cn + "-" + j;
                if (!existsCertificateObjects(null, label.toCharArray())) {
                    break;
                }
            }

            X509PublicKeyCertificate newCaCertTemp = createPkcs11Template(
                    cert, encodedCert, keyId, label.toCharArray());
            session.createObject(newCaCertTemp);
            P11KeyIdentifier p11KeyId = new P11KeyIdentifier(keyId,
                    new String(newCaCertTemp.getLabel().getCharArrayValue()));
            return p11KeyId;
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        } catch (CertificateEncodingException ex) {
            throw new SecurityException(ex.getMessage(), ex);
        } finally {
            returnWritableSession(session);
        }
    } // method addCert

    @Override
    public P11KeyIdentifier generateRSAKeypair(
            final int keySize,
            final BigInteger publicExponent,
            final String label)
    throws P11TokenException {
        ParamUtil.requireNonBlank("label", label);
        ParamUtil.requireMin("keySize", keySize, 1024);

        if (keySize % 1024 != 0) {
            throw new IllegalArgumentException("key size is not multiple of 1024: " + keySize);
        }

        Session session = borrowWritableSession();
        try {
            if (IaikP11Util.labelExists(session, label)) {
                throw new IllegalArgumentException(
                        "label " + label + " exists, please specify another one");
            }

            byte[] id = IaikP11Util.generateKeyId(session);

            generateRSAKeyPair(
                    session,
                    keySize, publicExponent, id, label);

            return new P11KeyIdentifier(id, label);
        } finally {
            returnWritableSession(session);
        }
    } // method generateRSAKeypair

    @Override
    public P11KeyIdentifier generateDSAKeypair(
            final int plength,
            final int qlength,
            final String label)
    throws P11TokenException {
        ParamUtil.requireNonBlank("label", label);
        ParamUtil.requireMin("pLength", plength, 1024);

        if (plength % 1024 != 0) {
            throw new IllegalArgumentException("key size is not multiple of 1024: " + plength);
        }

        Session session = borrowWritableSession();
        try {
            if (IaikP11Util.labelExists(session, label)) {
                throw new IllegalArgumentException(
                        "label " + label + " exists, please specify another one");
            }

            byte[] id = IaikP11Util.generateKeyId(session);
            generateDSAKeyPair(session, plength, qlength, id, label);
            return new P11KeyIdentifier(id, label);
        } finally {
            returnWritableSession(session);
        }
    }

    @Override
    public P11KeyIdentifier generateECKeypair(
            final String curveNameOrOid,
            final String label)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonBlank("curveNameOrOid", curveNameOrOid);
        ParamUtil.requireNonBlank("label", label);

        ASN1ObjectIdentifier curveId = getCurveId(curveNameOrOid);
        if (curveId == null) {
            throw new IllegalArgumentException("unknown curve " + curveNameOrOid);
        }

        X9ECParameters ecParams = ECNamedCurveTable.getByOID(curveId);
        if (ecParams == null) {
            throw new IllegalArgumentException("unknown curve " + curveNameOrOid);
        }

        Session session = borrowWritableSession();
        try {
            if (IaikP11Util.labelExists(session, label)) {
                throw new IllegalArgumentException(
                        "label " + label + " exists, please specify another one");
            }

            byte[] id = IaikP11Util.generateKeyId(session);

            generateECKeyPair(
                    session, curveId, ecParams, id, label);

            return new P11KeyIdentifier(id, label);
        } finally {
            returnWritableSession(session);
        }
    } // method generateECKeypair

    // CHECKSTYLE:SKIP
    private void generateDSAKeyPair(
            final Session session,
            final int plength,
            final int qlength,
            final byte[] id,
            final String label)
    throws P11TokenException {
        long mech = P11Constants.CKM_DSA_KEY_PAIR_GEN;
        assertMechanismSupported(mech);

        DSAParametersGenerator paramGen = new DSAParametersGenerator(new SHA512Digest());
        DSAParameterGenerationParameters genParams = new DSAParameterGenerationParameters(
                plength, qlength, 80, new SecureRandom());
        paramGen.init(genParams);
        DSAParameters dsaParams = paramGen.generateParameters();

        DSAPrivateKey privateKey = new DSAPrivateKey();
        DSAPublicKey publicKey = new DSAPublicKey();

        setKeyAttributes(id, label, P11Constants.CKK_DSA, publicKey, privateKey);

        publicKey.getPrime().setByteArrayValue(dsaParams.getP().toByteArray());
        publicKey.getSubprime().setByteArrayValue(dsaParams.getQ().toByteArray());
        publicKey.getBase().setByteArrayValue(dsaParams.getG().toByteArray());

        try {
            session.generateKeyPair(Mechanism.get(mech), publicKey, privateKey);
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        }
    } // method generateDSAKeyPair

    // CHECKSTYLE:SKIP
    private void generateRSAKeyPair(
            final Session session,
            final int keySize,
            final BigInteger publicExponent,
            final byte[] id,
            final String label)
    throws P11TokenException {
        long mech = P11Constants.CKM_RSA_PKCS_KEY_PAIR_GEN;
        assertMechanismSupported(mech);

        BigInteger tmpPublicExponent = publicExponent;
        if (tmpPublicExponent == null) {
            tmpPublicExponent = BigInteger.valueOf(65537);
        }

        RSAPrivateKey privateKey = new RSAPrivateKey();
        RSAPublicKey publicKey = new RSAPublicKey();

        setKeyAttributes(id, label, P11Constants.CKK_RSA, publicKey, privateKey);

        publicKey.getModulusBits().setLongValue((long) keySize);
        publicKey.getPublicExponent().setByteArrayValue(tmpPublicExponent.toByteArray());

        try {
            session.generateKeyPair(Mechanism.get(mech), publicKey, privateKey);
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        }
    } // method generateRSAKeyPair

    // CHECKSTYLE:SKIP
    private void generateECKeyPair(
            final Session session,
            final ASN1ObjectIdentifier curveId,
            final X9ECParameters ecParams,
            final byte[] id,
            final String label)
    throws SecurityException, P11TokenException {
        long mech = P11Constants.CKM_EC_KEY_PAIR_GEN;
        assertMechanismSupported(mech);

        ECDSAPrivateKey privateKey = new ECDSAPrivateKey();
        ECDSAPublicKey publicKey = new ECDSAPublicKey();
        setKeyAttributes(id, label, P11Constants.CKK_EC, publicKey, privateKey);

        byte[] encodedCurveId;
        try {
            encodedCurveId = curveId.getEncoded();
        } catch (IOException ex) {
            throw new SecurityException(ex.getMessage(), ex);
        }
        try {
            publicKey.getEcdsaParams().setByteArrayValue(encodedCurveId);
            session.generateKeyPair(Mechanism.get(mech), publicKey, privateKey);
        } catch (TokenException ex) {
            try {
                publicKey.getEcdsaParams().setByteArrayValue(ecParams.getEncoded());
            } catch (IOException ex2) {
                throw new SecurityException(ex.getMessage(), ex);
            }
            try {
                session.generateKeyPair(Mechanism.get(mech), publicKey, privateKey);
            } catch (TokenException ex2) {
                throw new P11TokenException("could not generate EC keypair", ex2);
            }
        }
    }

    @Override
    public List<? extends P11Identity> getP11Identities() {
        return Collections.unmodifiableList(identities);
    }

    @Override
    public X509Certificate exportCert(
            final P11KeyIdentifier keyIdentifier)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonNull("keyIdentifier", keyIdentifier);
        PrivateKey privKey = getPrivateObject(null, null, keyIdentifier);
        if (privKey == null) {
            return null;
        }

        X509PublicKeyCertificate cert =
                getCertificateObject(privKey.getId().getByteArrayValue(), null);
        try {
            return X509Util.parseCert(cert.getValue().getByteArrayValue());
        } catch (CertificateException | IOException ex) {
            throw new SecurityException(ex.getMessage(), ex);
        }
    }

    @Override
    public String getModuleName() {
        return moduleName;
    }

    @Override
    public P11SlotIdentifier getSlotIdentifier() {
        return slotId;
    }

    private static boolean checkSessionLoggedIn(
            final Session session)
    throws P11TokenException {
        SessionInfo info;
        try {
            info = session.getSessionInfo();
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        }
        if (LOG.isTraceEnabled()) {
            LOG.debug("SessionInfo: {}", info);
        }

        State state = info.getState();
        long deviceError = info.getDeviceError();

        LOG.debug("to be verified PKCS11Module: state = {}, deviceError: {}", state, deviceError);

        boolean isRwSessionLoggedIn = state.equals(State.RW_USER_FUNCTIONS);
        boolean isRoSessionLoggedIn = state.equals(State.RO_USER_FUNCTIONS);

        boolean sessionSessionLoggedIn = ((isRoSessionLoggedIn || isRwSessionLoggedIn)
                && deviceError == 0);
        LOG.debug("sessionSessionLoggedIn: {}", sessionSessionLoggedIn);
        return sessionSessionLoggedIn;
    }

    private static List<iaik.pkcs.pkcs11.objects.Object> getObjects(
            final Session session,
            final iaik.pkcs.pkcs11.objects.Object template)
    throws P11TokenException {
        return getObjects(session, template, 9999);
    }

    private static List<iaik.pkcs.pkcs11.objects.Object> getObjects(
            final Session session,
            final iaik.pkcs.pkcs11.objects.Object template,
            final int maxNo)
    throws P11TokenException {
        List<iaik.pkcs.pkcs11.objects.Object> objList = new LinkedList<>();

        try {
            session.findObjectsInit(template);

            while (objList.size() < maxNo) {
                iaik.pkcs.pkcs11.objects.Object[] foundObjects = session.findObjects(1);
                if (foundObjects == null || foundObjects.length == 0) {
                    break;
                }

                for (iaik.pkcs.pkcs11.objects.Object object : foundObjects) {
                    if (LOG.isTraceEnabled()) {
                        LOG.debug("foundObject: {}", object);
                    }
                    objList.add(object);
                }
            }
        } catch (TokenException ex) {
            throw new P11TokenException(ex.getMessage(), ex);
        } finally {
            try {
                session.findObjectsFinal();
            } catch (Exception ex) { // CHECKSTYLE:SKIP
            }
        }

        return objList;
    } // method getObjects

    private static String getDescription(
            final byte[] keyId,
            final char[] keyLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("id ");
        if (keyId == null) {
            sb.append("null");
        } else {
            sb.append(Hex.toHexString(keyId));
        }

        sb.append(" and label ");
        if (keyLabel == null) {
            sb.append("null");
        } else {
            sb.append(new String(keyLabel));
        }
        return sb.toString();
    }

    private static X509PublicKeyCertificate createPkcs11Template(
            final X509Certificate cert,
            final byte[] encodedCert,
            final byte[] keyId,
            final char[] label)
    throws SecurityException, P11TokenException {
        if (label == null || label.length == 0) {
            throw new IllegalArgumentException("label must not be null or empty");
        }

        byte[] tmpEncodedCert = encodedCert;
        if (tmpEncodedCert == null) {
            try {
                tmpEncodedCert = cert.getEncoded();
            } catch (CertificateEncodingException ex) {
                throw new SecurityException(ex.getMessage(), ex);
            }
        }

        X509PublicKeyCertificate newCertTemp = new X509PublicKeyCertificate();
        newCertTemp.getId().setByteArrayValue(keyId);
        newCertTemp.getLabel().setCharArrayValue(label);
        newCertTemp.getToken().setBooleanValue(true);
        newCertTemp.getCertificateType().setLongValue(
                CertificateType.X_509_PUBLIC_KEY);

        newCertTemp.getSubject().setByteArrayValue(
                cert.getSubjectX500Principal().getEncoded());
        newCertTemp.getIssuer().setByteArrayValue(
                cert.getIssuerX500Principal().getEncoded());
        newCertTemp.getSerialNumber().setByteArrayValue(
                cert.getSerialNumber().toByteArray());
        newCertTemp.getValue().setByteArrayValue(tmpEncodedCert);
        return newCertTemp;
    }

    private static void setKeyAttributes(
            final byte[] id,
            final String label,
            final long keyType,
            final PublicKey publicKey,
            final PrivateKey privateKey) {
        if (privateKey != null) {
            privateKey.getId().setByteArrayValue(id);
            privateKey.getToken().setBooleanValue(true);
            privateKey.getLabel().setCharArrayValue(label.toCharArray());
            privateKey.getKeyType().setLongValue(keyType);
            privateKey.getSign().setBooleanValue(true);
            privateKey.getPrivate().setBooleanValue(true);
            privateKey.getSensitive().setBooleanValue(true);
        }

        if (publicKey != null) {
            publicKey.getId().setByteArrayValue(id);
            publicKey.getToken().setBooleanValue(true);
            publicKey.getLabel().setCharArrayValue(label.toCharArray());
            publicKey.getKeyType().setLongValue(keyType);
            publicKey.getVerify().setBooleanValue(true);
            publicKey.getModifiable().setBooleanValue(Boolean.TRUE);
        }
    }

    private static ASN1ObjectIdentifier getCurveId(
            final String curveNameOrOid) {
        try {
            return new ASN1ObjectIdentifier(curveNameOrOid);
        } catch (Exception ex) { // CHECKSTYLE:SKIP
        }

        ASN1ObjectIdentifier curveId = X962NamedCurves.getOID(curveNameOrOid);

        if (curveId == null) {
            curveId = SECNamedCurves.getOID(curveNameOrOid);
        }

        if (curveId == null) {
            curveId = TeleTrusTNamedCurves.getOID(curveNameOrOid);
        }

        if (curveId == null) {
            curveId = NISTNamedCurves.getOID(curveNameOrOid);
        }

        return curveId;
    }

    private static String hex(
            final byte[] bytes) {
        return Hex.toHexString(bytes).toUpperCase();
    }

    private static java.security.PublicKey generatePublicKey(
            final PublicKey p11Key)
    throws SecurityException {
        if (p11Key instanceof RSAPublicKey) {
            RSAPublicKey rsaP11Key = (RSAPublicKey) p11Key;
            byte[] expBytes = rsaP11Key.getPublicExponent().getByteArrayValue();
            BigInteger exp = new BigInteger(1, expBytes);

            byte[] modBytes = rsaP11Key.getModulus().getByteArrayValue();
            BigInteger mod = new BigInteger(1, modBytes);

            if (LOG.isDebugEnabled()) {
                LOG.debug("modulus:\n {}", Hex.toHexString(modBytes));
            }
            RSAPublicKeySpec keySpec = new RSAPublicKeySpec(mod, exp);
            try {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                return keyFactory.generatePublic(keySpec);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
                throw new SecurityException(ex.getMessage(), ex);
            }
        } else if (p11Key instanceof DSAPublicKey) {
            DSAPublicKey dsaP11Key = (DSAPublicKey) p11Key;

            BigInteger prime = new BigInteger(1, dsaP11Key.getPrime().getByteArrayValue()); // p
            BigInteger subPrime = new BigInteger(1,
                    dsaP11Key.getSubprime().getByteArrayValue()); // q
            BigInteger base = new BigInteger(1, dsaP11Key.getBase().getByteArrayValue()); // g
            BigInteger value = new BigInteger(1, dsaP11Key.getValue().getByteArrayValue()); // y

            DSAPublicKeySpec keySpec = new DSAPublicKeySpec(value, prime, subPrime, base);
            try {
                KeyFactory keyFactory = KeyFactory.getInstance("DSA");
                return keyFactory.generatePublic(keySpec);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
                throw new SecurityException(ex.getMessage(), ex);
            }
        } else if (p11Key instanceof ECDSAPublicKey) {
            ECDSAPublicKey ecP11Key = (ECDSAPublicKey) p11Key;
            byte[] encodedAlgorithmIdParameters = ecP11Key.getEcdsaParams().getByteArrayValue();
            byte[] encodedPoint = ecP11Key.getEcPoint().getByteArrayValue();
            try {
                return KeyUtil.createECPublicKey(encodedAlgorithmIdParameters, encodedPoint);
            } catch (InvalidKeySpecException ex) {
                throw new SecurityException(ex.getMessage(), ex);
            }
        } else {
            throw new SecurityException("unknown publicKey class " + p11Key.getClass().getName());
        }
    } // method generatePublicKey

    @Override
    public void showDetails(
            final OutputStream stream,
            final boolean verbose)
    throws IOException, SecurityException, P11TokenException {
        ParamUtil.requireNonNull("stream", stream);
        List<PrivateKey> allPrivateObjects = getAllPrivateObjects(null, null);
        int size = allPrivateObjects.size();

        List<ComparableIaikPrivateKey> privateKeys = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            PrivateKey key = allPrivateObjects.get(i);
            byte[] id = key.getId().getByteArrayValue();
            if (id != null) {
                char[] label = key.getLabel().getCharArrayValue();
                ComparableIaikPrivateKey privKey = new ComparableIaikPrivateKey(id, label);
                privateKeys.add(privKey);
            }
        }

        Collections.sort(privateKeys);
        size = privateKeys.size();

        List<X509PublicKeyCertificate> allCertObjects = getAllCertificateObjects();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            ComparableIaikPrivateKey privKey = privateKeys.get(i);
            byte[] keyId = privKey.getKeyId();
            char[] keyLabel = privKey.getKeyLabel();

            PublicKey pubKey = getPublicKeyObject(null, null, keyId, keyLabel);
            sb.append("\t")
                .append(i + 1)
                .append(". ")
                .append(privKey.getKeyLabelAsText())
                .append(" (").append("id: ")
                .append(Hex.toHexString(privKey.getKeyId()).toUpperCase())
                .append(")\n");

            sb.append("\t\tAlgorithm: ")
                .append(getKeyAlgorithm(pubKey))
                .append("\n");

            X509PublicKeyCertificate cert = removeCertificateObject(allCertObjects, keyId,
                    keyLabel);
            if (cert == null) {
                sb.append("\t\tCertificate: NONE\n");
            } else {
                formatString(verbose, sb, cert);
            }
        }

        for (int i = 0; i < allCertObjects.size(); i++) {
            X509PublicKeyCertificate certObj = allCertObjects.get(i);
            sb.append("\tCert-")
                .append(i + 1)
                .append(". ")
                .append(certObj.getLabel().getCharArrayValue())
                .append(" (").append("id: ")
                .append(Hex.toHexString(certObj.getId().getByteArrayValue()).toUpperCase())
                .append(")\n");

            formatString(verbose, sb, certObj);
        }

        if (sb.length() > 0) {
            stream.write(sb.toString().getBytes());
        }
    }

    private static String getKeyAlgorithm(
            final PublicKey key) {
        if (key instanceof RSAPublicKey) {
            return "RSA";
        } else if (key instanceof ECDSAPublicKey) {
            byte[] paramBytes = ((ECDSAPublicKey) key).getEcdsaParams().getByteArrayValue();
            if (paramBytes.length < 50) {
                try {
                    ASN1ObjectIdentifier curveId =
                            (ASN1ObjectIdentifier) ASN1ObjectIdentifier.fromByteArray(paramBytes);
                    String curveName = KeyUtil.getCurveName(curveId);
                    return "EC (named curve " + curveName + ")";
                } catch (Exception ex) {
                    return "EC";
                }
            } else {
                return "EC (specified curve)";
            }
        } else if (key instanceof DSAPublicKey) {
            return "DSA";
        } else {
            return "UNKNOWN";
        }
    }

    private static X509PublicKeyCertificate removeCertificateObject(
            final List<X509PublicKeyCertificate> certificateObjects,
            final byte[] keyId,
            final char[] keyLabel) {
        X509PublicKeyCertificate cert = null;
        for (X509PublicKeyCertificate certObj : certificateObjects) {
            if (keyId != null
                    && !Arrays.equals(keyId, certObj.getId().getByteArrayValue())) {
                continue;
            }

            if (keyLabel != null
                    && !Arrays.equals(keyLabel, certObj.getLabel().getCharArrayValue())) {
                continue;
            }

            cert = certObj;
            break;
        }

        if (cert != null) {
            certificateObjects.remove(cert);
        }

        return cert;
    }

    private void formatString(
            final boolean verbose,
            final StringBuilder sb,
            final X509PublicKeyCertificate cert) {
        byte[] bytes = cert.getSubject().getByteArrayValue();
        String subject;
        try {
            X500Principal x500Prin = new X500Principal(bytes);
            subject = X509Util.getRfc4519Name(x500Prin);
        } catch (Exception ex) {
            subject = new String(bytes);
        }

        if (!verbose) {
            sb.append("\t\tCertificate: ").append(subject).append("\n");
            return;
        }

        sb.append("\t\tCertificate:\n");
        sb.append("\t\t\tSubject: ")
            .append(subject)
            .append("\n");

        bytes = cert.getIssuer().getByteArrayValue();
        String issuer;
        try {
            X500Principal x500Prin = new X500Principal(bytes);
            issuer = X509Util.getRfc4519Name(x500Prin);
        } catch (Exception ex) {
            issuer = new String(bytes);
        }
        sb.append("\t\t\tIssuer: ")
            .append(issuer)
            .append("\n");

        byte[] certBytes = cert.getValue().getByteArrayValue();

        X509Certificate x509Cert = null;
        try {
            x509Cert = X509Util.parseCert(certBytes);
        } catch (Exception ex) {
            sb.append("\t\t\tError: " + ex.getMessage());
            return;
        }

        sb.append("\t\t\tSerial: ")
            .append(x509Cert.getSerialNumber())
            .append("\n");
        sb.append("\t\t\tStart time: ")
            .append(x509Cert.getNotBefore())
            .append("\n");
        sb.append("\t\t\tEnd time: ")
            .append(x509Cert.getNotAfter())
            .append("\n");
        sb.append("\t\t\tSHA1 Sum: ")
            .append(HashCalculator.hexSha1(certBytes))
            .append("\n");
    }

    private static String toString(
            final CharArrayAttribute charArrayAttr) {
        String labelStr = null;
        if (charArrayAttr != null) {
            char[] chars = charArrayAttr.getCharArrayValue();
            if (chars != null) {
                labelStr = new String(chars);
            }
        }
        return labelStr;
    }

}
