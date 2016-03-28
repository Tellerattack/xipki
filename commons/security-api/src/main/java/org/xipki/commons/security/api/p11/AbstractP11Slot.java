/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2013 - 2016 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
 *
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

package org.xipki.commons.security.api.p11;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.DSAParametersGenerator;
import org.bouncycastle.crypto.params.DSAParameterGenerationParameters;
import org.bouncycastle.crypto.params.DSAParameters;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.commons.common.util.ParamUtil;
import org.xipki.commons.security.api.HashCalculator;
import org.xipki.commons.security.api.SecurityException;
import org.xipki.commons.security.api.X509Cert;
import org.xipki.commons.security.api.p11.parameters.P11Params;
import org.xipki.commons.security.api.util.KeyUtil;
import org.xipki.commons.security.api.util.X509Util;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

public abstract class AbstractP11Slot implements P11Slot {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractP11Slot.class);

    protected final String moduleName;

    protected final P11SlotIdentifier slotId;

    private final boolean readOnly;

    private final SecureRandom random = new SecureRandom();

    private final CopyOnWriteArrayList<P11ObjectIdentifier> identityIds =
            new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<P11ObjectIdentifier, P11Identity> identities =
            new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<P11ObjectIdentifier> certIds =
            new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<P11ObjectIdentifier, X509Cert> certificates =
            new ConcurrentHashMap<>();

    private final Set<Long> mechanisms = Collections.emptySet();

    private final P11MechanismFilter mechanismFilter;

    protected AbstractP11Slot(
            final String moduleName,
            final P11SlotIdentifier slotId,
            final boolean readOnly,
            final P11MechanismFilter mechanismFilter)
    throws P11TokenException {
        this.mechanismFilter = ParamUtil.requireNonNull("mechanismFilter", mechanismFilter);
        this.moduleName = ParamUtil.requireNonBlank("moduleName", moduleName);
        this.slotId = ParamUtil.requireNonNull("slotId", slotId);
        this.readOnly = readOnly;
    }

    protected static String hex(
            @Nonnull final byte[] bytes) {
        return Hex.toHexString(bytes).toUpperCase();
    }

    protected static String getDescription(
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

    protected X509Cert getCertForId(
            @Nonnull final byte[] id) {
        for (P11ObjectIdentifier objId : certIds) {
            if (objId.matchesId(id)) {
                return certificates.get(objId);
            }
        }
        return null;
    }

    private void updateCaCertsOfIdentities(
            @Nullable final X509Certificate modifiedOldCert) {
        for (P11ObjectIdentifier objId : identityIds) {
            P11Identity identity = identities.get(objId);
            updateCaCertsOfIdentity(identity, modifiedOldCert);
        }
    }

    private void updateCaCertsOfIdentity(
            @Nonnull final P11Identity identity,
            @Nullable final X509Certificate modifiedOldCert) {
        X509Certificate[] certchain = identity.getCertificateChain();
        if (certchain == null || certchain.length == 0) {
            return;
        }

        boolean related = true;
        if (modifiedOldCert != null) {
            related = false;
            for (X509Certificate c : certchain) {
                if (modifiedOldCert.equals(c)) {
                    related = true;
                    break;
                }
            }
        }

        if (!related) {
            return;
        }

        X509Certificate[] newCertchain = buildCertPath(certchain[0]);
        if (!Arrays.equals(certchain, newCertchain)) {
            try {
                identity.setCertificates(newCertchain);
            } catch (P11TokenException ex) {
                LOG.warn("could not set certificates for identity {}", identity.getIdentityId());
            }
        }
    }

    private X509Certificate[] buildCertPath(
            @Nonnull final X509Certificate cert) {
        List<X509Certificate> certs = new LinkedList<>();
        X509Certificate cur = cert;
        while (cur != null) {
            certs.add(cur);
            cur = getIssuerForCert(cur);
        }
        return certs.toArray(new X509Certificate[0]);
    }

    private X509Certificate getIssuerForCert(
            @Nonnull final X509Certificate cert) {
        try {
            if (X509Util.isSelfSigned(cert)) {
                return null;
            }

            for (P11ObjectIdentifier objId : certIds) {
                X509Cert cert2 = certificates.get(objId);
                if (X509Util.issues(cert2.getCert(), cert)) {
                    return cert2.getCert();
                }
            }
        } catch (CertificateEncodingException ex) {
            LOG.warn("invalid encoding of certificate {}", ex.getMessage());
        }
        return null;
    }

    @Override
    public void refresh()
    throws P11TokenException {
        P11SlotRefreshResult res = doRefresh(mechanismFilter); // CHECKSTYLE:SKIP

        mechanisms.clear();
        certIds.clear();
        certificates.clear();
        identityIds.clear();
        identities.clear();

        mechanisms.addAll(res.getMechanisms());
        
        certificates.putAll(res.getCertificates());
        List<P11ObjectIdentifier> objIds = new ArrayList<>(res.getCertificates().keySet());
        Collections.sort(objIds);
        certIds.addAll(objIds);

        identities.putAll(res.getIdentities());
        objIds = new ArrayList<>(res.getIdentities().keySet());
        Collections.sort(objIds);
        identityIds.addAll(objIds);

        if (LOG.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("initialized module ").append(moduleName).append(", slot ").append(slotId);

            sb.append("\nsupported mechanisms: ").append(mechanisms);

            sb.append(this.certIds.size()).append(" certificates:\n");
            for (P11ObjectIdentifier objectId : certIds) {
                X509Cert entity = this.certificates.get(objectId);
                sb.append("\t(").append(objectId);
                sb.append(", subject=").append(entity.getSubject()).append(")\n");
            }

            sb.append(this.identityIds.size()).append(" identities:\n");
            for (P11ObjectIdentifier objectId : identityIds) {
                P11Identity identity = this.identities.get(objectId);
                sb.append("\t(").append(objectId);
                sb.append(", algo=").append(identity.getPublicKey().getAlgorithm()).append(")\n");
            }

            LOG.info(sb.toString());
        }
    }

    protected abstract P11SlotRefreshResult doRefresh(
            P11MechanismFilter mechanismFilter)
    throws P11TokenException;

    protected void addIdentity(
            final P11Identity identity)
    throws P11DuplicateEntityException {
        if (!slotId.equals(identity.getIdentityId().getSlotId())) {
            throw new IllegalArgumentException("invalid identity");
        }

        P11ObjectIdentifier objectId = identity.getIdentityId().getObjectId();
        if (hasIdentity(objectId)) {
            throw new P11DuplicateEntityException(slotId, objectId);
        }

        List<P11ObjectIdentifier> ids = new ArrayList<>(identityIds);
        ids.add(objectId);
        Collections.sort(ids);
        identityIds.clear();
        identityIds.addAll(ids);

        updateCaCertsOfIdentity(identity, null);
        identities.put(objectId, identity);
    }

    protected void deleteIdentity(
            final P11ObjectIdentifier objectId)
    throws P11DuplicateEntityException {
        if (hasIdentity(objectId)) {
            LOG.warn("could not find object " + objectId);
            return;
        }
        identityIds.remove(objectId);
        identities.remove(objectId);
        LOG.info("deleted entity " + objectId);
    }

    @Override
    public boolean hasIdentity(
            final P11ObjectIdentifier objectId) {
        return identityIds.contains(objectId);
    }

    @Override
    public Set<Long> getMechanisms() {
        return Collections.unmodifiableSet(mechanisms);
    }

    @Override
    public boolean supportsMechanism(
            final long mechanism) {
        return mechanisms.contains(mechanism);
    }

    @Override
    public void assertMechanismSupported(
            final long mechanism)
    throws P11UnsupportedMechanismException {
        if (!mechanisms.contains(mechanism)) {
            throw new P11UnsupportedMechanismException(mechanism, slotId);
        }
    }

    @Override
    public List<P11ObjectIdentifier> getIdentityIdentifiers() {
        return Collections.unmodifiableList(identityIds);
    }

    @Override
    public List<P11ObjectIdentifier> getCertIdentifiers() {
        return Collections.unmodifiableList(certIds);
    }

    @Override
    public String getModuleName() {
        return moduleName;
    }

    @Override
    public P11SlotIdentifier getSlotId() {
        return slotId;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public P11Identity getIdentity(
            final P11ObjectIdentifier objectId)
    throws P11UnknownEntityException {
        P11Identity ident = identities.get(objectId);
        if (ident == null) {
            throw new P11UnknownEntityException(slotId, objectId);
        }
        return ident;
    }

    @Override
    public P11ObjectIdentifier getObjectIdForId(
            final byte[] id) {
        for (P11ObjectIdentifier objectId : identityIds) {
            if (objectId.matchesId(id)) {
                return objectId;
            }
        }

        for (P11ObjectIdentifier objectId : certIds) {
            if (objectId.matchesId(id)) {
                return objectId;
            }
        }

        return null;
    }

    @Override
    public P11ObjectIdentifier getObjectIdForLabel(
            final String label) {
        for (P11ObjectIdentifier objectId : identityIds) {
            if (objectId.getLabel().equals(label)) {
                return objectId;
            }
        }

        for (P11ObjectIdentifier objectId : certIds) {
            if (objectId.getLabel().equals(label)) {
                return objectId;
            }
        }

        return null;
    }

    @Override
    public X509Certificate exportCert(
            final P11ObjectIdentifier objectId)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonNull("objectId", objectId);
        try {
            return getIdentity(objectId).getCertificate();
        } catch (P11UnknownEntityException ex) {
            // CHECKSTYLE:SKIP
        }

        X509Cert cert = certificates.get(objectId);
        if (cert == null) {
            throw new P11UnknownEntityException(slotId, objectId);
        }
        return cert.getCert();
    }

    @Override
    public void removeCerts(
            final P11ObjectIdentifier objectId)
    throws P11TokenException {
        ParamUtil.requireNonNull("objectId", objectId);
        assertWritable("removeCerts");

        X509Certificate cert;
        if (identityIds.contains(objectId)) {
            cert = identities.get(objectId).getCertificate();
            certIds.remove(objectId);
            certificates.remove(objectId);
            identityIds.remove(objectId);
            identities.get(objectId).setCertificates(null);
        } else if (certIds.contains(objectId)) {
            cert = certificates.get(objectId).getCert();
            certIds.remove(objectId);
            certificates.remove(objectId);
        } else {
            throw new P11UnknownEntityException(slotId, objectId);
        }

        certificateRemoved(cert);
        doRemoveCerts(objectId);
    }

    @Override
    public void removeIdentity(
            P11ObjectIdentifier objectId)
    throws P11TokenException {
        ParamUtil.requireNonNull("objectId", objectId);
        assertWritable("removeIdentity");

        X509Certificate cert;
        if (identityIds.contains(objectId)) {
            cert = identities.get(objectId).getCertificate();
            certIds.remove(objectId);
            certificates.remove(objectId);
            identityIds.remove(objectId);
            identities.get(objectId).setCertificates(null);
        } else {
            throw new P11UnknownEntityException(slotId, objectId);
        }

        if (cert != null) {
            certificateRemoved(cert);
        }
        doRemoveIdentity(objectId);
    }

    protected abstract void doRemoveIdentity(
            P11ObjectIdentifier objectId)
    throws P11TokenException;

    private void certificateRemoved(
            final X509Certificate cert) {
        updateCaCertsOfIdentities(cert);
    }

    protected abstract void doRemoveCerts(
            final P11ObjectIdentifier objectId)
    throws P11TokenException;

    @Override
    public P11ObjectIdentifier addCert(
            final X509Certificate cert)
    throws P11TokenException, SecurityException {
        ParamUtil.requireNonNull("cert", cert);
        assertWritable("addCert");

        X509Cert x509Cert = new X509Cert(cert);
        byte[] encodedCert = x509Cert.getEncodedCert();
        for (P11ObjectIdentifier objectId : certIds) {
            X509Cert tmpCert = certificates.get(objectId);
            if (Arrays.equals(encodedCert, tmpCert.getEncodedCert())) {
                return objectId;
            }
        }

        byte[] id = generateId();
        String cn = X509Util.getCommonName(cert.getSubjectX500Principal());
        String label = generateLabel(cn);
        P11ObjectIdentifier objectId = new P11ObjectIdentifier(id, label);
        doAddCert(objectId, x509Cert.getCert());
        certAdded(objectId, cert);
        return objectId;
    }

    private void certAdded(
            final P11ObjectIdentifier objectId,
            final X509Certificate cert) {
        updateCaCertsOfIdentities(null);
    }

    protected abstract void doAddCert(
            @Nonnull final P11ObjectIdentifier objectId,
            @Nonnull final X509Certificate cert)
    throws P11TokenException, SecurityException;

    protected byte[] generateId()
    throws P11TokenException {
        byte[] id = new byte[8];

        while (true) {
            random.nextBytes(id);
            boolean duplicated = false;
            for (P11ObjectIdentifier objectId : identityIds) {
                if (objectId.matchesId(id)) {
                    duplicated = true;
                    break;
                }
            }

            if (!duplicated) {
                for (P11ObjectIdentifier objectId : certIds) {
                    if (objectId.matchesId(id)) {
                        duplicated = true;
                        break;
                    }
                }
            }

            if (!duplicated) {
                return id;
            }
        }
    }

    protected String generateLabel(
            final String label)
    throws P11TokenException {

        String tmpLabel = label;
        int idx = 0;
        while (true) {
            boolean duplicated = false;
            for (P11ObjectIdentifier objectId : identityIds) {
                if (objectId.getLabel().equals(label)) {
                    duplicated = true;
                    break;
                }
            }

            if (!duplicated) {
                for (P11ObjectIdentifier objectId : certIds) {
                    if (objectId.getLabel().equals(label)) {
                        duplicated = true;
                        break;
                    }
                }
            }

            if (!duplicated) {
                return tmpLabel;
            }

            idx++;
            tmpLabel = label + "-" + idx;
        }
    }

    @Override
    public byte[] sign(
            final long mechanism,
            final P11Params parameters,
            final byte[] content,
            final P11ObjectIdentifier objectId)
    throws P11TokenException, SecurityException {
        return getIdentity(objectId).sign(mechanism, parameters, content);
    }

    @Override
    public P11ObjectIdentifier generateRSAKeypair(
            final int keysize,
            final BigInteger publicExponent,
            final String label)
    throws P11TokenException {
        ParamUtil.requireNonBlank("label", label);
        ParamUtil.requireMin("keysize", keysize, 1024);
        if (keysize % 1024 != 0) {
            throw new IllegalArgumentException("key size is not multiple of 1024: " + keysize);
        }
        assertWritable("generateRSAKeypair");
        assertMechanismSupported(P11Constants.CKM_RSA_PKCS_KEY_PAIR_GEN);

        BigInteger tmpPublicExponent = publicExponent;
        if (tmpPublicExponent == null) {
            tmpPublicExponent = BigInteger.valueOf(65537);
        }

        P11Identity identity = doGenerateRSAKeypair(keysize, tmpPublicExponent, label);
        addIdentity(identity);
        return identity.getIdentityId().getObjectId();
    }

    // CHECKSTYLE:SKIP
    protected abstract P11Identity doGenerateRSAKeypair(
            int keysize,
            @Nonnull BigInteger publicExponent,
            @Nonnull String label)
    throws P11TokenException;

    @Override
    public P11ObjectIdentifier generateDSAKeypair(
            final int plength,
            final int qlength,
            final String label)
    throws P11TokenException {
        ParamUtil.requireMin("pLength", plength, 1024);
        if (plength % 1024 != 0) {
            throw new IllegalArgumentException("key size is not multiple of 1024: " + plength);
        }
        assertWritable("generateDSAKeypair");
        assertMechanismSupported(P11Constants.CKM_DSA_KEY_PAIR_GEN);

        DSAParametersGenerator paramGen = new DSAParametersGenerator(new SHA512Digest());
        DSAParameterGenerationParameters genParams = new DSAParameterGenerationParameters(
                plength, qlength, 80, new SecureRandom());
        paramGen.init(genParams);
        DSAParameters dsaParams = paramGen.generateParameters();

        P11Identity identity = doGenerateDSAKeypair(dsaParams.getP(), dsaParams.getQ(),
                dsaParams.getG(), label);
        addIdentity(identity);
        return identity.getIdentityId().getObjectId();
    }

    @Override
    public P11ObjectIdentifier generateDSAKeypair(
            final BigInteger p, // CHECKSTYLE:SKIP
            final BigInteger q, // CHECKSTYLE:SKIP
            final BigInteger g, // CHECKSTYLE:SKIP
            final String label)
    throws P11TokenException {
        ParamUtil.requireNonBlank("label", label);
        ParamUtil.requireNonNull("p", p);
        ParamUtil.requireNonNull("q", q);
        ParamUtil.requireNonNull("g", g);
        assertWritable("generateDSAKeypair");
        assertMechanismSupported(P11Constants.CKM_DSA_KEY_PAIR_GEN);

        P11Identity identity = doGenerateDSAKeypair(p, q, g, label);
        addIdentity(identity);
        return identity.getIdentityId().getObjectId();
    }

    // CHECKSTYLE:OFF
    protected abstract P11Identity doGenerateDSAKeypair(
            final BigInteger p,
            final BigInteger q,
            final BigInteger g,
            @Nonnull final String label)
    throws P11TokenException;
    // CHECKSTYLE:ON

    @Override
    public P11ObjectIdentifier generateECKeypair(
            final String curveNameOrOid,
            final String label)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonBlank("curveNameOrOid", curveNameOrOid);
        ParamUtil.requireNonBlank("label", label);
        assertWritable("generateECKeypair");
        assertMechanismSupported(P11Constants.CKM_EC_KEY_PAIR_GEN);

        ASN1ObjectIdentifier curveId = KeyUtil.getCurveOidForCurveNameOrOid(curveNameOrOid);
        if (curveId == null) {
            throw new IllegalArgumentException("unknown curve " + curveNameOrOid);
        }
        P11Identity identity = doGenerateECKeypair(curveId, label);
        addIdentity(identity);
        return identity.getIdentityId().getObjectId();
    }

    // CHECKSTYLE:SKIP
    protected abstract P11Identity doGenerateECKeypair(
            @Nonnull ASN1ObjectIdentifier curveId,
            @Nonnull String label)
    throws P11TokenException;

    @Override
    public void updateCertificate(
            final P11ObjectIdentifier objectId,
            final X509Certificate newCert)
    throws SecurityException, P11TokenException {
        ParamUtil.requireNonNull("objectId", objectId);
        ParamUtil.requireNonNull("newCert", newCert);
        assertWritable("updateCertificate");

        P11Identity entity = identities.get(objectId);
        if (entity == null) {
            throw new P11UnknownEntityException("could not find private key " + objectId);
        }

        java.security.PublicKey pk = entity.getPublicKey();
        java.security.PublicKey newPk = newCert.getPublicKey();
        if (!pk.equals(newPk)) {
            throw new SecurityException("the given certificate is not for the key " + objectId);
        }

        doUpdateCertificate(objectId, newCert);
        X509Certificate oldCert = entity.getCertificate();
        entity.setCertificates(null);
        if (oldCert != null) {
            certificateRemoved(oldCert);
        }
        certAdded(objectId, newCert);
    }

    protected abstract void doUpdateCertificate(
            final P11ObjectIdentifier objectId,
            final X509Certificate newCert)
    throws SecurityException, P11TokenException;

    @Override
    public void showDetails(
            final OutputStream stream,
            final boolean verbose)
    throws IOException, SecurityException, P11TokenException {
        ParamUtil.requireNonNull("stream", stream);

        List<P11ObjectIdentifier> sortedObjectIds = new ArrayList<>(identityIds);
        Collections.sort(sortedObjectIds);
        int size = sortedObjectIds.size();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            P11ObjectIdentifier objectId = sortedObjectIds.get(i);
            sb.append("\t").append(i + 1).append(". ").append(objectId.getLabel());
            sb.append(" (").append("id: ").append(objectId.getIdHex()).append(")\n");
            String algo = identities.get(objectId).getPublicKey().getAlgorithm();
            sb.append("\t\tAlgorithm: ").append(algo).append("\n");
            X509Certificate cert = identities.get(objectId).getCertificate();
            if (cert == null) {
                sb.append("\t\tCertificate: NONE\n");
            } else {
                formatString(verbose, sb, cert);
            }
        }

        sortedObjectIds.clear();
        for (P11ObjectIdentifier objectId : certIds) {
            if (!identityIds.contains(objectId)) {
                sortedObjectIds.add(objectId);
            }
        }

        if (!sortedObjectIds.isEmpty()) {
            Collections.sort(sortedObjectIds);
            size = sortedObjectIds.size();
            for (int i = 0; i < size; i++) {
                P11ObjectIdentifier objectId = sortedObjectIds.get(i);
                sb.append("\tCert-").append(i + 1).append(". ").append(objectId.getLabel());
                sb.append(" (").append("id: ").append(objectId.getLabel()).append(")\n");
                formatString(verbose, sb, certificates.get(objectId).getCert());
            }
        }

        if (sb.length() > 0) {
            stream.write(sb.toString().getBytes());
        }
    }

    protected void assertWritable(
            final String operationName)
    throws P11PermissionException {
        if (readOnly) {
            throw new P11PermissionException("Operation " + operationName + " is not permitted");
        }
    }

    private static void formatString(
            final boolean verbose,
            final StringBuilder sb,
            final X509Certificate cert) {
        String subject = X509Util.getRfc4519Name(cert.getSubjectX500Principal());
        if (!verbose) {
            sb.append("\t\tCertificate: ").append(subject).append("\n");
            return;
        }

        sb.append("\t\tCertificate:\n");
        sb.append("\t\t\tSubject: ").append(subject).append("\n");

        String issuer = X509Util.getRfc4519Name(cert.getIssuerX500Principal());
        sb.append("\t\t\tIssuer: ").append(issuer).append("\n");
        sb.append("\t\t\tSerial: ").append(cert.getSerialNumber()).append("\n");
        sb.append("\t\t\tStart time: ").append(cert.getNotBefore()).append("\n");
        sb.append("\t\t\tEnd time: ").append(cert.getNotAfter()).append("\n");
        sb.append("\t\t\tSHA1 Sum: ");
        try {
            sb.append(HashCalculator.hexSha1(cert.getEncoded()));
        } catch (CertificateEncodingException ex) {
            sb.append("ERROR");
        }
        sb.append("\n");
    }

}