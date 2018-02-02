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

package org.xipki.ca.server.impl.ocsp;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.OperationException.ErrorCode;
import org.xipki.ca.api.X509CertWithDbId;
import org.xipki.common.util.Base64;
import org.xipki.common.util.LogUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.security.CertRevocationInfo;
import org.xipki.security.HashAlgoType;
import org.xipki.security.X509Cert;
import org.xipki.security.util.X509Util;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

class OcspStoreQueryExecutor {

    private static final String SQL_ADD_REVOKED_CERT =
            "INSERT INTO CERT (ID,LUPDATE,SN,NBEFORE,NAFTER,REV,IID,PN,RT,RIT,RR)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";

    private static final String SQL_ADD_CERT =
            "INSERT INTO CERT (ID,LUPDATE,SN,NBEFORE,NAFTER,REV,IID,PN) VALUES (?,?,?,?,?,?,?,?)";

    private static final String SQL_ADD_CRAW = "INSERT INTO CRAW (CID,SUBJECT,CERT) VALUES (?,?,?)";

    private static final String SQL_ADD_CHASH =
            "INSERT INTO CHASH (CID,S1,S256,S3_256) VALUES (?,?,?,?)";

    private static final Logger LOG = LoggerFactory.getLogger(OcspStoreQueryExecutor.class);

    private final DataSourceWrapper datasource;

    private final String sqlCertRegistered;

    private final IssuerStore issuerStore;

    private final boolean publishGoodCerts;

    @SuppressWarnings("unused")
    private final int dbSchemaVersion;

    private final int maxX500nameLen;

    OcspStoreQueryExecutor(DataSourceWrapper datasource, boolean publishGoodCerts)
            throws DataAccessException, NoSuchAlgorithmException {
        this.datasource = ParamUtil.requireNonNull("datasource", datasource);
        this.issuerStore = initIssuerStore();
        this.publishGoodCerts = publishGoodCerts;

        this.sqlCertRegistered = datasource.buildSelectFirstSql(1,
                "ID FROM CERT WHERE SN=? AND IID=?");
        final String sql = "SELECT NAME,VALUE2 FROM DBSCHEMA";
        Connection conn = datasource.getConnection();
        if (conn == null) {
            throw new DataAccessException("could not get connection");
        }

        Map<String, String> variables = new HashMap<>();
        Statement stmt = null;
        ResultSet rs = null;

        try {
            stmt = datasource.createStatement(conn);
            if (stmt == null) {
                throw new DataAccessException("could not create statement");
            }

            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String name = rs.getString("NAME");
                String value = rs.getString("VALUE2");
                variables.put(name, value);
            }
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(stmt, rs);
        }

        String str = variables.get("VERSION");
        this.dbSchemaVersion = Integer.parseInt(str);
        str = variables.get("X500NAME_MAXLEN");
        this.maxX500nameLen = Integer.parseInt(str);
    } // constructor

    private IssuerStore initIssuerStore() throws DataAccessException {
        final String sql = "SELECT ID,SUBJECT,S1C,CERT FROM ISSUER";
        PreparedStatement ps = borrowPreparedStatement(sql);
        ResultSet rs = null;

        try {
            rs = ps.executeQuery();
            List<IssuerEntry> caInfos = new LinkedList<>();
            while (rs.next()) {
                int id = rs.getInt("ID");
                String subject = rs.getString("SUBJECT");
                String sha1Fp = rs.getString("S1C");
                String b64Cert = rs.getString("CERT");

                IssuerEntry caInfoEntry = new IssuerEntry(id, subject, sha1Fp, b64Cert);
                caInfos.add(caInfoEntry);
            }

            return new IssuerStore(caInfos);
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(ps, rs);
        }
    } // method initIssuerStore

    void addCert(X509Cert issuer, X509CertWithDbId certificate, String certprofile)
            throws DataAccessException, OperationException {
        addCert(issuer, certificate, certprofile, null);
    }

    void addCert(X509Cert issuer, X509CertWithDbId certificate, String certprofile,
            CertRevocationInfo revInfo) throws DataAccessException, OperationException {
        addOrUpdateCert(issuer, certificate, certprofile, revInfo);
    }

    private void addOrUpdateCert(X509Cert issuer, X509CertWithDbId certificate, String certprofile,
            CertRevocationInfo revInfo) throws DataAccessException, OperationException {
        ParamUtil.requireNonNull("issuer", issuer);

        boolean revoked = (revInfo != null);
        int issuerId = getIssuerId(issuer);

        BigInteger serialNumber = certificate.cert().getSerialNumber();
        Long certRegisteredId = getCertId(issuerId, serialNumber);

        if (!publishGoodCerts && !revoked && certRegisteredId != null) {
            return;
        }

        if (certRegisteredId != null) {
            updateRegisteredCert(certRegisteredId, revInfo);
            return;
        }

        final String sqlAddCert = revoked ? SQL_ADD_REVOKED_CERT : SQL_ADD_CERT;

        long certId = certificate.certId();
        byte[] encodedCert = certificate.encodedCert();
        String b64Cert = Base64.encodeToString(encodedCert);
        String sha1Fp = HashAlgoType.SHA1.base64Hash(encodedCert);
        String sha256Fp = HashAlgoType.SHA256.base64Hash(encodedCert);
        String sha3_256Fp = HashAlgoType.SHA3_256.base64Hash(encodedCert);

        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        X509Certificate cert = certificate.cert();
        long notBeforeSeconds = cert.getNotBefore().getTime() / 1000;
        long notAfterSeconds = cert.getNotAfter().getTime() / 1000;
        String cuttedSubject = X509Util.cutText(certificate.subject(), maxX500nameLen);

        PreparedStatement[] pss = borrowPreparedStatements(sqlAddCert, SQL_ADD_CRAW, SQL_ADD_CHASH);
        // all statements have the same connection
        Connection conn = null;

        try {
            PreparedStatement psAddcert = pss[0];
            conn = psAddcert.getConnection();

            // CERT
            int idx = 2;
            psAddcert.setLong(idx++, currentTimeSeconds);
            psAddcert.setString(idx++, serialNumber.toString(16));
            psAddcert.setLong(idx++, notBeforeSeconds);
            psAddcert.setLong(idx++, notAfterSeconds);
            setBoolean(psAddcert, idx++, revoked);
            psAddcert.setInt(idx++, issuerId);
            psAddcert.setString(idx++, certprofile);

            if (revoked) {
                long revTime = revInfo.revocationTime().getTime() / 1000;
                psAddcert.setLong(idx++, revTime);
                if (revInfo.invalidityTime() != null) {
                    psAddcert.setLong(idx++, revInfo.invalidityTime().getTime() / 1000);
                } else {
                    psAddcert.setNull(idx++, Types.BIGINT);
                }
                int reasonCode = (revInfo.reason() == null) ? 0 : revInfo.reason().code();
                psAddcert.setInt(idx++, reasonCode);
            }

            // CRAW
            PreparedStatement psAddRawcert = pss[1];

            idx = 2;
            psAddRawcert.setString(idx++, cuttedSubject);
            psAddRawcert.setString(idx++, b64Cert);

            // CHASH
            PreparedStatement psAddCerthash = pss[2];

            idx = 2;
            psAddCerthash.setString(idx++, sha1Fp);
            psAddCerthash.setString(idx++, sha256Fp);
            psAddCerthash.setString(idx++, sha3_256Fp);

            psAddcert.setLong(1, certId);
            psAddCerthash.setLong(1, certId);
            psAddRawcert.setLong(1, certId);

            final boolean origAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            String sql = null;

            try {
                sql = sqlAddCert;
                psAddcert.executeUpdate();

                sql = SQL_ADD_CHASH;
                psAddRawcert.executeUpdate();

                sql = SQL_ADD_CHASH;
                psAddCerthash.executeUpdate();

                sql = "(commit add cert to OCSP)";
                conn.commit();
            } catch (Throwable th) {
                conn.rollback();
                // more secure
                datasource.deleteFromTable(null, "CRAW", "CID", certId);
                datasource.deleteFromTable(null, "CHASH", "CID", certId);
                datasource.deleteFromTable(null, "CERT", "ID", certId);

                if (th instanceof SQLException) {
                    SQLException ex = (SQLException) th;
                    LOG.error("datasource {} could not add certificate with id {}: {}",
                            datasource.datasourceName(), certId, th.getMessage());
                    throw datasource.translate(sql, ex);
                } else {
                    throw new OperationException(ErrorCode.SYSTEM_FAILURE, th);
                }
            } finally {
                conn.setAutoCommit(origAutoCommit);
            }
        } catch (SQLException ex) {
            throw datasource.translate(null, ex);
        } finally {
            for (PreparedStatement ps : pss) {
                try {
                    ps.close();
                } catch (Throwable th) {
                    LOG.warn("could not close PreparedStatement", th);
                }

            }
            if (conn != null) {
                datasource.returnConnection(conn);
            }
        }
    } // method addOrUpdateCert

    private void updateRegisteredCert(long registeredCertId, CertRevocationInfo revInfo)
            throws DataAccessException {
        boolean revoked = (revInfo != null);

        final String sql = "UPDATE CERT SET LUPDATE=?,REV=?,RT=?,RIT=?,RR=? WHERE ID=?";

        long currentTimeSeconds = System.currentTimeMillis() / 1000;

        PreparedStatement ps = borrowPreparedStatement(sql);

        try {
            int idx = 1;
            ps.setLong(idx++, currentTimeSeconds);
            setBoolean(ps, idx++, revoked);
            if (revoked) {
                long revTime = revInfo.revocationTime().getTime() / 1000;
                ps.setLong(idx++, revTime);
                if (revInfo.invalidityTime() != null) {
                    ps.setLong(idx++, revInfo.invalidityTime().getTime() / 1000);
                } else {
                    ps.setNull(idx++, Types.INTEGER);
                }
                ps.setInt(idx++, revInfo.reason().code());
            } else {
                ps.setNull(idx++, Types.INTEGER); // rev_time
                ps.setNull(idx++, Types.INTEGER); // rev_invalidity_time
                ps.setNull(idx++, Types.INTEGER); // rev_reason
            }
            ps.setLong(idx++, registeredCertId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(ps, null);
        }
    }

    void revokeCert(X509Cert caCert, X509CertWithDbId cert, String certprofile,
            CertRevocationInfo revInfo) throws DataAccessException, OperationException {
        addOrUpdateCert(caCert, cert, certprofile, revInfo);
    }

    void unrevokeCert(X509Cert issuer, X509CertWithDbId cert) throws DataAccessException {
        ParamUtil.requireNonNull("issuer", issuer);
        ParamUtil.requireNonNull("cert", cert);

        Integer issuerId = issuerStore.getIdForCert(issuer.encodedCert());
        if (issuerId == null) {
            return;
        }

        BigInteger serialNumber = cert.cert().getSerialNumber();
        Long certRegisteredId = getCertId(issuerId, serialNumber);

        if (certRegisteredId == null) {
            return;
        }

        if (publishGoodCerts) {
            final String sql = "UPDATE CERT SET LUPDATE=?,REV=?,RT=?,RIT=?,RR=? WHERE ID=?";
            PreparedStatement ps = borrowPreparedStatement(sql);

            try {
                int idx = 1;
                ps.setLong(idx++, System.currentTimeMillis() / 1000);
                setBoolean(ps, idx++, false);
                ps.setNull(idx++, Types.INTEGER);
                ps.setNull(idx++, Types.INTEGER);
                ps.setNull(idx++, Types.INTEGER);
                ps.setLong(idx++, certRegisteredId);
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw datasource.translate(sql, ex);
            } finally {
                datasource.releaseResources(ps, null);
            }
        } else {
            final String sql = "DELETE FROM CERT WHERE IID=? AND SN=?";
            PreparedStatement ps = borrowPreparedStatement(sql);

            try {
                int idx = 1;
                ps.setInt(idx++, issuerId);
                ps.setString(idx++, serialNumber.toString(16));
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw datasource.translate(sql, ex);
            } finally {
                datasource.releaseResources(ps, null);
            }
        }

    } // method unrevokeCert

    void removeCert(X509Cert issuer, X509CertWithDbId cert) throws DataAccessException {
        ParamUtil.requireNonNull("issuer", issuer);
        ParamUtil.requireNonNull("cert", cert);

        Integer issuerId = issuerStore.getIdForCert(issuer.encodedCert());
        if (issuerId == null) {
            return;
        }

        final String sql = "DELETE FROM CERT WHERE IID=? AND SN=?";
        PreparedStatement ps = borrowPreparedStatement(sql);

        try {
            ps.setInt(1, issuerId);
            ps.setString(2, cert.cert().getSerialNumber().toString(16));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(ps, null);
        }
    } // method removeCert

    void revokeCa(X509Cert caCert, CertRevocationInfo revInfo) throws DataAccessException {
        ParamUtil.requireNonNull("caCert", caCert);
        ParamUtil.requireNonNull("revInfo", revInfo);

        Date revocationTime = revInfo.revocationTime();
        Date invalidityTime = revInfo.invalidityTime();
        if (invalidityTime == null) {
            invalidityTime = revocationTime;
        }

        int issuerId = getIssuerId(caCert);
        final String sql = "UPDATE ISSUER SET REV=?,RT=?,RIT=?,RR=? WHERE ID=?";
        PreparedStatement ps = borrowPreparedStatement(sql);

        try {
            int idx = 1;
            setBoolean(ps, idx++, true);
            ps.setLong(idx++, revocationTime.getTime() / 1000);
            ps.setLong(idx++, invalidityTime.getTime() / 1000);
            ps.setInt(idx++, revInfo.reason().code());
            ps.setInt(idx++, issuerId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(ps, null);
        }
    } // method revokeCa

    void unrevokeCa(X509Cert caCert) throws DataAccessException {
        int issuerId = getIssuerId(caCert);
        final String sql = "UPDATE ISSUER SET REV=?,RT=?,RIT=?,RR=? WHERE ID=?";
        PreparedStatement ps = borrowPreparedStatement(sql);

        try {
            int idx = 1;
            setBoolean(ps, idx++, false);
            ps.setNull(idx++, Types.INTEGER);
            ps.setNull(idx++, Types.INTEGER);
            ps.setNull(idx++, Types.INTEGER);
            ps.setInt(idx++, issuerId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(ps, null);
        }
    } // method unrevokeCa

    private int getIssuerId(X509Cert issuerCert) throws DataAccessException {
        ParamUtil.requireNonNull("issuerCert", issuerCert);
        Integer id = issuerStore.getIdForCert(issuerCert.encodedCert());
        if (id == null) {
            throw new IllegalStateException("could not find issuer, "
                    + "please start XiPKI in master mode first the restart this XiPKI system");
        }
        return id.intValue();
    }

    void addIssuer(X509Cert issuerCert) throws DataAccessException {
        if (issuerStore.getIdForCert(issuerCert.encodedCert()) != null) {
            return;
        }

        String sha1FpCert = HashAlgoType.SHA1.base64Hash(issuerCert.encodedCert());
        long maxId = datasource.getMax(null, "ISSUER", "ID");
        int id = (int) maxId + 1;

        byte[] encodedCert = issuerCert.encodedCert();
        long notBeforeSeconds = issuerCert.cert().getNotBefore().getTime() / 1000;
        long notAfterSeconds = issuerCert.cert().getNotAfter().getTime() / 1000;

        final String sql =
                "INSERT INTO ISSUER (ID,SUBJECT,NBEFORE,NAFTER,S1C,CERT) VALUES (?,?,?,?,?,?)";

        PreparedStatement ps = borrowPreparedStatement(sql);

        try {
            String b64Cert = Base64.encodeToString(encodedCert);
            String subject = issuerCert.subject();
            int idx = 1;
            ps.setInt(idx++, id);
            ps.setString(idx++, subject);
            ps.setLong(idx++, notBeforeSeconds);
            ps.setLong(idx++, notAfterSeconds);
            ps.setString(idx++, sha1FpCert);
            ps.setString(idx++, b64Cert);

            ps.execute();

            IssuerEntry newInfo = new IssuerEntry(id, subject, sha1FpCert, b64Cert);
            issuerStore.addIdentityEntry(newInfo);
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(ps, null);
        }
    } // method addIssuer

    /**
     * @param sqlQuery the SQL query
     * @return the next idle preparedStatement, {@code null} will be returned if no PreparedStament
     *      can be created within 5 seconds.
     */
    private PreparedStatement borrowPreparedStatement(String sqlQuery) throws DataAccessException {
        PreparedStatement ps = null;
        Connection col = datasource.getConnection();
        if (col != null) {
            ps = datasource.prepareStatement(col, sqlQuery);
        }
        if (ps == null) {
            throw new DataAccessException("could not create prepared statement for " + sqlQuery);
        }
        return ps;
    }

    private PreparedStatement[] borrowPreparedStatements(String... sqlQueries)
            throws DataAccessException {
        PreparedStatement[] pss = new PreparedStatement[sqlQueries.length];

        Connection conn = datasource.getConnection();
        if (conn != null) {
            int n = sqlQueries.length;
            for (int i = 0; i < n; i++) {
                pss[i] = datasource.prepareStatement(conn, sqlQueries[i]);
                if (pss[i] != null) {
                    continue;
                }

                for (int j = 0; j < i; j++) {
                    try {
                        pss[j].close();
                    } catch (Throwable th) {
                        LOG.warn("could not close preparedStatement", th);
                    }
                }

                try {
                    conn.close();
                } catch (Throwable th) {
                    LOG.warn("could not close connection", th);
                }

                throw new DataAccessException(
                        "could not create prepared statement for " + sqlQueries[i]);
            }
        } // end if

        return pss;
    } // method borrowPreparedStatements

    /**
     * Returns the database Id for the given issuer and serialNumber.
     * @return the database table id if registered, <code>null</code> otherwise.
     */
    private Long getCertId(int issuerId, BigInteger serialNumber) throws DataAccessException {
        final String sql = sqlCertRegistered;
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try {
            int idx = 1;
            ps.setString(idx++, serialNumber.toString(16));
            ps.setInt(idx++, issuerId);

            rs = ps.executeQuery();
            return rs.next() ? rs.getLong("ID") : null;
        } catch (SQLException ex) {
            throw datasource.translate(sql, ex);
        } finally {
            datasource.releaseResources(ps, rs);
        }
    } // method getCertId

    boolean isHealthy() {
        final String sql = "SELECT ID FROM ISSUER";

        try {
            ResultSet rs = null;
            PreparedStatement ps = borrowPreparedStatement(sql);

            try {
                rs = ps.executeQuery();
            } finally {
                datasource.releaseResources(ps, rs);
            }
            return true;
        } catch (Exception ex) {
            LogUtil.error(LOG, ex);
            return false;
        }
    } // method isHealthy

    private static void setBoolean(PreparedStatement ps, int index, boolean value)
            throws SQLException {
        ps.setInt(index, value ? 1 : 0);
    }

}
