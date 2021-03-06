/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.security.pkcs11.emulator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.crypto.params.DSAParameters;
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util;
import org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.bc.BcContentSignerBuilder;
import org.bouncycastle.operator.bc.BcDSAContentSignerBuilder;
import org.bouncycastle.operator.bc.BcECContentSignerBuilder;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.security.HashAlgo;
import org.xipki.security.X509Cert;
import org.xipki.security.pkcs11.P11Identity;
import org.xipki.security.pkcs11.P11IdentityId;
import org.xipki.security.pkcs11.P11ModuleConf.P11MechanismFilter;
import org.xipki.security.pkcs11.P11ModuleConf.P11NewObjectConf;
import org.xipki.security.pkcs11.P11ObjectIdentifier;
import org.xipki.security.pkcs11.P11Slot;
import org.xipki.security.pkcs11.P11SlotIdentifier;
import org.xipki.security.pkcs11.P11TokenException;
import org.xipki.security.pkcs11.P11UnknownEntityException;
import org.xipki.security.pkcs11.emulator.EmulatorP11Module.Vendor;
import org.xipki.security.util.KeyUtil;
import org.xipki.security.util.X509Util;
import org.xipki.util.Args;
import org.xipki.util.IoUtil;
import org.xipki.util.LogUtil;
import org.xipki.util.StringUtil;

import iaik.pkcs.pkcs11.constants.Functions;
import iaik.pkcs.pkcs11.constants.PKCS11Constants;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

class EmulatorP11Slot extends P11Slot {

  private static class InfoFilenameFilter implements FilenameFilter {

    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(INFO_FILE_SUFFIX);
    }

  }

  private static final Logger LOG = LoggerFactory.getLogger(EmulatorP11Slot.class);

  // slotinfo
  private static final String FILE_SLOTINFO = "slot.info";
  private static final String PROP_NAMED_CURVE_SUPPORTED = "namedCurveSupported";

  private static final String DIR_PRIV_KEY = "privkey";
  private static final String DIR_PUB_KEY = "pubkey";
  private static final String DIR_SEC_KEY = "seckey";
  private static final String DIR_CERT = "cert";

  private static final String INFO_FILE_SUFFIX = ".info";
  private static final String VALUE_FILE_SUFFIX = ".value";

  private static final String PROP_ID = "id";
  private static final String PROP_LABEL = "label";
  private static final String PROP_SHA1SUM = "sha1";

  private static final String PROP_ALGORITHM = "algorithm";

  // RSA
  private static final String PROP_RSA_MODUS = "modus";
  private static final String PROP_RSA_PUBLIC_EXPONENT = "publicExponent";

  // DSA
  private static final String PROP_DSA_PRIME = "prime"; // p
  private static final String PROP_DSA_SUBPRIME = "subprime"; // q
  private static final String PROP_DSA_BASE = "base"; // g
  private static final String PROP_DSA_VALUE = "value"; // y

  // EC
  private static final String PROP_EC_ECDSA_PARAMS = "ecdsaParams";
  private static final String PROP_EC_EC_POINT = "ecPoint";

  private static final long[] supportedMechs = new long[]{
    PKCS11Constants.CKM_DSA_KEY_PAIR_GEN,
    PKCS11Constants.CKM_RSA_PKCS_KEY_PAIR_GEN,
    PKCS11Constants.CKM_EC_KEY_PAIR_GEN,
    PKCS11Constants.CKM_GENERIC_SECRET_KEY_GEN,

    // Digest
    PKCS11Constants.CKM_SHA_1,
    PKCS11Constants.CKM_SHA224,
    PKCS11Constants.CKM_SHA256,
    PKCS11Constants.CKM_SHA384,
    PKCS11Constants.CKM_SHA512,
    PKCS11Constants.CKM_SHA3_224,
    PKCS11Constants.CKM_SHA3_256,
    PKCS11Constants.CKM_SHA3_384,
    PKCS11Constants.CKM_SHA3_512,

    // HMAC
    PKCS11Constants.CKM_SHA_1_HMAC,
    PKCS11Constants.CKM_SHA224_HMAC,
    PKCS11Constants.CKM_SHA256_HMAC,
    PKCS11Constants.CKM_SHA384_HMAC,
    PKCS11Constants.CKM_SHA512_HMAC,
    PKCS11Constants.CKM_SHA3_224_HMAC,
    PKCS11Constants.CKM_SHA3_256_HMAC,
    PKCS11Constants.CKM_SHA3_384_HMAC,
    PKCS11Constants.CKM_SHA3_512_HMAC,

    PKCS11Constants.CKM_RSA_X_509,

    PKCS11Constants.CKM_RSA_PKCS,
    PKCS11Constants.CKM_SHA1_RSA_PKCS,
    PKCS11Constants.CKM_SHA224_RSA_PKCS,
    PKCS11Constants.CKM_SHA256_RSA_PKCS,
    PKCS11Constants.CKM_SHA384_RSA_PKCS,
    PKCS11Constants.CKM_SHA512_RSA_PKCS,
    PKCS11Constants.CKM_SHA3_224_RSA_PKCS,
    PKCS11Constants.CKM_SHA3_256_RSA_PKCS,
    PKCS11Constants.CKM_SHA3_384_RSA_PKCS,
    PKCS11Constants.CKM_SHA3_512_RSA_PKCS,

    PKCS11Constants.CKM_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA1_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA224_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA256_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA384_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA512_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA3_224_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA3_256_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA3_384_RSA_PKCS_PSS,
    PKCS11Constants.CKM_SHA3_512_RSA_PKCS_PSS,

    PKCS11Constants.CKM_DSA,
    PKCS11Constants.CKM_DSA_SHA1,
    PKCS11Constants.CKM_DSA_SHA224,
    PKCS11Constants.CKM_DSA_SHA256,
    PKCS11Constants.CKM_DSA_SHA384,
    PKCS11Constants.CKM_DSA_SHA512,
    PKCS11Constants.CKM_DSA_SHA3_224,
    PKCS11Constants.CKM_DSA_SHA3_256,
    PKCS11Constants.CKM_DSA_SHA3_384,
    PKCS11Constants.CKM_DSA_SHA3_512,

    PKCS11Constants.CKM_ECDSA,
    PKCS11Constants.CKM_ECDSA_SHA1,
    PKCS11Constants.CKM_ECDSA_SHA224,
    PKCS11Constants.CKM_ECDSA_SHA256,
    PKCS11Constants.CKM_ECDSA_SHA384,
    PKCS11Constants.CKM_ECDSA_SHA512,
    PKCS11Constants.CKM_ECDSA_SHA3_224,
    PKCS11Constants.CKM_ECDSA_SHA3_256,
    PKCS11Constants.CKM_ECDSA_SHA3_384,
    PKCS11Constants.CKM_ECDSA_SHA3_512,

    // SM2
    PKCS11Constants.CKM_VENDOR_SM2_KEY_PAIR_GEN,
    PKCS11Constants.CKM_VENDOR_SM2_SM3,
    PKCS11Constants.CKM_VENDOR_SM2};

  private static final FilenameFilter INFO_FILENAME_FILTER = new InfoFilenameFilter();

  private final boolean namedCurveSupported;

  private final File slotDir;

  private final File privKeyDir;

  private final File pubKeyDir;

  private final File secKeyDir;

  private final File certDir;

  private final char[] password;

  private final PrivateKeyCryptor privateKeyCryptor;

  private final SecureRandom random = new SecureRandom();

  private final int maxSessions;

  private final Vendor vendor;

  private final P11NewObjectConf newObjectConf;

  EmulatorP11Slot(String moduleName, File slotDir, P11SlotIdentifier slotId, boolean readOnly,
      char[] password, PrivateKeyCryptor privateKeyCryptor, P11MechanismFilter mechanismFilter,
      P11NewObjectConf newObjectConf, int maxSessions, Vendor vendor) throws P11TokenException {
    super(moduleName, slotId, readOnly, mechanismFilter);

    this.newObjectConf = Args.notNull(newObjectConf, "newObjectConf");
    this.slotDir = Args.notNull(slotDir, "slotDir");
    this.password = Args.notNull(password, "password");
    this.privateKeyCryptor = Args.notNull(privateKeyCryptor, "privateKeyCryptor");
    this.maxSessions = Args.positive(maxSessions, "maxSessions");
    this.vendor = (vendor == null) ? Vendor.GENERAL : vendor;

    this.privKeyDir = new File(slotDir, DIR_PRIV_KEY);
    if (!this.privKeyDir.exists()) {
      this.privKeyDir.mkdirs();
    }

    this.pubKeyDir = new File(slotDir, DIR_PUB_KEY);
    if (!this.pubKeyDir.exists()) {
      this.pubKeyDir.mkdirs();
    }

    this.secKeyDir = new File(slotDir, DIR_SEC_KEY);
    if (!this.secKeyDir.exists()) {
      this.secKeyDir.mkdirs();
    }

    this.certDir = new File(slotDir, DIR_CERT);
    if (!this.certDir.exists()) {
      this.certDir.mkdirs();
    }

    File slotInfoFile = new File(slotDir, FILE_SLOTINFO);
    if (slotInfoFile.exists()) {
      Properties props = loadProperties(slotInfoFile);
      this.namedCurveSupported = Boolean.parseBoolean(
          props.getProperty(PROP_NAMED_CURVE_SUPPORTED, "true"));
    } else {
      this.namedCurveSupported = true;
    }

    refresh();
  }

  @Override
  protected P11SlotRefreshResult refresh0() throws P11TokenException {
    P11SlotRefreshResult ret = new P11SlotRefreshResult();
    for (long mech : supportedMechs) {
      ret.addMechanism(mech);
    }

    // Secret Keys
    File[] secKeyInfoFiles = secKeyDir.listFiles(INFO_FILENAME_FILTER);

    if (secKeyInfoFiles != null && secKeyInfoFiles.length != 0) {
      for (File secKeyInfoFile : secKeyInfoFiles) {
        byte[] id = getKeyIdFromInfoFilename(secKeyInfoFile.getName());
        String hexId = hex(id);

        try {
          Properties props = loadProperties(secKeyInfoFile);
          String label = props.getProperty(PROP_LABEL);

          P11ObjectIdentifier p11ObjId = new P11ObjectIdentifier(id, label);
          byte[] encodedValue = IoUtil.read(new File(secKeyDir, hexId + VALUE_FILE_SUFFIX));

          KeyStore ks = KeyStore.getInstance("JCEKS");
          ks.load(new ByteArrayInputStream(encodedValue), password);
          SecretKey key = null;
          Enumeration<String> aliases = ks.aliases();
          while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.isKeyEntry(alias)) {
              key = (SecretKey) ks.getKey(alias, password);
              break;
            }
          }

          EmulatorP11Identity identity = new EmulatorP11Identity(this,
              new P11IdentityId(slotId, p11ObjId, null, null), key, maxSessions, random);
          LOG.info("added PKCS#11 secret key {}", p11ObjId);
          ret.addIdentity(identity);
        } catch (ClassCastException ex) {
          LogUtil.warn(LOG, ex,"InvalidKeyException while initializing key with key-id " + hexId);
          continue;
        } catch (Throwable th) {
          LOG.error("unexpected exception while initializing key with key-id " + hexId, th);
          continue;
        }
      }
    }

    // Certificates
    File[] certInfoFiles = certDir.listFiles(INFO_FILENAME_FILTER);
    if (certInfoFiles != null) {
      for (File infoFile : certInfoFiles) {
        byte[] id = getKeyIdFromInfoFilename(infoFile.getName());
        Properties props = loadProperties(infoFile);
        String label = props.getProperty(PROP_LABEL);
        P11ObjectIdentifier objId = new P11ObjectIdentifier(id, label);
        try {
          X509Cert cert = readCertificate(id);
          ret.addCertificate(objId, cert);
        } catch (CertificateException | IOException ex) {
          LOG.warn("could not parse certificate " + objId);
        }
      }
    }

    // Private / Public keys
    File[] privKeyInfoFiles = privKeyDir.listFiles(INFO_FILENAME_FILTER);

    if (privKeyInfoFiles != null && privKeyInfoFiles.length != 0) {
      for (File privKeyInfoFile : privKeyInfoFiles) {
        byte[] id = getKeyIdFromInfoFilename(privKeyInfoFile.getName());
        String hexId = hex(id);

        try {
          Properties props = loadProperties(privKeyInfoFile);
          String label = props.getProperty(PROP_LABEL);

          P11ObjectIdentifier p11ObjId = new P11ObjectIdentifier(id, label);
          X509Cert cert = ret.getCertForId(id);
          java.security.PublicKey publicKey = (cert == null) ? readPublicKey(id)
              : cert.getCert().getPublicKey();

          if (publicKey == null) {
            LOG.warn("Neither public key nor certificate is associated with private key {}",
                p11ObjId);
            continue;
          }

          byte[] encodedValue = IoUtil.read(new File(privKeyDir, hexId + VALUE_FILE_SUFFIX));

          PKCS8EncryptedPrivateKeyInfo epki = new PKCS8EncryptedPrivateKeyInfo(encodedValue);
          PrivateKey privateKey = privateKeyCryptor.decrypt(epki);

          X509Certificate[] certs = (cert == null) ? null : new X509Certificate[]{cert.getCert()};

          EmulatorP11Identity identity = new EmulatorP11Identity(this,
              new P11IdentityId(slotId, p11ObjId, label, label), privateKey, publicKey, certs,
                  maxSessions, random);
          LOG.info("added PKCS#11 key {}", p11ObjId);
          ret.addIdentity(identity);
        } catch (InvalidKeyException ex) {
          LogUtil.warn(LOG, ex,"InvalidKeyException while initializing key with key-id " + hexId);
          continue;
        } catch (Throwable th) {
          LOG.error("unexpected exception while initializing key with key-id " + hexId, th);
          continue;
        }
      }
    }

    return ret;
  } // method refresh

  File slotDir() {
    return slotDir;
  }

  private PublicKey readPublicKey(byte[] keyId) throws P11TokenException {
    String hexKeyId = hex(keyId);
    File pubKeyFile = new File(pubKeyDir, hexKeyId + INFO_FILE_SUFFIX);
    Properties props = loadProperties(pubKeyFile);

    String algorithm = props.getProperty(PROP_ALGORITHM);
    if (PKCSObjectIdentifiers.rsaEncryption.getId().equals(algorithm)) {
      BigInteger exp = new BigInteger(1, decodeHex(props.getProperty(PROP_RSA_PUBLIC_EXPONENT)));
      BigInteger mod = new BigInteger(1, decodeHex(props.getProperty(PROP_RSA_MODUS)));

      RSAPublicKeySpec keySpec = new RSAPublicKeySpec(mod, exp);
      try {
        return KeyUtil.generateRSAPublicKey(keySpec);
      } catch (InvalidKeySpecException ex) {
        throw new P11TokenException(ex.getMessage(), ex);
      }
    } else if (X9ObjectIdentifiers.id_dsa.getId().equals(algorithm)) {
      BigInteger prime = new BigInteger(1, decodeHex(props.getProperty(PROP_DSA_PRIME))); // p
      BigInteger subPrime = new BigInteger(1, decodeHex(props.getProperty(PROP_DSA_SUBPRIME))); // q
      BigInteger base = new BigInteger(1, decodeHex(props.getProperty(PROP_DSA_BASE))); // g
      BigInteger value = new BigInteger(1, decodeHex(props.getProperty(PROP_DSA_VALUE))); // y

      DSAPublicKeySpec keySpec = new DSAPublicKeySpec(value, prime, subPrime, base);
      try {
        return KeyUtil.generateDSAPublicKey(keySpec);
      } catch (InvalidKeySpecException ex) {
        throw new P11TokenException(ex.getMessage(), ex);
      }
    } else if (X9ObjectIdentifiers.id_ecPublicKey.getId().equals(algorithm)) {
      byte[] ecdsaParams = decodeHex(props.getProperty(PROP_EC_ECDSA_PARAMS));
      byte[] asn1EncodedPoint = decodeHex(props.getProperty(PROP_EC_EC_POINT));
      byte[] ecPoint = DEROctetString.getInstance(asn1EncodedPoint).getOctets();
      try {
        return KeyUtil.createECPublicKey(ecdsaParams, ecPoint);
      } catch (InvalidKeySpecException ex) {
        throw new P11TokenException(ex.getMessage(), ex);
      }
    } else {
      throw new P11TokenException("unknown key algorithm " + algorithm);
    }
  }

  private X509Cert readCertificate(byte[] keyId) throws CertificateException, IOException {
    byte[] encoded = IoUtil.read(new File(certDir, hex(keyId) + VALUE_FILE_SUFFIX));
    X509Certificate cert = X509Util.parseCert(encoded);
    return new X509Cert(cert, encoded);
  }

  private Properties loadProperties(File file) throws P11TokenException {
    try {
      try (InputStream stream = Files.newInputStream(file.toPath())) {
        Properties props = new Properties();
        props.load(stream);
        return props;
      }
    } catch (IOException ex) {
      throw new P11TokenException("could not load properties from the file " + file.getPath(), ex);
    }
  }

  private static byte[] getKeyIdFromInfoFilename(String fileName) {
    return decodeHex(fileName.substring(0, fileName.length() - INFO_FILE_SUFFIX.length()));
  }

  @Override
  public void close() {
    LOG.info("close slot " + slotId);
  }

  private boolean removePkcs11Cert(P11ObjectIdentifier objectId) throws P11TokenException {
    return removePkcs11Entry(certDir, objectId);
  }

  private boolean removePkcs11Entry(File dir, P11ObjectIdentifier objectId)
      throws P11TokenException {
    byte[] id = objectId.getId();
    String label = objectId.getLabel();
    if (id != null) {
      String hextId = hex(id);
      File infoFile = new File(dir, hextId + INFO_FILE_SUFFIX);
      if (!infoFile.exists()) {
        return false;
      }

      if (StringUtil.isBlank(label)) {
        return deletePkcs11Entry(dir, id);
      } else {
        Properties props = loadProperties(infoFile);

        return label.equals(props.getProperty("label")) ? deletePkcs11Entry(dir, id) : false;
      }
    }

    // id is null, delete all entries with the specified label
    boolean deleted = false;
    File[] infoFiles = dir.listFiles(INFO_FILENAME_FILTER);
    if (infoFiles != null) {
      for (File infoFile : infoFiles) {
        if (!infoFile.isFile()) {
          continue;
        }

        Properties props = loadProperties(infoFile);
        if (label.equals(props.getProperty("label"))) {
          if (deletePkcs11Entry(dir, getKeyIdFromInfoFilename(infoFile.getName()))) {
            deleted = true;
          }
        }
      }
    }

    return deleted;
  }

  private static boolean deletePkcs11Entry(File dir, byte[] objectId) {
    String hextId = hex(objectId);
    File infoFile = new File(dir, hextId + INFO_FILE_SUFFIX);
    boolean b1 = true;
    if (infoFile.exists()) {
      b1 = infoFile.delete();
    }

    File valueFile = new File(dir, hextId + VALUE_FILE_SUFFIX);
    boolean b2 = true;
    if (valueFile.exists()) {
      b2 = valueFile.delete();
    }

    return b1 || b2;
  }

  private int deletePkcs11Entry(File dir, byte[] id, String label) throws P11TokenException {
    if (StringUtil.isBlank(label)) {
      return deletePkcs11Entry(dir, id) ? 1 : 0;
    }

    if (id != null && id.length > 0) {
      String hextId = hex(id);
      File infoFile = new File(dir, hextId + INFO_FILE_SUFFIX);
      if (!infoFile.exists()) {
        return 0;
      }

      Properties props = loadProperties(infoFile);
      if (!label.equals(props.get(PROP_LABEL))) {
        return 0;
      }

      return deletePkcs11Entry(dir, id) ? 1 : 0;
    }

    File[] infoFiles = dir.listFiles(INFO_FILENAME_FILTER);
    if (infoFiles == null || infoFiles.length == 0) {
      return 0;
    }

    List<byte[]> ids = new LinkedList<>();

    for (File infoFile : infoFiles) {
      Properties props = loadProperties(infoFile);
      if (label.equals(props.getProperty(PROP_LABEL))) {
        ids.add(getKeyIdFromInfoFilename(infoFile.getName()));
      }
    }

    if (ids.isEmpty()) {
      return 0;
    }

    for (byte[] m : ids) {
      deletePkcs11Entry(dir, m);
    }
    return ids.size();
  }

  private String savePkcs11SecretKey(byte[] id, String label, SecretKey secretKey)
      throws P11TokenException {
    assertValidId(id);
    if (vendor == Vendor.YUBIKEY) {
      label = "Secret key " + id[0];
    }

    byte[] encrytedValue;
    try {
      KeyStore ks = KeyStore.getInstance("JCEKS");
      ks.load(null, password);
      ks.setKeyEntry("main", secretKey, password, null);
      ByteArrayOutputStream outStream = new ByteArrayOutputStream();
      ks.store(outStream, password);
      outStream.flush();
      encrytedValue = outStream.toByteArray();
    } catch (NoSuchAlgorithmException | KeyStoreException | CertificateException | IOException ex) {
      throw new P11TokenException(ex.getClass().getName() + ": " + ex.getMessage(), ex);
    }

    savePkcs11Entry(secKeyDir, id, label, encrytedValue);

    return label;
  }

  private String savePkcs11PrivateKey(byte[] id, String label, PrivateKey privateKey)
      throws P11TokenException {
    assertValidId(id);
    if (vendor == Vendor.YUBIKEY) {
      label = "Private Key " + id[0];
    }

    PKCS8EncryptedPrivateKeyInfo encryptedPrivKeyInfo = privateKeyCryptor.encrypt(privateKey);
    byte[] encoded;
    try {
      encoded = encryptedPrivKeyInfo.getEncoded();
    } catch (IOException ex) {
      LogUtil.error(LOG, ex);
      throw new P11TokenException("could not encode PrivateKey");
    }

    savePkcs11Entry(privKeyDir, id, label, encoded);
    return label;
  }

  private String savePkcs11PublicKey(byte[] id, String label, PublicKey publicKey)
      throws P11TokenException {
    String hexId = hex(id);
    if (vendor == Vendor.YUBIKEY) {
      label = "Public Key " + id[0];
    }

    StringBuilder sb = new StringBuilder(100);
    sb.append(PROP_ID).append('=').append(hexId).append('\n');
    sb.append(PROP_LABEL).append('=').append(label).append('\n');

    if (publicKey instanceof RSAPublicKey) {
      sb.append(PROP_ALGORITHM).append('=')
        .append(PKCSObjectIdentifiers.rsaEncryption.getId()).append('\n');

      RSAPublicKey rsaKey = (RSAPublicKey) publicKey;
      sb.append(PROP_RSA_MODUS).append('=')
        .append(hex(rsaKey.getModulus().toByteArray())).append('\n');

      sb.append(PROP_RSA_PUBLIC_EXPONENT).append('=')
        .append(hex(rsaKey.getPublicExponent().toByteArray())).append('\n');
    } else if (publicKey instanceof DSAPublicKey) {
      sb.append(PROP_ALGORITHM).append('=')
        .append(X9ObjectIdentifiers.id_dsa.getId()).append('\n');

      DSAPublicKey dsaKey = (DSAPublicKey) publicKey;
      sb.append(PROP_DSA_PRIME).append('=')
        .append(hex(dsaKey.getParams().getP().toByteArray())).append('\n');

      sb.append(PROP_DSA_SUBPRIME).append('=')
        .append(hex(dsaKey.getParams().getQ().toByteArray())).append('\n');

      sb.append(PROP_DSA_BASE).append('=')
        .append(hex(dsaKey.getParams().getG().toByteArray())).append('\n');

      sb.append(PROP_DSA_VALUE).append('=')
        .append(hex(dsaKey.getY().toByteArray())).append('\n');
    } else if (publicKey instanceof ECPublicKey) {
      sb.append(PROP_ALGORITHM).append('=')
        .append(X9ObjectIdentifiers.id_ecPublicKey.getId()).append('\n');

      ECPublicKey ecKey = (ECPublicKey) publicKey;
      ECParameterSpec paramSpec = ecKey.getParams();

      // ecdsaParams
      org.bouncycastle.jce.spec.ECParameterSpec bcParamSpec = EC5Util.convertSpec(paramSpec, false);
      ASN1ObjectIdentifier curveOid = ECUtil.getNamedCurveOid(bcParamSpec);
      if (curveOid == null) {
        throw new P11TokenException("EC public key is not of namedCurve");
      }

      byte[] encodedParams;
      try {
        if (namedCurveSupported) {
          encodedParams = curveOid.getEncoded();
        } else {
          encodedParams = ECNamedCurveTable.getByOID(curveOid).getEncoded();
        }
      } catch (IOException | NullPointerException ex) {
        throw new P11TokenException(ex.getMessage(), ex);
      }

      sb.append(PROP_EC_ECDSA_PARAMS).append('=').append(hex(encodedParams)).append('\n');

      // EC point
      java.security.spec.ECPoint pointW = ecKey.getW();
      int keysize = (paramSpec.getOrder().bitLength() + 7) / 8;
      byte[] ecPoint = new byte[1 + keysize * 2];
      ecPoint[0] = 4; // uncompressed
      bigIntToBytes("Wx", pointW.getAffineX(), ecPoint, 1, keysize);
      bigIntToBytes("Wy", pointW.getAffineY(), ecPoint, 1 + keysize, keysize);

      byte[] encodedEcPoint;
      try {
        encodedEcPoint = new DEROctetString(ecPoint).getEncoded();
      } catch (IOException ex) {
        throw new P11TokenException("could not ASN.1 encode the ECPoint");
      }
      sb.append(PROP_EC_EC_POINT).append('=').append(hex(encodedEcPoint)).append('\n');
    } else {
      throw new IllegalArgumentException(
          "unsupported public key " + publicKey.getClass().getName());
    }

    try {
      IoUtil.save(new File(pubKeyDir, hexId + INFO_FILE_SUFFIX), sb.toString().getBytes());
    } catch (IOException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }

    return label;
  }

  private static void bigIntToBytes(String numName, BigInteger num, byte[] dest, int destPos,
      int length) throws P11TokenException {
    if (num.signum() != 1) {
      throw new P11TokenException(numName + " is not positive");
    }
    byte[] bytes = num.toByteArray();
    if (bytes.length == length) {
      System.arraycopy(bytes, 0, dest, destPos, length);
    } else if (bytes.length < length) {
      System.arraycopy(bytes, 0, dest, destPos + length - bytes.length, bytes.length);
    } else {
      System.arraycopy(bytes, bytes.length - length, dest, destPos, length);
    }
  }

  private void savePkcs11Cert(byte[] id, String label, X509Certificate cert)
      throws P11TokenException, CertificateException {
    savePkcs11Entry(certDir, id, label, cert.getEncoded());
  }

  private void savePkcs11Entry(File dir, byte[] id, String label, byte[] value)
      throws P11TokenException {
    Args.notNull(dir, "dir");
    Args.notNull(id, "id");
    Args.notBlank(label, "label");
    Args.notNull(value, "value");

    assertValidId(id);
    String hexId = hex(id);

    String str = StringUtil.concat(PROP_ID, "=", hexId, "\n", PROP_LABEL, "=", label, "\n",
        PROP_SHA1SUM, "=", HashAlgo.SHA1.hexHash(value), "\n");

    try {
      IoUtil.save(new File(dir, hexId + INFO_FILE_SUFFIX), str.getBytes());
      IoUtil.save(new File(dir, hexId + VALUE_FILE_SUFFIX), value);
    } catch (IOException ex) {
      throw new P11TokenException("could not save certificate");
    }
  }

  @Override
  public int removeObjects(byte[] id, String label) throws P11TokenException {
    if ((id == null || id.length == 0) && StringUtil.isBlank(label)) {
      throw new IllegalArgumentException("at least one of id and label may not be null");
    }

    int num = deletePkcs11Entry(privKeyDir, id, label);
    num += deletePkcs11Entry(pubKeyDir, id, label);
    num += deletePkcs11Entry(certDir, id, label);
    num += deletePkcs11Entry(secKeyDir, id, label);
    return num;
  }

  @Override
  protected void removeIdentity0(P11IdentityId identityId) throws P11TokenException {
    P11ObjectIdentifier keyId = identityId.getKeyId();

    boolean b1 = true;
    if (identityId.getCertId() != null) {
      removePkcs11Entry(certDir, identityId.getCertId());
    }

    boolean b2 = removePkcs11Entry(privKeyDir, keyId);

    boolean b3 = true;
    if (identityId.getPublicKeyId() != null) {
      b3 = removePkcs11Entry(pubKeyDir, identityId.getPublicKeyId());
    }

    boolean b4 = removePkcs11Entry(secKeyDir, keyId);
    if (! (b1 || b2 || b3 || b4)) {
      throw new P11UnknownEntityException(slotId, keyId);
    }
  }

  @Override
  protected void removeCerts0(P11ObjectIdentifier objectId) throws P11TokenException {
    deletePkcs11Entry(certDir, objectId.getId());
  }

  @Override
  protected P11ObjectIdentifier addCert0(X509Certificate cert, P11NewObjectControl control)
      throws P11TokenException, CertificateException {
    byte[] id = control.getId();
    if (id == null) {
      id = generateId();
    }

    String label = control.getLabel();

    savePkcs11Cert(id, label, cert);
    return new P11ObjectIdentifier(id, label);
  }

  @Override
  protected P11Identity generateSecretKey0(long keyType, int keysize,
      P11NewKeyControl control) throws P11TokenException {
    if (keysize % 8 != 0) {
      throw new IllegalArgumentException("keysize is not multiple of 8: " + keysize);
    }

    long mech;
    if (PKCS11Constants.CKK_AES == keyType) {
      mech = PKCS11Constants.CKM_AES_KEY_GEN;
    } else if (PKCS11Constants.CKK_DES3 == keyType) {
      mech = PKCS11Constants.CKM_DES3_KEY_GEN;
    } else if (PKCS11Constants.CKK_GENERIC_SECRET == keyType) {
      mech = PKCS11Constants.CKM_GENERIC_SECRET_KEY_GEN;
    } else if (PKCS11Constants.CKK_SHA_1_HMAC == keyType
        || PKCS11Constants.CKK_SHA224_HMAC == keyType || PKCS11Constants.CKK_SHA256_HMAC == keyType
        || PKCS11Constants.CKK_SHA384_HMAC == keyType || PKCS11Constants.CKK_SHA512_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_224_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_256_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_384_HMAC == keyType
        || PKCS11Constants.CKK_SHA3_512_HMAC == keyType) {
      mech = PKCS11Constants.CKM_GENERIC_SECRET_KEY_GEN;
    } else {
      throw new IllegalArgumentException(
          "unsupported key type 0x" + Functions.toFullHex((int)keyType));
    }
    assertMechanismSupported(mech);

    byte[] keyBytes = new byte[keysize / 8];
    random.nextBytes(keyBytes);
    SecretKey key = new SecretKeySpec(keyBytes, getSecretKeyAlgorithm(keyType));
    return saveP11Entity(key, control);
  }

  @Override
  protected P11Identity importSecretKey0(long keyType, byte[] keyValue,
      P11NewKeyControl control) throws P11TokenException {
    SecretKey key = new SecretKeySpec(keyValue, getSecretKeyAlgorithm(keyType));
    return saveP11Entity(key, control);
  }

  private static String getSecretKeyAlgorithm(long keyType) {
    String algorithm;
    if (PKCS11Constants.CKK_GENERIC_SECRET == keyType) {
      algorithm = "generic";
    } else if (PKCS11Constants.CKK_AES == keyType) {
      algorithm = "AES";
    } else if (PKCS11Constants.CKK_SHA_1_HMAC == keyType) {
      algorithm = "HMACSHA1";
    } else if (PKCS11Constants.CKK_SHA224_HMAC == keyType) {
      algorithm = "HMACSHA224";
    } else if (PKCS11Constants.CKK_SHA256_HMAC == keyType) {
      algorithm = "HMACSHA256";
    } else if (PKCS11Constants.CKK_SHA384_HMAC == keyType) {
      algorithm = "HMACSHA384";
    } else if (PKCS11Constants.CKK_SHA512_HMAC == keyType) {
      algorithm = "HMACSHA512";
    } else if (PKCS11Constants.CKK_SHA3_224_HMAC == keyType) {
      algorithm = "HMACSHA3-224";
    } else if (PKCS11Constants.CKK_SHA3_256_HMAC == keyType) {
      algorithm = "HMACSHA3-256";
    } else if (PKCS11Constants.CKK_SHA3_384_HMAC == keyType) {
      algorithm = "HMACSHA3-384";
    } else if (PKCS11Constants.CKK_SHA3_512_HMAC == keyType) {
      algorithm = "HMACSHA3-512";
    } else {
      throw new IllegalArgumentException("unsupported keyType " + keyType);
    }
    return algorithm;
  }

  @Override
  protected P11Identity generateRSAKeypair0(int keysize, BigInteger publicExponent,
      P11NewKeyControl control) throws P11TokenException {
    assertMechanismSupported(PKCS11Constants.CKM_RSA_PKCS_KEY_PAIR_GEN);

    KeyPair keypair;
    try {
      keypair = KeyUtil.generateRSAKeypair(keysize, publicExponent, random);
    } catch (NoSuchAlgorithmException | NoSuchProviderException
        | InvalidAlgorithmParameterException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
    return saveP11Entity(keypair, control);
  }

  @Override
  // CHECKSTYLE:SKIP
  protected P11Identity generateDSAKeypair0(BigInteger p, BigInteger q, BigInteger g,
      P11NewKeyControl control) throws P11TokenException {
    assertMechanismSupported(PKCS11Constants.CKM_DSA_KEY_PAIR_GEN);
    DSAParameters dsaParams = new DSAParameters(p, q, g);
    KeyPair keypair;
    try {
      keypair = KeyUtil.generateDSAKeypair(dsaParams, random);
    } catch (NoSuchAlgorithmException | NoSuchProviderException
        | InvalidAlgorithmParameterException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
    return saveP11Entity(keypair, control);
  }

  @Override
  protected P11Identity generateSM2Keypair0(P11NewKeyControl control)
      throws P11TokenException {
    assertMechanismSupported(PKCS11Constants.CKM_VENDOR_SM2_KEY_PAIR_GEN);
    return generateECKeypair0(GMObjectIdentifiers.sm2p256v1, control);
  }

  @Override
  protected P11Identity generateECKeypair0(ASN1ObjectIdentifier curveId,
      P11NewKeyControl control) throws P11TokenException {
    assertMechanismSupported(PKCS11Constants.CKM_EC_KEY_PAIR_GEN);

    KeyPair keypair;
    try {
      keypair = KeyUtil.generateECKeypairForCurveNameOrOid(curveId.getId(), random);
    } catch (NoSuchAlgorithmException | NoSuchProviderException
        | InvalidAlgorithmParameterException ex) {
      throw new P11TokenException(ex.getMessage(), ex);
    }
    return saveP11Entity(keypair, control);
  }

  private P11Identity saveP11Entity(KeyPair keypair, P11NewObjectControl control)
      throws P11TokenException {
    byte[] id = control.getId();
    if (id == null) {
      id = generateId();
    }

    assertValidId(id);

    String label = control.getLabel();

    String keyLabel = savePkcs11PrivateKey(id, label, keypair.getPrivate());
    String pubKeyLabel = savePkcs11PublicKey(id, label, keypair.getPublic());
    String certLabel = null;
    X509Certificate[] certs = null;
    if (vendor == Vendor.YUBIKEY) {
      try {
        // Yubico generates always a self-signed certificate during keypair generation
        SubjectPublicKeyInfo spki = KeyUtil.createSubjectPublicKeyInfo(keypair.getPublic());
        Date now = new Date();
        Date notBefore = new Date(now.getTime()); // 10 minutes past
        Date notAfter = new Date(notBefore.getTime() + 3650L * 24 * 3600 * 1000); // 10 years

        certLabel = "Certificate " + id[0];
        X500Name subjectDn = new X500Name("CN=" + certLabel);
        ContentSigner contentSigner = getContentSigner(keypair.getPrivate());

        // Generate keystore
        X509v3CertificateBuilder certGenerator = new X509v3CertificateBuilder(subjectDn,
            BigInteger.ONE, notBefore, notAfter, subjectDn, spki);
        X509CertificateHolder bcCert = certGenerator.build(contentSigner);
        byte[] encodedCert = bcCert.getEncoded();
        X509Certificate cert = X509Util.parseCert(encodedCert);
        savePkcs11Entry(certDir, id, label, encodedCert);

        certs = new X509Certificate[] {cert};
      } catch (Exception ex) {
        throw new P11TokenException("cannot generate self-signed certificate", ex);
      }
    }

    P11IdentityId identityId = new P11IdentityId(slotId,
        new P11ObjectIdentifier(id, keyLabel), pubKeyLabel, certLabel);
    try {
      return new EmulatorP11Identity(this,identityId, keypair.getPrivate(),
          keypair.getPublic(), certs, maxSessions, random);
    } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchProviderException ex) {
      throw new P11TokenException(
          "could not construct KeyStoreP11Identity: " + ex.getMessage(), ex);
    }
  }

  private P11Identity saveP11Entity(SecretKey key, P11NewObjectControl control)
      throws P11TokenException {
    byte[] id = control.getId();
    if (id == null) {
      id = generateId();
    }
    assertValidId(id);

    String label = control.getLabel();

    savePkcs11SecretKey(id, label, key);
    P11IdentityId identityId = new P11IdentityId(slotId,
        new P11ObjectIdentifier(id, label), null, null);
    return new EmulatorP11Identity(this,identityId, key, maxSessions, random);
  }

  @Override
  protected void updateCertificate0(P11ObjectIdentifier keyId, X509Certificate newCert)
      throws P11TokenException, CertificateException {
    removePkcs11Cert(keyId);
    savePkcs11Cert(keyId.getId(), keyId.getLabel(), newCert);
  }

  private byte[] generateId() throws P11TokenException {
    while (true) {
      byte[] id = new byte[newObjectConf.getIdLength()];
      random.nextBytes(id);

      boolean duplicated = existsIdentityForId(id) || existsCertForId(id);
      if (!duplicated) {
        return id;
      }
    }
  }

  private void assertValidId(byte[] id) throws P11TokenException {
    if (vendor == Vendor.YUBIKEY) {
      if (!(id.length == 1 && (id[0] >= 0 && id[0] <= 23))) {
        throw buildP11TokenException(PKCS11Constants.CKR_ATTRIBUTE_VALUE_INVALID);
      }
    }
  }

  private static P11TokenException buildP11TokenException(long p11ErrorCode) {
    return new P11TokenException(new PKCS11Exception(p11ErrorCode));
  }

  private static ContentSigner getContentSigner(PrivateKey key) throws Exception {
    BcContentSignerBuilder builder;

    if (key instanceof RSAPrivateKey) {
      ASN1ObjectIdentifier hashOid = X509ObjectIdentifiers.id_SHA1;
      ASN1ObjectIdentifier sigOid = PKCSObjectIdentifiers.sha1WithRSAEncryption;

      builder = new BcRSAContentSignerBuilder(buildAlgId(sigOid), buildAlgId(hashOid));
    } else if (key instanceof DSAPrivateKey) {
      ASN1ObjectIdentifier hashOid = X509ObjectIdentifiers.id_SHA1;
      AlgorithmIdentifier sigId = new AlgorithmIdentifier(
          X9ObjectIdentifiers.id_dsa_with_sha1);

      builder = new BcDSAContentSignerBuilder(sigId, buildAlgId(hashOid));
    } else if (key instanceof ECPrivateKey) {
      HashAlgo hashAlgo;
      ASN1ObjectIdentifier sigOid;

      int keysize = ((ECPrivateKey) key).getParams().getOrder().bitLength();
      if (keysize > 384) {
        hashAlgo = HashAlgo.SHA512;
        sigOid = X9ObjectIdentifiers.ecdsa_with_SHA512;
      } else if (keysize > 256) {
        hashAlgo = HashAlgo.SHA384;
        sigOid = X9ObjectIdentifiers.ecdsa_with_SHA384;
      } else if (keysize > 224) {
        hashAlgo = HashAlgo.SHA224;
        sigOid = X9ObjectIdentifiers.ecdsa_with_SHA224;
      } else if (keysize > 160) {
        hashAlgo = HashAlgo.SHA256;
        sigOid = X9ObjectIdentifiers.ecdsa_with_SHA256;
      } else {
        hashAlgo = HashAlgo.SHA1;
        sigOid = X9ObjectIdentifiers.ecdsa_with_SHA1;
      }

      builder = new BcECContentSignerBuilder(new AlgorithmIdentifier(sigOid),
          buildAlgId(hashAlgo.getOid()));
    } else {
      throw new IllegalArgumentException("unknown type of key " + key.getClass().getName());
    }

    return builder.build(KeyUtil.generatePrivateKeyParameter(key));
  } // method getContentSigner

  private static AlgorithmIdentifier buildAlgId(ASN1ObjectIdentifier identifier) {
    return new AlgorithmIdentifier(identifier, DERNull.INSTANCE);
  }

}
